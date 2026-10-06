use std::{future::Future, sync::Arc, time::Duration};

use serde_json::{Value, json};
use sqlx::{FromRow, PgPool, Postgres, Transaction};
use subtle::ConstantTimeEq;
use time::{Duration as TimeDuration, OffsetDateTime};
use uuid::Uuid;

use crate::{
    application::ports::{
        ingest_authorization::{
            AuthorizeIngestCommand, AuthorizeIngestResult, IngestAuthorization,
            IngestAuthorizationError, IngestAuthorizationRepository,
        },
        media_node_assignment::{MediaNodeAssignmentPolicy, MediaNodeLoad},
        session_clock::SessionMonotonicClock,
    },
    domain::ids::{SessionId, StreamId},
};

const PLATFORM_LIVE_SESSION_LIMIT: i64 = 5;
const OWNER_LEASE: Duration = Duration::from_secs(15);

pub struct PostgresIngestAuthorizationRepository {
    pool: PgPool,
    media_node_assignment: Arc<dyn MediaNodeAssignmentPolicy>,
}

impl PostgresIngestAuthorizationRepository {
    pub fn new(pool: PgPool, media_node_assignment: Arc<dyn MediaNodeAssignmentPolicy>) -> Self {
        Self {
            pool,
            media_node_assignment,
        }
    }

    async fn authorize_in_transaction(
        &self,
        command: AuthorizeIngestCommand,
        session_clock: &dyn SessionMonotonicClock,
    ) -> Result<AuthorizeIngestResult, IngestAuthorizationError> {
        let mut transaction = self.pool.begin().await.map_err(persistence_error)?;
        sqlx::query("SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE")
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
        let has_capacity_lock = sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS (SELECT 1 FROM streaming_capacity_lock WHERE singleton = TRUE)",
        )
        .fetch_one(&mut *transaction)
        .await
        .map_err(persistence_error)?;
        if !has_capacity_lock {
            return Err(IngestAuthorizationError::InvalidStoredData);
        }
        sqlx::query(
            "SELECT singleton FROM streaming_capacity_lock WHERE singleton = TRUE FOR UPDATE",
        )
        .fetch_one(&mut *transaction)
        .await
        .map_err(persistence_error)?;

        if let Some(existing) = sqlx::query_as::<_, ExistingAttempt>(
            "SELECT payload_hash, response_payload FROM ingest_authorizations WHERE ingest_attempt_id = $1",
        )
        .bind(command.ingest_attempt_id)
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?
        {
            if existing.payload_hash.len() != 32
                || existing
                    .payload_hash
                    .as_slice()
                    .ct_eq(command.request_fingerprint.as_slice())
                    .unwrap_u8()
                    != 1
            {
                return Err(IngestAuthorizationError::IdempotencyKeyReused);
            }

            let authorization = serde_json::from_value(existing.response_payload)
                .map_err(|_| IngestAuthorizationError::InvalidStoredData)?;
            transaction.commit().await.map_err(persistence_error)?;
            return Ok(AuthorizeIngestResult {
                authorization,
                sessions_ended: Vec::new(),
            });
        }

        let stream_config = sqlx::query_as::<_, StreamKeyConfig>(
            "SELECT stream_id, channel_id, stream_generation, source_generation FROM stream_configs WHERE ingest_key_hash = $1 FOR UPDATE",
        )
        .bind(command.stream_key_hash.as_slice())
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?
        .ok_or(IngestAuthorizationError::InvalidStreamKey)?;
        if command
            .expected_stream_id
            .as_ref()
            .is_some_and(|id| id.as_str() != stream_config.stream_id)
        {
            return Err(IngestAuthorizationError::InvalidStreamKey);
        }

        let active_session = sqlx::query_as::<_, ActiveSession>(
            "SELECT session_id, stream_id, channel_id, stream_generation, source_generation, source_claimed, status, session_version, owner_instance_id, owner_lease_expires_at, count_version, timeline_position_ms FROM stream_sessions WHERE channel_id = $1 AND status <> 'ENDED' FOR UPDATE",
        )
        .bind(&stream_config.channel_id)
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?;

        // Sample time after locking the active session so lock wait cannot make an expired
        // owner lease appear valid.
        let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
            .fetch_one(&mut *transaction)
            .await
            .map_err(persistence_error)?;
        let lease_expires_at = now + TimeDuration::seconds(OWNER_LEASE.as_secs() as i64);

        let mut sessions_ended = Vec::new();
        if let Some(active) = active_session {
            if active.stream_id != stream_config.stream_id {
                return Err(IngestAuthorizationError::InvalidStoredData);
            }

            let same_owner =
                active.owner_instance_id.as_deref() == Some(command.owner_instance_id.as_str());
            let owner_lease_valid = active
                .owner_lease_expires_at
                .is_some_and(|expires_at| expires_at > now);
            if !same_owner && owner_lease_valid {
                return Err(IngestAuthorizationError::SessionOwnedElsewhere);
            }

            match active.status.as_str() {
                "LIVE" if same_owner && owner_lease_valid => {
                    return Err(IngestAuthorizationError::ChannelAlreadyActive);
                }
                "LIVE" => {
                    end_session(&mut transaction, &active, session_clock, now).await?;
                    sessions_ended.push(
                        SessionId::parse(active.session_id.clone())
                            .ok_or(IngestAuthorizationError::InvalidStoredData)?,
                    );
                }
                "PREPARING" => {
                    if same_owner
                        && owner_lease_valid
                        && session_clock
                            .preparing_elapsed(
                                &SessionId::parse(active.session_id.clone())
                                    .ok_or(IngestAuthorizationError::InvalidStoredData)?,
                            )
                            .is_some_and(|elapsed| {
                                elapsed < crate::domain::session::PREPARING_TIMEOUT
                            })
                    {
                        return Err(IngestAuthorizationError::ChannelAlreadyActive);
                    }

                    end_session(&mut transaction, &active, session_clock, now).await?;
                    sessions_ended.push(
                        SessionId::parse(active.session_id.clone())
                            .ok_or(IngestAuthorizationError::InvalidStoredData)?,
                    );
                }
                "RECONNECT_GRACE" => {
                    let active_session_id = SessionId::parse(active.session_id.clone())
                        .ok_or(IngestAuthorizationError::InvalidStoredData)?;
                    let reconnect_elapsed = session_clock.reconnect_elapsed(&active_session_id);
                    let mut ended_at = now;
                    if may_resume_reconnect(same_owner, owner_lease_valid, reconnect_elapsed) {
                        if active.source_claimed {
                            return Err(IngestAuthorizationError::ChannelAlreadyActive);
                        }
                        let source_generation = stream_config
                            .source_generation
                            .checked_add(1)
                            .filter(|generation| *generation > active.source_generation)
                            .ok_or(IngestAuthorizationError::InvalidStoredData)?;
                        // Recheck expiry in the write itself. The lease could expire after the
                        // earlier clock sample while this transaction waited or did other work.
                        let renewed_session = sqlx::query(
                            "UPDATE stream_sessions SET source_generation = $2, source_claimed = TRUE, owner_lease_expires_at = clock_timestamp() + ($3 * INTERVAL '1 second'), updated_at = clock_timestamp() WHERE session_id = $1 AND status = 'RECONNECT_GRACE' AND source_claimed = FALSE AND owner_instance_id = $4 AND owner_lease_expires_at > clock_timestamp()",
                        )
                        .bind(&active.session_id)
                        .bind(source_generation)
                        .bind(OWNER_LEASE.as_secs() as f64)
                        .bind(&command.owner_instance_id)
                        .execute(&mut *transaction)
                        .await
                        .map_err(persistence_error)?;

                        if renewed_session.rows_affected() == 1 {
                            sqlx::query(
                                "UPDATE stream_configs SET source_generation = $2, updated_at = $3 WHERE stream_id = $1",
                            )
                            .bind(&stream_config.stream_id)
                            .bind(source_generation)
                            .bind(now)
                            .execute(&mut *transaction)
                            .await
                            .map_err(persistence_error)?;
                            // Keep the original loss anchor and deadline: authorization alone
                            // does not win a reconnect; playback must be verified before expiry.
                            let authorization = IngestAuthorization {
                                stream_id: crate::domain::ids::StreamId::parse(
                                    stream_config.stream_id.clone(),
                                )
                                .ok_or(IngestAuthorizationError::InvalidStoredData)?,
                                session_id: active_session_id,
                                stream_generation: active.stream_generation,
                                source_generation,
                            };
                            persist_authorization(&mut transaction, &command, &authorization)
                                .await?;
                            bump_capacity_revision(&mut transaction).await?;
                            transaction.commit().await.map_err(persistence_error)?;
                            return Ok(AuthorizeIngestResult {
                                authorization,
                                sessions_ended,
                            });
                        }

                        // The lease expired between the clock sample and the guarded update.
                        ended_at =
                            sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
                                .fetch_one(&mut *transaction)
                                .await
                                .map_err(persistence_error)?;
                    }

                    // If its clock was lost or its lease expired, close the old session instead
                    // of reviving it. A new authorization below receives a new session identity.
                    end_session(&mut transaction, &active, session_clock, ended_at).await?;
                    sessions_ended.push(active_session_id);
                }
                _ => return Err(IngestAuthorizationError::InvalidStoredData),
            }
        }

        let active_count = sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM stream_sessions WHERE status <> 'ENDED'",
        )
        .fetch_one(&mut *transaction)
        .await
        .map_err(persistence_error)?;
        if active_count >= PLATFORM_LIVE_SESSION_LIMIT {
            return Err(IngestAuthorizationError::LiveSessionLimit);
        }

        let stream_generation = stream_config
            .stream_generation
            .checked_add(1)
            .filter(|generation| *generation > 0)
            .ok_or(IngestAuthorizationError::InvalidStoredData)?;
        let source_generation = stream_config
            .source_generation
            .checked_add(1)
            .filter(|generation| *generation > 0)
            .ok_or(IngestAuthorizationError::InvalidStoredData)?;
        let stream_id = StreamId::parse(stream_config.stream_id.clone())
            .ok_or(IngestAuthorizationError::InvalidStoredData)?;
        let available_media_nodes = sqlx::query_as::<_, MediaNodeLoadRow>(
            "SELECT n.media_node_id, COUNT(s.session_id)::BIGINT AS active_sessions FROM media_nodes n LEFT JOIN stream_sessions s ON s.media_node_id = n.media_node_id AND s.status <> 'ENDED' WHERE n.enabled = TRUE GROUP BY n.media_node_id ORDER BY n.media_node_id",
        )
        .fetch_all(&mut *transaction)
        .await
        .map_err(persistence_error)?
        .into_iter()
        .map(Into::into)
        .collect::<Vec<_>>();
        let media_node_id = self
            .media_node_assignment
            .select_node(&stream_id, &available_media_nodes)
            .map_err(|_| IngestAuthorizationError::MediaNodeUnavailable)?;
        let session_id = SessionId::new();
        let preparing_deadline_at =
            now + TimeDuration::seconds(crate::domain::session::PREPARING_TIMEOUT.as_secs() as i64);
        let authorization = IngestAuthorization {
            stream_id,
            session_id: session_id.clone(),
            stream_generation,
            source_generation,
        };
        sqlx::query(
            "UPDATE stream_configs SET stream_generation = $2, source_generation = $3, updated_at = $4 WHERE stream_id = $1",
        )
        .bind(&stream_config.stream_id)
        .bind(stream_generation)
        .bind(source_generation)
        .bind(now)
        .execute(&mut *transaction)
        .await
        .map_err(persistence_error)?;
        sqlx::query(
            "INSERT INTO stream_sessions (session_id, stream_id, channel_id, stream_generation, source_generation, status, availability, media_node_id, owner_instance_id, owner_lease_expires_at, preparing_deadline_at, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, 'PREPARING', 'OFFLINE', $6, $7, $8, $9, $10, $10)",
        )
        .bind(session_id.as_str())
        .bind(&stream_config.stream_id)
        .bind(&stream_config.channel_id)
        .bind(stream_generation)
        .bind(source_generation)
        .bind(&media_node_id)
        .bind(&command.owner_instance_id)
        .bind(lease_expires_at)
        .bind(preparing_deadline_at)
        .bind(now)
        .execute(&mut *transaction)
        .await
        .map_err(persistence_error)?;

        append_session_event(
            &mut transaction,
            Uuid::now_v7(),
            session_id.as_str(),
            1,
            "StreamSessionPreparing",
            json!({
                "sessionId": session_id,
                "streamId": stream_config.stream_id,
                "streamGeneration": stream_generation,

                "status": "PREPARING",
                "availability": "OFFLINE",
                "sessionVersion": 1,

            }),
        )
        .await?;
        persist_authorization(&mut transaction, &command, &authorization).await?;
        bump_capacity_revision(&mut transaction).await?;

        // The DB transaction is serialized by streaming_capacity_lock. Install the monotonic
        // anchor before commit so the next request cannot race ahead of its owner clock.
        session_clock.start_preparing(&session_id);
        if session_clock.preparing_elapsed(&session_id).is_none() {
            return Err(IngestAuthorizationError::SessionOwnedElsewhere);
        }
        if let Err(error) = transaction.commit().await {
            session_clock.forget(&session_id);
            return Err(persistence_error(error));
        }

        Ok(AuthorizeIngestResult {
            authorization,
            sessions_ended,
        })
    }
}

fn may_resume_reconnect(
    same_owner: bool,
    owner_lease_valid: bool,
    reconnect_elapsed: Option<Duration>,
) -> bool {
    same_owner
        && owner_lease_valid
        && reconnect_elapsed
            .is_some_and(|elapsed| elapsed < crate::domain::session::RECONNECT_GRACE)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reconnect_requires_the_same_owner_a_live_lease_and_remaining_grace() {
        let under_deadline = Some(Duration::from_secs(1));

        assert!(may_resume_reconnect(true, true, under_deadline));
        assert!(!may_resume_reconnect(false, true, under_deadline));
        assert!(!may_resume_reconnect(true, false, under_deadline));
        assert!(!may_resume_reconnect(true, true, None));
        assert!(!may_resume_reconnect(
            true,
            true,
            Some(crate::domain::session::RECONNECT_GRACE),
        ));
    }
}

impl IngestAuthorizationRepository for PostgresIngestAuthorizationRepository {
    fn authorize<'a>(
        &'a self,
        command: AuthorizeIngestCommand,
        session_clock: &'a dyn SessionMonotonicClock,
    ) -> impl Future<Output = Result<AuthorizeIngestResult, IngestAuthorizationError>> + Send + 'a
    {
        async move { self.authorize_in_transaction(command, session_clock).await }
    }

    fn renew_owner_leases(
        &self,
        owner_instance_id: String,
    ) -> impl Future<Output = Result<(), IngestAuthorizationError>> + Send {
        async move {
            let mut transaction = self.pool.begin().await.map_err(persistence_error)?;
            sqlx::query(
                "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
            )
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
            let has_capacity_lock = sqlx::query_scalar::<_, bool>(
                "SELECT EXISTS (SELECT 1 FROM streaming_capacity_lock WHERE singleton = TRUE)",
            )
            .fetch_one(&mut *transaction)
            .await
            .map_err(persistence_error)?;
            if !has_capacity_lock {
                return Err(IngestAuthorizationError::InvalidStoredData);
            }
            sqlx::query(
                "SELECT singleton FROM streaming_capacity_lock WHERE singleton = TRUE FOR UPDATE",
            )
            .fetch_one(&mut *transaction)
            .await
            .map_err(persistence_error)?;
            sqlx::query(
                // An expired lease is fenced off; only the deadline worker may resolve it.
                "UPDATE stream_sessions SET owner_lease_expires_at = clock_timestamp() + ($2 * INTERVAL '1 second'), updated_at = clock_timestamp() WHERE owner_instance_id = $1 AND status <> 'ENDED' AND owner_lease_expires_at > clock_timestamp()",
            )
            .bind(owner_instance_id)
            .bind(OWNER_LEASE.as_secs() as f64)
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
            transaction.commit().await.map_err(persistence_error)?;
            Ok(())
        }
    }
}

#[derive(FromRow)]
struct ExistingAttempt {
    payload_hash: Vec<u8>,
    response_payload: Value,
}

#[derive(FromRow)]
struct StreamKeyConfig {
    stream_id: String,
    channel_id: String,
    stream_generation: i64,
    source_generation: i64,
}

#[derive(FromRow)]
struct MediaNodeLoadRow {
    media_node_id: String,
    active_sessions: i64,
}

impl From<MediaNodeLoadRow> for MediaNodeLoad {
    fn from(row: MediaNodeLoadRow) -> Self {
        Self {
            media_node_id: row.media_node_id,
            active_sessions: row.active_sessions,
        }
    }
}

#[derive(FromRow)]
struct ActiveSession {
    session_id: String,
    stream_id: String,
    stream_generation: i64,
    source_generation: i64,
    source_claimed: bool,
    status: String,
    session_version: i64,
    owner_instance_id: Option<String>,
    owner_lease_expires_at: Option<OffsetDateTime>,
    count_version: i64,
    timeline_position_ms: i64,
}

async fn end_session(
    transaction: &mut Transaction<'_, Postgres>,
    active: &ActiveSession,
    session_clock: &dyn SessionMonotonicClock,
    now: OffsetDateTime,
) -> Result<(), IngestAuthorizationError> {
    if active.timeline_position_ms < 0 {
        return Err(IngestAuthorizationError::InvalidStoredData);
    }
    let session_id = SessionId::parse(active.session_id.clone())
        .ok_or(IngestAuthorizationError::InvalidStoredData)?;
    let elapsed_ms = session_clock
        .timeline_elapsed(&session_id)
        .map(|elapsed| i64::try_from(elapsed.as_millis()))
        .transpose()
        .map_err(|_| IngestAuthorizationError::InvalidStoredData)?;
    let timeline_position_ms = active
        .timeline_position_ms
        .max(elapsed_ms.unwrap_or(active.timeline_position_ms));
    let session_version = active
        .session_version
        .checked_add(1)
        .filter(|version| *version > 0)
        .ok_or(IngestAuthorizationError::InvalidStoredData)?;
    let count_version = active
        .count_version
        .checked_add(1)
        .filter(|version| *version > 0)
        .ok_or(IngestAuthorizationError::InvalidStoredData)?;
    let result = sqlx::query(
        "UPDATE stream_sessions SET status = 'ENDED', availability = 'OFFLINE', session_version = $2, timeline_position_ms = $3, timeline_sampled_at = $4, grace_deadline_at = NULL, ended_at = $4, owner_lease_expires_at = NULL, viewer_count = 0, count_version = $5, viewer_count_observed_at = $4, updated_at = $4 WHERE session_id = $1 AND status <> 'ENDED'",
    )
    .bind(&active.session_id)
    .bind(session_version)
    .bind(timeline_position_ms)
    .bind(now)
    .bind(count_version)
    .execute(&mut **transaction)
    .await
    .map_err(persistence_error)?;
    if result.rows_affected() != 1 {
        return Err(IngestAuthorizationError::InvalidStoredData);
    }
    sqlx::query(
        "UPDATE viewer_leases SET closed_at = $2 WHERE session_id = $1 AND closed_at IS NULL",
    )
    .bind(&active.session_id)
    .bind(now)
    .execute(&mut **transaction)
    .await
    .map_err(persistence_error)?;
    append_session_event(
        transaction,
        Uuid::now_v7(),
        &active.session_id,
        session_version,
        "StreamSessionEnded",
        json!({
            "sessionId": active.session_id,
            "streamId": active.stream_id,
            "streamGeneration": active.stream_generation,

            "status": "ENDED",
            "availability": "OFFLINE",
            "sessionVersion": session_version,

        }),
    )
    .await?;
    Ok(())
}

async fn append_session_event(
    transaction: &mut Transaction<'_, Postgres>,
    event_id: Uuid,
    session_id: &str,
    sequence: i64,
    event_type: &str,
    payload: Value,
) -> Result<(), IngestAuthorizationError> {
    sqlx::query(
        "INSERT INTO streaming_outbox (event_id, aggregate_id, sequence, event_type, schema_version, payload, created_at, available_at) VALUES ($1, $2, $3, $4, 1, $5, clock_timestamp(), clock_timestamp())",
    )
    .bind(event_id)
    .bind(format!("session:{session_id}"))
    .bind(sequence)
    .bind(event_type)
    .bind(payload)
    .execute(&mut **transaction)
    .await
    .map_err(persistence_error)?;
    Ok(())
}

async fn persist_authorization(
    transaction: &mut Transaction<'_, Postgres>,
    command: &AuthorizeIngestCommand,
    authorization: &IngestAuthorization,
) -> Result<(), IngestAuthorizationError> {
    let response_payload = serde_json::to_value(authorization)
        .map_err(|_| IngestAuthorizationError::InvalidStoredData)?;
    sqlx::query(
        "INSERT INTO ingest_authorizations (ingest_attempt_id, payload_hash, stream_id, session_id, stream_generation, source_generation, response_payload) VALUES ($1, $2, $3, $4, $5, $6, $7)",
    )
    .bind(command.ingest_attempt_id)
    .bind(command.request_fingerprint.as_slice())
    .bind(authorization.stream_id.as_str())
    .bind(authorization.session_id.as_str())
    .bind(authorization.stream_generation)
    .bind(authorization.source_generation)
    .bind(response_payload)
    .execute(&mut **transaction)
    .await
    .map_err(persistence_error)?;
    Ok(())
}

async fn bump_capacity_revision(
    transaction: &mut Transaction<'_, Postgres>,
) -> Result<(), IngestAuthorizationError> {
    sqlx::query(
        "UPDATE streaming_capacity_lock SET revision = revision + 1 WHERE singleton = TRUE",
    )
    .execute(&mut **transaction)
    .await
    .map_err(persistence_error)?;
    Ok(())
}

fn persistence_error(_: sqlx::Error) -> IngestAuthorizationError {
    IngestAuthorizationError::PersistenceUnavailable
}
