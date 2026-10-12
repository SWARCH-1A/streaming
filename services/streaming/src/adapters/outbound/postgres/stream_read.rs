use std::future::Future;

use serde_json::json;
use sqlx::{FromRow, PgPool, types::Json};
use time::OffsetDateTime;
use uuid::Uuid;

use crate::{
    application::ports::streaming_repository::{
        PublicStreamSessionSnapshot, PublicStreamSnapshot, PublicStreamState, RepositoryError,
        StreamConfigSnapshot, StreamingRepository,
    },
    domain::{
        ids::{SessionId, StreamId},
        session::{Availability, SessionStatus, StreamSession},
    },
};

pub struct PostgresStreamingRepository {
    write_pool: PgPool,
    // Only the public stream summary may use a read replica; session/playback reads stay on primary.
    read_pool: Option<PgPool>,
}

impl PostgresStreamingRepository {
    pub fn new(pool: PgPool) -> Self {
        Self {
            write_pool: pool,
            read_pool: None,
        }
    }

    pub fn with_read_pool(write_pool: PgPool, read_pool: PgPool) -> Self {
        Self {
            write_pool,
            read_pool: Some(read_pool),
        }
    }
}

impl StreamingRepository for PostgresStreamingRepository {
    fn remember_catalog_values(
        &self,
        stream_id: StreamId,
        metadata_version: i64,
        values: Vec<crate::application::ports::taxonomy::TaxonomyValue>,
    ) -> impl Future<Output = Result<(), RepositoryError>> + Send {
        async move {
            let mut tx = self.write_pool.begin().await.map_err(persistence_error)?;
            sqlx::query(
                "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
            )
            .execute(&mut *tx)
            .await
            .map_err(persistence_error)?;
            // A catalog read cannot overwrite labels associated by a concurrent PATCH.
            sqlx::query("UPDATE stream_configs SET catalog_labels=$3 WHERE stream_id=$1 AND metadata_version=$2 AND catalog_labels IS DISTINCT FROM $3")
                .bind(stream_id.as_str()).bind(metadata_version).bind(Json(values))
                .execute(&mut *tx).await.map_err(persistence_error)?;
            tx.commit().await.map_err(persistence_error)?;
            Ok(())
        }
    }

    fn stored_catalog_values(
        &self,
        stream_id: StreamId,
    ) -> impl Future<
        Output = Result<Vec<crate::application::ports::taxonomy::TaxonomyValue>, RepositoryError>,
    > + Send {
        async move {
            sqlx::query_scalar::<_, Json<Vec<crate::application::ports::taxonomy::TaxonomyValue>>>(
                "SELECT catalog_labels FROM stream_configs WHERE stream_id=$1",
            )
            .bind(stream_id.as_str())
            .fetch_optional(&self.write_pool)
            .await
            .map_err(persistence_error)?
            .map(|v| v.0)
            .ok_or(RepositoryError::NotFound)
        }
    }

    fn find_stream_config(
        &self,
        stream_id: StreamId,
    ) -> impl Future<Output = Result<Option<StreamConfigSnapshot>, RepositoryError>> + Send {
        async move {
            let row = sqlx::query_as::<_, StreamConfigRow>(
                "SELECT stream_id, channel_id, owner_user_id, title, category_id, tag_ids, metadata_version, stream_generation FROM stream_configs WHERE stream_id = $1",
            )
            .bind(stream_id.as_str())
            .fetch_optional(&self.write_pool)
            .await
            .map_err(|_| RepositoryError::Unavailable)?;
            row.map(config_from_row).transpose()
        }
    }

    fn find_stream_config_by_channel(
        &self,
        channel_id: String,
    ) -> impl Future<Output = Result<Option<StreamConfigSnapshot>, RepositoryError>> + Send {
        async move {
            let row = sqlx::query_as::<_, StreamConfigRow>(
                "SELECT stream_id, channel_id, owner_user_id, title, category_id, tag_ids, metadata_version, stream_generation FROM stream_configs WHERE channel_id = $1",
            )
            .bind(channel_id)
            .fetch_optional(&self.write_pool)
            .await
            .map_err(|_| RepositoryError::Unavailable)?;
            row.map(config_from_row).transpose()
        }
    }

    fn find_session(
        &self,
        session_id: SessionId,
    ) -> impl Future<Output = Result<Option<StreamSession>, RepositoryError>> + Send {
        async move {
            let row = sqlx::query_as::<_, SessionRow>(
                "SELECT session_id, stream_id, channel_id, stream_generation, source_generation, status, availability, session_version, preparing_deadline_at, grace_deadline_at, started_at, ended_at FROM stream_sessions WHERE session_id = $1",
            )
            .bind(session_id.as_str())
            .fetch_optional(&self.write_pool)
            .await
            .map_err(|_| RepositoryError::Unavailable)?;
            row.map(session_from_row).transpose()
        }
    }

    fn find_public_session(
        &self,
        session_id: SessionId,
    ) -> impl Future<Output = Result<Option<PublicStreamSessionSnapshot>, RepositoryError>> + Send
    {
        async move {
            let row = sqlx::query_as::<_, PublicSessionRow>(
                "SELECT s.session_id, s.stream_id, s.channel_id, c.title, c.category_id, c.tag_ids, c.metadata_version, s.stream_generation, s.status, s.availability, s.session_version, CASE WHEN s.availability = 'PLAYABLE' THEN s.playback_path ELSE NULL END AS playback_path, s.timeline_position_ms, s.timeline_sampled_at, s.viewer_count, s.count_version, s.viewer_count_observed_at, (s.status='ENDED' OR s.owner_lease_expires_at>clock_timestamp()) AS owner_valid FROM stream_sessions s JOIN stream_configs c ON c.stream_id = s.stream_id WHERE s.session_id = $1",
            )
            .bind(session_id.as_str())
            .fetch_optional(&self.write_pool)
            .await
            .map_err(|_| RepositoryError::Unavailable)?;
            row.map(public_session_from_row).transpose()
        }
    }

    fn find_public_stream(
        &self,
        stream_id: StreamId,
    ) -> impl Future<Output = Result<Option<PublicStreamSnapshot>, RepositoryError>> + Send {
        async move {
            let Some(read_pool) = self.read_pool.as_ref() else {
                return load_public_stream(&self.write_pool, stream_id.as_str()).await;
            };

            let primary_insert_lsn =
                match sqlx::query_scalar::<_, String>("SELECT pg_current_wal_insert_lsn()::TEXT")
                    .fetch_one(&self.write_pool)
                    .await
                {
                    Ok(lsn) => lsn,
                    Err(_) => {
                        tracing::warn!(
                            reason = "primary_wal_probe_failed",
                            "falling back to primary for public stream read"
                        );
                        return load_public_stream(&self.write_pool, stream_id.as_str()).await;
                    }
                };
            let replica_has_replayed_primary = sqlx::query_scalar::<_, bool>(
                "SELECT COALESCE(pg_is_in_recovery() AND pg_last_wal_replay_lsn() >= $1::pg_lsn, FALSE)",
            )
            .bind(primary_insert_lsn)
            .fetch_one(read_pool)
            .await
            .unwrap_or(false);

            if replica_has_replayed_primary {
                match load_public_stream(read_pool, stream_id.as_str()).await {
                    Ok(Some(snapshot)) => return Ok(Some(snapshot)),
                    Ok(None) | Err(RepositoryError::Unavailable) => {}
                    Err(error) => return Err(error),
                }
            }

            load_public_stream(&self.write_pool, stream_id.as_str()).await
        }
    }

    fn find_active_session_for_stream(
        &self,
        stream_id: StreamId,
    ) -> impl Future<Output = Result<Option<StreamSession>, RepositoryError>> + Send {
        async move {
            let row = sqlx::query_as::<_, SessionRow>(
                "SELECT session_id, stream_id, channel_id, stream_generation, source_generation, status, availability, session_version, preparing_deadline_at, grace_deadline_at, started_at, ended_at FROM stream_sessions WHERE stream_id = $1 AND status <> 'ENDED'",
            )
            .bind(stream_id.as_str())
            .fetch_optional(&self.write_pool)
            .await
            .map_err(|_| RepositoryError::Unavailable)?;
            row.map(session_from_row).transpose()
        }
    }

    fn stop_session(
        &self,
        session_id: SessionId,
        timeline_position_ms: Option<i64>,
        authorization_expires_at: Option<std::time::Instant>,
    ) -> impl Future<Output = Result<StreamSession, RepositoryError>> + Send {
        async move {
            let mut transaction = self.write_pool.begin().await.map_err(persistence_error)?;
            crate::adapters::outbound::authorization_budget::configure(
                &mut transaction,
                authorization_expires_at,
            )
            .await
            .map_err(persistence_error)?;
            sqlx::query(
                "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
            )
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
            let row = sqlx::query_as::<_, StopSessionRow>(
                "SELECT session_id, stream_id, channel_id, stream_generation, source_generation, status, availability, session_version, preparing_deadline_at, grace_deadline_at, started_at, ended_at, count_version, timeline_position_ms FROM stream_sessions WHERE session_id = $1 FOR UPDATE",
            )
            .bind(session_id.as_str())
            .fetch_optional(&mut *transaction)
            .await
            .map_err(persistence_error)?
            .ok_or(RepositoryError::NotFound)?;

            let was_ended = row.status == "ENDED";
            let mut session = session_from_row(SessionRow {
                session_id: row.session_id.clone(),
                stream_id: row.stream_id.clone(),
                channel_id: row.channel_id.clone(),
                stream_generation: row.stream_generation,
                source_generation: row.source_generation,
                status: row.status.clone(),
                availability: row.availability.clone(),
                session_version: row.session_version,
                preparing_deadline_at: row.preparing_deadline_at,
                grace_deadline_at: row.grace_deadline_at,
                started_at: row.started_at,
                ended_at: row.ended_at,
            })?;

            if was_ended {
                crate::adapters::outbound::authorization_budget::valid(authorization_expires_at)
                    .map_err(persistence_error)?;
                transaction.commit().await.map_err(persistence_error)?;
                return Ok(session);
            }

            let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
                .fetch_one(&mut *transaction)
                .await
                .map_err(persistence_error)?;
            if row.timeline_position_ms < 0 {
                return Err(RepositoryError::InvalidStoredData);
            }
            if timeline_position_ms.is_some_and(|position| position < 0) {
                return Err(RepositoryError::InvalidStoredData);
            }
            let timeline_position_ms = row
                .timeline_position_ms
                .max(timeline_position_ms.unwrap_or(row.timeline_position_ms));
            let session_version = row
                .session_version
                .checked_add(1)
                .filter(|version| *version > 0)
                .ok_or(RepositoryError::InvalidStoredData)?;
            let count_version = row
                .count_version
                .checked_add(1)
                .filter(|version| *version > 0)
                .ok_or(RepositoryError::InvalidStoredData)?;

            sqlx::query(
                "UPDATE stream_sessions SET status = 'ENDED', availability = 'OFFLINE', session_version = $2, timeline_position_ms = $3, timeline_sampled_at = $4, grace_deadline_at = NULL, ended_at = $4, owner_lease_expires_at = NULL, viewer_count = 0, count_version = $5, viewer_count_observed_at = $4, updated_at = $4 WHERE session_id = $1",
            )
            .bind(&row.session_id)
            .bind(session_version)
            .bind(timeline_position_ms)
            .bind(now)
            .bind(count_version)
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
            sqlx::query(
                "UPDATE viewer_leases SET closed_at = $2 WHERE session_id = $1 AND closed_at IS NULL",
            )
            .bind(&row.session_id)
            .bind(now)
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;

            let event_payload = json!({
                "sessionId": row.session_id,
                "streamId": row.stream_id,
                "streamGeneration": row.stream_generation,

                "status": "ENDED",
                "availability": "OFFLINE",
                "sessionVersion": session_version,

            });
            sqlx::query(
                "INSERT INTO streaming_outbox (event_id, aggregate_id, sequence, event_type, schema_version, payload, created_at, available_at) VALUES ($1, $2, $3, 'StreamSessionEnded', 1, $4, $5, $5)",
            )
            .bind(Uuid::now_v7())
            .bind(format!("session:{}", row.session_id))
            .bind(session_version)
            .bind(event_payload)
            .bind(now)
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;

            crate::adapters::outbound::authorization_budget::valid(authorization_expires_at)
                .map_err(persistence_error)?;
            transaction.commit().await.map_err(persistence_error)?;
            session.status = SessionStatus::Ended;
            session.availability = Availability::Offline;
            session.grace_deadline_at = None;
            session.ended_at = Some(now);
            session.session_version = session_version;
            Ok(session)
        }
    }
}

async fn load_public_stream(
    pool: &PgPool,
    stream_id: &str,
) -> Result<Option<PublicStreamSnapshot>, RepositoryError> {
    let row = sqlx::query_as::<_, PublicStreamRow>(
        "SELECT c.stream_id, c.channel_id, c.title, c.category_id, c.tag_ids, c.metadata_version, c.stream_generation, s.session_id, s.stream_generation AS session_stream_generation, s.status, s.availability, s.session_version, s.viewer_count, s.count_version, s.viewer_count_observed_at, (s.status='ENDED' OR s.owner_lease_expires_at>clock_timestamp()) AS owner_valid FROM stream_configs c LEFT JOIN stream_sessions s ON s.stream_id = c.stream_id AND s.stream_generation = c.stream_generation WHERE c.stream_id = $1",
    )
    .bind(stream_id)
    .fetch_optional(pool)
    .await
    .map_err(|_| RepositoryError::Unavailable)?;
    row.map(public_stream_from_row).transpose()
}

#[derive(FromRow)]
struct StreamConfigRow {
    stream_id: String,
    channel_id: String,
    owner_user_id: String,
    title: String,
    category_id: String,
    tag_ids: Json<Vec<String>>,
    metadata_version: i64,
    stream_generation: i64,
}

#[derive(FromRow)]
struct SessionRow {
    session_id: String,
    stream_id: String,
    channel_id: String,
    stream_generation: i64,
    source_generation: i64,
    status: String,
    availability: String,
    session_version: i64,
    preparing_deadline_at: OffsetDateTime,
    grace_deadline_at: Option<OffsetDateTime>,
    started_at: Option<OffsetDateTime>,
    ended_at: Option<OffsetDateTime>,
}

#[derive(FromRow)]
struct PublicSessionRow {
    owner_valid: Option<bool>,
    session_id: String,
    stream_id: String,
    channel_id: String,
    title: String,
    category_id: String,
    tag_ids: Json<Vec<String>>,
    metadata_version: i64,
    stream_generation: i64,
    status: String,
    availability: String,
    session_version: i64,
    playback_path: Option<String>,
    timeline_position_ms: i64,
    timeline_sampled_at: Option<OffsetDateTime>,
    viewer_count: i64,
    count_version: i64,
    viewer_count_observed_at: Option<OffsetDateTime>,
}

#[derive(FromRow)]
struct PublicStreamRow {
    owner_valid: Option<bool>,
    stream_id: String,
    channel_id: String,
    title: String,
    category_id: String,
    tag_ids: Json<Vec<String>>,
    metadata_version: i64,
    stream_generation: i64,
    session_id: Option<String>,
    session_stream_generation: Option<i64>,
    status: Option<String>,
    availability: Option<String>,
    session_version: Option<i64>,
    viewer_count: Option<i64>,
    count_version: Option<i64>,
    viewer_count_observed_at: Option<OffsetDateTime>,
}

#[derive(FromRow)]
struct StopSessionRow {
    session_id: String,
    stream_id: String,
    channel_id: String,
    stream_generation: i64,
    source_generation: i64,
    status: String,
    availability: String,
    session_version: i64,
    preparing_deadline_at: OffsetDateTime,
    grace_deadline_at: Option<OffsetDateTime>,
    started_at: Option<OffsetDateTime>,
    ended_at: Option<OffsetDateTime>,
    count_version: i64,
    timeline_position_ms: i64,
}

fn config_from_row(row: StreamConfigRow) -> Result<StreamConfigSnapshot, RepositoryError> {
    Ok(StreamConfigSnapshot {
        stream_id: StreamId::parse(row.stream_id).ok_or(RepositoryError::InvalidStoredData)?,
        channel_id: row.channel_id,
        owner_user_id: row.owner_user_id,
        title: row.title,
        category_id: row.category_id,
        tag_ids: row.tag_ids.0,
        metadata_version: row.metadata_version,
        stream_generation: row.stream_generation,
    })
}

fn session_from_row(row: SessionRow) -> Result<StreamSession, RepositoryError> {
    Ok(StreamSession {
        session_id: SessionId::parse(row.session_id).ok_or(RepositoryError::InvalidStoredData)?,
        stream_id: StreamId::parse(row.stream_id).ok_or(RepositoryError::InvalidStoredData)?,
        channel_id: row.channel_id,
        stream_generation: row.stream_generation,
        source_generation: row.source_generation,
        status: parse_status(&row.status)?,
        availability: parse_availability(&row.availability)?,
        session_version: row.session_version,
        preparing_deadline_at: row.preparing_deadline_at,
        grace_deadline_at: row.grace_deadline_at,
        started_at: row.started_at,
        ended_at: row.ended_at,
    })
}

fn public_session_from_row(
    row: PublicSessionRow,
) -> Result<PublicStreamSessionSnapshot, RepositoryError> {
    if row.owner_valid != Some(true) {
        return Err(RepositoryError::Unavailable);
    }
    if row.timeline_position_ms < 0 || row.viewer_count < 0 || row.count_version < 0 {
        return Err(RepositoryError::InvalidStoredData);
    }
    let stream_id = StreamId::parse(row.stream_id).ok_or(RepositoryError::InvalidStoredData)?;
    let session_id = SessionId::parse(row.session_id).ok_or(RepositoryError::InvalidStoredData)?;
    let status = parse_status(&row.status)?;
    let availability = parse_availability(&row.availability)?;
    let playback_path = match (availability, row.playback_path) {
        (Availability::Playable, Some(path)) if path.starts_with("/hls/") => Some(path),
        (Availability::Playable, _) => return Err(RepositoryError::InvalidStoredData),
        (_, _) => None,
    };
    Ok(PublicStreamSessionSnapshot {
        stream_id,
        session_id,
        channel_id: row.channel_id,
        title: row.title,
        category_id: row.category_id,
        tag_ids: row.tag_ids.0,
        metadata_version: row.metadata_version,
        stream_generation: row.stream_generation,
        status,
        availability,
        session_version: row.session_version,
        playback_path,
        timeline_position_ms: row.timeline_position_ms,
        timeline_sampled_at: row.timeline_sampled_at,
        viewer_count: row.viewer_count,
        count_version: row.count_version,
        viewer_count_observed_at: row.viewer_count_observed_at,
    })
}

fn public_stream_from_row(row: PublicStreamRow) -> Result<PublicStreamSnapshot, RepositoryError> {
    if row.metadata_version < 1 || row.stream_generation < 0 {
        return Err(RepositoryError::InvalidStoredData);
    }

    let stream_id = StreamId::parse(row.stream_id).ok_or(RepositoryError::InvalidStoredData)?;
    let session = match row.session_id {
        Some(session_id) => {
            if row.owner_valid != Some(true) {
                return Err(RepositoryError::Unavailable);
            }
            let session_stream_generation = row
                .session_stream_generation
                .ok_or(RepositoryError::InvalidStoredData)?;
            let status = parse_status(
                row.status
                    .as_deref()
                    .ok_or(RepositoryError::InvalidStoredData)?,
            )?;
            let availability = parse_availability(
                row.availability
                    .as_deref()
                    .ok_or(RepositoryError::InvalidStoredData)?,
            )?;
            let session_version = row
                .session_version
                .filter(|version| *version > 0)
                .ok_or(RepositoryError::InvalidStoredData)?;
            let viewer_count = row
                .viewer_count
                .filter(|count| *count >= 0)
                .ok_or(RepositoryError::InvalidStoredData)?;
            let count_version = row
                .count_version
                .filter(|version| *version >= 0)
                .ok_or(RepositoryError::InvalidStoredData)?;

            if session_stream_generation != row.stream_generation
                || !public_state_is_consistent(status, availability)
            {
                return Err(RepositoryError::InvalidStoredData);
            }

            Some(PublicStreamState {
                session_id: SessionId::parse(session_id)
                    .ok_or(RepositoryError::InvalidStoredData)?,
                status,
                availability,
                session_version,
                viewer_count,
                count_version,
                viewer_count_observed_at: row.viewer_count_observed_at,
            })
        }
        None => {
            if row.stream_generation != 0
                || row.session_stream_generation.is_some()
                || row.status.is_some()
                || row.availability.is_some()
                || row.session_version.is_some()
                || row.viewer_count.is_some()
                || row.count_version.is_some()
                || row.viewer_count_observed_at.is_some()
            {
                return Err(RepositoryError::InvalidStoredData);
            }
            None
        }
    };

    Ok(PublicStreamSnapshot {
        stream_id,
        channel_id: row.channel_id,
        title: row.title,
        category_id: row.category_id,
        tag_ids: row.tag_ids.0,
        metadata_version: row.metadata_version,
        stream_generation: row.stream_generation,
        session,
    })
}

fn public_state_is_consistent(status: SessionStatus, availability: Availability) -> bool {
    matches!(
        (status, availability),
        (
            SessionStatus::Preparing | SessionStatus::Ended,
            Availability::Offline
        ) | (SessionStatus::Live, Availability::Playable)
            | (SessionStatus::ReconnectGrace, Availability::Reconnecting)
    )
}

fn parse_status(value: &str) -> Result<SessionStatus, RepositoryError> {
    match value {
        "PREPARING" => Ok(SessionStatus::Preparing),
        "LIVE" => Ok(SessionStatus::Live),
        "RECONNECT_GRACE" => Ok(SessionStatus::ReconnectGrace),
        "ENDED" => Ok(SessionStatus::Ended),
        _ => Err(RepositoryError::InvalidStoredData),
    }
}

fn parse_availability(value: &str) -> Result<Availability, RepositoryError> {
    match value {
        "OFFLINE" => Ok(Availability::Offline),
        "RECONNECTING" => Ok(Availability::Reconnecting),
        "PLAYABLE" => Ok(Availability::Playable),
        _ => Err(RepositoryError::InvalidStoredData),
    }
}

fn persistence_error(_: sqlx::Error) -> RepositoryError {
    RepositoryError::Unavailable
}
