use std::{future::Future, time::Duration};

use serde_json::{Value, json};
use sqlx::{FromRow, PgPool, Postgres, Transaction};
use subtle::ConstantTimeEq;
use time::{Duration as TimeDuration, OffsetDateTime};
use uuid::Uuid;

use crate::{
    application::ports::{
        media_callbacks::{
            AcceptMediaCallbackCommand, ClaimedMediaCallback, MediaCallbackKind,
            MediaCallbackProcessingError, MediaCallbackProcessingRepository, MediaCallbackReceipt,
            MediaCallbackRepository, MediaCallbackRepositoryError, MediaCallbackSessionState,
        },
        media_server::PlaybackEvidence,
        session_clock::SessionMonotonicClock,
        session_deadlines::{SessionDeadlineError, SessionDeadlineRepository},
    },
    domain::{
        ids::SessionId,
        session::{PREPARING_TIMEOUT, RECONNECT_GRACE},
    },
};

const CALLBACK_CLAIM_LEASE: i64 = 15;

pub struct PostgresSessionDeadlineRepository {
    pool: PgPool,
}

impl PostgresSessionDeadlineRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    async fn expire_one(
        &self,
        session_id: String,
        owner_instance_id: &str,
        session_clock: &dyn SessionMonotonicClock,
    ) -> Result<bool, SessionDeadlineError> {
        let mut transaction = self
            .pool
            .begin()
            .await
            .map_err(deadline_persistence_error)?;
        sqlx::query("SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE")
            .execute(&mut *transaction)
            .await
            .map_err(deadline_persistence_error)?;
        let session = sqlx::query_as::<_, ProcessingSessionRow>(
            "SELECT s.session_id, s.stream_id, s.stream_generation, s.source_generation, s.source_claimed, s.status, s.session_version, s.fencing_token, s.owner_instance_id, s.owner_lease_expires_at, s.started_at, s.timeline_position_ms, s.count_version FROM stream_sessions s JOIN stream_configs c ON c.stream_id = s.stream_id WHERE s.session_id = $1 FOR UPDATE OF s",
        )
        .bind(&session_id)
        .fetch_optional(&mut *transaction)
        .await
        .map_err(deadline_persistence_error)?
        .ok_or(SessionDeadlineError::InvalidStoredData)?;
        let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
            .fetch_one(&mut *transaction)
            .await
            .map_err(deadline_persistence_error)?;

        if session.status == "ENDED" {
            transaction
                .commit()
                .await
                .map_err(deadline_persistence_error)?;
            return Ok(false);
        }

        let owner_lease_valid = session
            .owner_lease_expires_at
            .is_some_and(|expires_at| expires_at > now);
        if owner_lease_valid && session.owner_instance_id.as_deref() != Some(owner_instance_id) {
            transaction
                .commit()
                .await
                .map_err(deadline_persistence_error)?;
            return Ok(false);
        }
        let session_id = SessionId::parse(session.session_id.clone())
            .ok_or(SessionDeadlineError::InvalidStoredData)?;
        let deadline_elapsed = if !owner_lease_valid {
            true
        } else {
            match session.status.as_str() {
                "PREPARING" => session_clock
                    .preparing_elapsed(&session_id)
                    .is_none_or(|elapsed| elapsed >= PREPARING_TIMEOUT),
                "RECONNECT_GRACE" => session_clock
                    .reconnect_elapsed(&session_id)
                    .is_none_or(|elapsed| elapsed >= RECONNECT_GRACE),
                "LIVE" => session_clock.timeline_elapsed(&session_id).is_none(),
                _ => return Err(SessionDeadlineError::InvalidStoredData),
            }
        };

        if !deadline_elapsed {
            transaction
                .commit()
                .await
                .map_err(deadline_persistence_error)?;
            return Ok(false);
        }
        let timeline_position_ms =
            timeline_position_ms(&session, session_clock).map_err(deadline_error)?;
        end_session(&mut transaction, &session, timeline_position_ms, now)
            .await
            .map_err(deadline_error)?;
        transaction
            .commit()
            .await
            .map_err(deadline_persistence_error)?;
        if let Some(session_id) = SessionId::parse(session.session_id) {
            session_clock.forget(&session_id);
        }
        Ok(true)
    }
}

impl SessionDeadlineRepository for PostgresSessionDeadlineRepository {
    fn expire_due<'a>(
        &'a self,
        owner_instance_id: String,
        session_clock: &'a dyn SessionMonotonicClock,
    ) -> impl Future<Output = Result<u64, SessionDeadlineError>> + Send + 'a {
        async move {
            let candidates = sqlx::query_as::<_, DeadlineCandidateRow>(
                "SELECT session_id FROM stream_sessions WHERE status <> 'ENDED' AND (owner_instance_id = $1 OR owner_instance_id IS NULL OR owner_lease_expires_at <= clock_timestamp()) ORDER BY session_id",
            )
            .bind(&owner_instance_id)
            .fetch_all(&self.pool)
            .await
            .map_err(deadline_persistence_error)?;
            let mut expired = 0_u64;
            for candidate in candidates {
                if self
                    .expire_one(candidate.session_id, &owner_instance_id, session_clock)
                    .await?
                {
                    expired += 1;
                }
            }
            Ok(expired)
        }
    }
}

pub struct PostgresMediaCallbackRepository {
    pool: PgPool,
}

impl PostgresMediaCallbackRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    async fn accept_in_transaction(
        &self,
        command: AcceptMediaCallbackCommand,
    ) -> Result<MediaCallbackReceipt, MediaCallbackRepositoryError> {
        let mut transaction = self.pool.begin().await.map_err(persistence_error)?;
        sqlx::query("SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE")
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
        sqlx::query("SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE")
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
        let inserted = sqlx::query(
            "INSERT INTO media_callback_inbox (event_id, event_type, stream_id, session_id, stream_generation, source_generation, payload_hash, payload) VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (event_id) DO NOTHING",
        )
        .bind(command.event_id)
        .bind(command.kind.as_db_str())
        .bind(command.stream_id.as_str())
        .bind(command.session_id.as_str())
        .bind(command.stream_generation)
        .bind(command.source_generation)
        .bind(command.payload_hash.as_slice())
        .bind(command.payload)
        .execute(&mut *transaction)
        .await
        .map_err(persistence_error)?
        .rows_affected()
            == 1;

        let existing = sqlx::query_as::<_, CallbackRecord>(
            "SELECT event_type, payload_hash, processing_error_code FROM media_callback_inbox WHERE event_id = $1 FOR UPDATE",
        )
        .bind(command.event_id)
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?
        .ok_or(MediaCallbackRepositoryError::InvalidStoredData)?;
        if !inserted {
            if existing.event_type != command.kind.as_db_str()
                || existing.payload_hash.len() != 32
                || existing
                    .payload_hash
                    .as_slice()
                    .ct_eq(command.payload_hash.as_slice())
                    .unwrap_u8()
                    != 1
            {
                return Err(MediaCallbackRepositoryError::EventIdConflict);
            }
            let receipt = if existing.processing_error_code.as_deref() == Some("STALE_GENERATION") {
                MediaCallbackReceipt::DuplicateStaleGeneration
            } else {
                MediaCallbackReceipt::Duplicate
            };
            transaction.commit().await.map_err(persistence_error)?;
            return Ok(receipt);
        }

        let session = sqlx::query_as::<_, CallbackSession>(
            "SELECT stream_id, stream_generation, source_generation, source_claimed, status FROM stream_sessions WHERE session_id = $1 FOR SHARE",
        )
        .bind(command.session_id.as_str())
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?
        .ok_or(MediaCallbackRepositoryError::SessionMismatch)?;

        if session.stream_id != command.stream_id.as_str() {
            return Err(MediaCallbackRepositoryError::SessionMismatch);
        }
        if session.status == "ENDED" {
            return Err(MediaCallbackRepositoryError::SessionEnded);
        }

        if session.stream_generation < command.stream_generation
            || session.source_generation < command.source_generation
        {
            return Err(MediaCallbackRepositoryError::SessionMismatch);
        }
        if session.stream_generation > command.stream_generation
            || session.source_generation > command.source_generation
            || (!session.source_claimed && command.kind != MediaCallbackKind::SourceLost)
        {
            sqlx::query(
                "UPDATE media_callback_inbox SET processed_at = clock_timestamp(), processing_error_code = 'STALE_GENERATION' WHERE event_id = $1",
            )
            .bind(command.event_id)
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
            transaction.commit().await.map_err(persistence_error)?;
            return Ok(MediaCallbackReceipt::StaleGeneration);
        }

        transaction.commit().await.map_err(persistence_error)?;
        Ok(MediaCallbackReceipt::Accepted)
    }
}

impl MediaCallbackRepository for PostgresMediaCallbackRepository {
    fn accept(
        &self,
        command: AcceptMediaCallbackCommand,
    ) -> impl Future<Output = Result<MediaCallbackReceipt, MediaCallbackRepositoryError>> + Send
    {
        async move { self.accept_in_transaction(command).await }
    }
}

impl MediaCallbackProcessingRepository for PostgresMediaCallbackRepository {
    fn claim_next(
        &self,
        owner_instance_id: String,
    ) -> impl Future<Output = Result<Option<ClaimedMediaCallback>, MediaCallbackProcessingError>> + Send
    {
        async move {
            let row = sqlx::query_as::<_, ClaimedCallbackRow>(
                "WITH candidate AS (SELECT i.event_id FROM media_callback_inbox i JOIN stream_sessions s ON s.session_id = i.session_id WHERE i.processed_at IS NULL AND i.available_at <= clock_timestamp() AND (i.processing_lease_expires_at IS NULL OR i.processing_lease_expires_at <= clock_timestamp()) AND (s.status = 'ENDED' OR s.owner_instance_id = $1 OR s.owner_instance_id IS NULL OR s.owner_lease_expires_at <= clock_timestamp()) AND NOT EXISTS (SELECT 1 FROM media_callback_inbox earlier WHERE earlier.session_id = i.session_id AND earlier.processed_at IS NULL AND (earlier.received_at, earlier.event_id) < (i.received_at, i.event_id)) ORDER BY i.received_at, i.event_id LIMIT 1 FOR UPDATE OF i SKIP LOCKED) UPDATE media_callback_inbox i SET processing_owner_instance_id = $1, processing_lease_expires_at = clock_timestamp() + ($2 * INTERVAL '1 second'), processing_attempts = processing_attempts + 1 FROM candidate c WHERE i.event_id = c.event_id RETURNING i.event_id, i.event_type, i.stream_id, i.session_id, i.stream_generation, i.source_generation, i.payload, i.received_at, i.processing_attempts, (SELECT s.status FROM stream_sessions s WHERE s.session_id = i.session_id) AS session_status, (SELECT s.playback_path FROM stream_sessions s WHERE s.session_id = i.session_id) AS current_playback_path, (SELECT s.media_node_id FROM stream_sessions s WHERE s.session_id = i.session_id) AS media_node_id, (SELECT s.fencing_token FROM stream_sessions s WHERE s.session_id = i.session_id) AS fencing_token",
            )
            .bind(owner_instance_id)
            .bind(CALLBACK_CLAIM_LEASE)
            .fetch_optional(&self.pool)
            .await
            .map_err(processing_persistence_error)?;
            row.map(ClaimedCallbackRow::into_claimed).transpose()
        }
    }

    fn apply<'a>(
        &'a self,
        callback: ClaimedMediaCallback,
        owner_instance_id: String,
        playback_evidence: Option<PlaybackEvidence>,
        session_clock: &'a dyn SessionMonotonicClock,
    ) -> impl Future<Output = Result<(), MediaCallbackProcessingError>> + Send + 'a {
        async move {
            self.apply_in_transaction(
                callback,
                owner_instance_id,
                playback_evidence,
                session_clock,
            )
            .await
        }
    }

    fn retry(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        error_code: &'static str,
        delay: Duration,
    ) -> impl Future<Output = Result<(), MediaCallbackProcessingError>> + Send {
        async move {
            let delay_millis = i64::try_from(delay.as_millis()).unwrap_or(i64::MAX);
            let rows = sqlx::query(
                "UPDATE media_callback_inbox SET available_at = clock_timestamp() + ($3 * INTERVAL '1 millisecond'), processing_owner_instance_id = NULL, processing_lease_expires_at = NULL, processing_error_code = $4 WHERE event_id = $1 AND processing_owner_instance_id = $2 AND processing_lease_expires_at > clock_timestamp() AND processed_at IS NULL",
            )
            .bind(event_id)
            .bind(owner_instance_id)
            .bind(delay_millis)
            .bind(error_code)
            .execute(&self.pool)
            .await
            .map_err(processing_persistence_error)?
            .rows_affected();
            if rows != 1 {
                return Err(MediaCallbackProcessingError::LeaseLost);
            }
            Ok(())
        }
    }

    fn dead_letter(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        reason_code: &'static str,
    ) -> impl Future<Output = Result<(), MediaCallbackProcessingError>> + Send {
        async move {
            let mut transaction = self
                .pool
                .begin()
                .await
                .map_err(processing_persistence_error)?;
            sqlx::query(
                "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
            )
            .execute(&mut *transaction)
            .await
            .map_err(processing_persistence_error)?;
            let event = sqlx::query_as::<_, CallbackDeadLetterRow>(
                "SELECT session_id, event_type, payload, processing_attempts, received_at, processing_owner_instance_id, processing_lease_expires_at, processed_at FROM media_callback_inbox WHERE event_id = $1 FOR UPDATE",
            )
            .bind(event_id)
            .fetch_optional(&mut *transaction)
            .await
            .map_err(processing_persistence_error)?
            .ok_or(MediaCallbackProcessingError::InvalidStoredData)?;
            let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
                .fetch_one(&mut *transaction)
                .await
                .map_err(processing_persistence_error)?;
            if event.processed_at.is_some()
                || event.processing_owner_instance_id.as_deref() != Some(owner_instance_id.as_str())
                || event
                    .processing_lease_expires_at
                    .is_none_or(|expires_at| expires_at <= now)
            {
                return Err(MediaCallbackProcessingError::LeaseLost);
            }
            sqlx::query(
                "INSERT INTO streaming_dead_letters (dead_letter_id, event_id, aggregate_id, event_type, payload, reason_code, attempts, first_failed_at, last_failed_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) ON CONFLICT (event_id) DO UPDATE SET reason_code = EXCLUDED.reason_code, attempts = EXCLUDED.attempts, last_failed_at = EXCLUDED.last_failed_at, closed_at = NULL, closed_by = NULL, resolution_note = NULL",
            )
            .bind(Uuid::now_v7())
            .bind(event_id)
            .bind(format!("session:{}", event.session_id))
            .bind(event.event_type)
            .bind(event.payload)
            .bind(reason_code)
            .bind(event.processing_attempts)
            .bind(event.received_at)
            .bind(now)
            .execute(&mut *transaction)
            .await
            .map_err(processing_persistence_error)?;
            sqlx::query(
                "UPDATE media_callback_inbox SET processed_at = $2, processing_owner_instance_id = NULL, processing_lease_expires_at = NULL, processing_error_code = $3 WHERE event_id = $1",
            )
            .bind(event_id)
            .bind(now)
            .bind(reason_code)
            .execute(&mut *transaction)
            .await
            .map_err(processing_persistence_error)?;
            transaction
                .commit()
                .await
                .map_err(processing_persistence_error)?;
            Ok(())
        }
    }
}

impl PostgresMediaCallbackRepository {
    async fn apply_in_transaction(
        &self,
        callback: ClaimedMediaCallback,
        owner_instance_id: String,
        playback_evidence: Option<PlaybackEvidence>,
        session_clock: &dyn SessionMonotonicClock,
    ) -> Result<(), MediaCallbackProcessingError> {
        let mut transaction = self
            .pool
            .begin()
            .await
            .map_err(processing_persistence_error)?;
        sqlx::query("SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE")
            .execute(&mut *transaction)
            .await
            .map_err(processing_persistence_error)?;
        let claim = sqlx::query_as::<_, CallbackClaimRow>(
            "SELECT processing_owner_instance_id, processing_lease_expires_at FROM media_callback_inbox WHERE event_id = $1 FOR UPDATE",
        )
        .bind(callback.event_id)
        .fetch_optional(&mut *transaction)
        .await
        .map_err(processing_persistence_error)?
        .ok_or(MediaCallbackProcessingError::InvalidStoredData)?;
        let session = sqlx::query_as::<_, ProcessingSessionRow>(
            "SELECT s.session_id, s.stream_id, s.stream_generation, s.source_generation, s.source_claimed, s.status, s.session_version, s.fencing_token, s.owner_instance_id, s.owner_lease_expires_at, s.started_at, s.timeline_position_ms, s.count_version FROM stream_sessions s JOIN stream_configs c ON c.stream_id = s.stream_id WHERE s.session_id = $1 FOR UPDATE OF s",
        )
        .bind(callback.session_id.as_str())
        .fetch_optional(&mut *transaction)
        .await
        .map_err(processing_persistence_error)?
        .ok_or(MediaCallbackProcessingError::InvalidStoredData)?;

        let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
            .fetch_one(&mut *transaction)
            .await
            .map_err(processing_persistence_error)?;
        if claim.processing_owner_instance_id.as_deref() != Some(owner_instance_id.as_str())
            || claim
                .processing_lease_expires_at
                .is_none_or(|expires_at| expires_at <= now)
        {
            return Err(MediaCallbackProcessingError::LeaseLost);
        }

        if session.stream_id != callback.stream_id.as_str()
            || session.stream_generation < callback.stream_generation
            || session.source_generation < callback.source_generation
        {
            return Err(MediaCallbackProcessingError::InvalidStoredData);
        }
        if session.fencing_token != callback.fencing_token {
            return Err(MediaCallbackProcessingError::LeaseLost);
        }
        if session.status == "ENDED" {
            complete_callback(&mut transaction, callback.event_id).await?;
            transaction
                .commit()
                .await
                .map_err(processing_persistence_error)?;
            return Ok(());
        }
        if session.stream_generation > callback.stream_generation
            || session.source_generation > callback.source_generation
            || (!session.source_claimed && callback.kind != MediaCallbackKind::SourceLost)
        {
            complete_callback(&mut transaction, callback.event_id).await?;
            transaction
                .commit()
                .await
                .map_err(processing_persistence_error)?;
            return Ok(());
        }

        let owner_lease_valid = session
            .owner_lease_expires_at
            .is_some_and(|expires_at| expires_at > now);
        if session.owner_instance_id.as_deref() != Some(owner_instance_id.as_str())
            || !owner_lease_valid
        {
            let timeline_position_ms = timeline_position_ms(&session, session_clock)?;
            end_session(&mut transaction, &session, timeline_position_ms, now).await?;
            complete_callback(&mut transaction, callback.event_id).await?;
            transaction
                .commit()
                .await
                .map_err(processing_persistence_error)?;
            session_clock.forget(&callback.session_id);
            return Ok(());
        }

        let status = session_status(&session.status)?;
        let session_id = SessionId::parse(session.session_id.clone())
            .ok_or(MediaCallbackProcessingError::InvalidStoredData)?;
        let timeline_position_ms = timeline_position_ms(&session, session_clock)?;
        let deadline_elapsed = match status {
            MediaCallbackSessionState::Preparing => session_clock
                .preparing_elapsed(&session_id)
                .is_none_or(|elapsed| elapsed >= PREPARING_TIMEOUT),
            MediaCallbackSessionState::ReconnectGrace => session_clock
                .reconnect_elapsed(&session_id)
                .is_none_or(|elapsed| elapsed >= RECONNECT_GRACE),
            MediaCallbackSessionState::Live => {
                session_clock.timeline_elapsed(&session_id).is_none()
            }
            MediaCallbackSessionState::Ended => false,
        };
        if deadline_elapsed {
            end_session(&mut transaction, &session, timeline_position_ms, now).await?;
            complete_callback(&mut transaction, callback.event_id).await?;
            transaction
                .commit()
                .await
                .map_err(processing_persistence_error)?;
            session_clock.forget(&session_id);
            return Ok(());
        }

        let mut forget_session_clock = false;
        match callback.kind {
            MediaCallbackKind::SourceConnected => match status {
                MediaCallbackSessionState::Preparing => {
                    if session_clock
                        .preparing_elapsed(&session_id)
                        .is_none_or(|elapsed| elapsed >= PREPARING_TIMEOUT)
                    {
                        end_session(&mut transaction, &session, timeline_position_ms, now).await?;
                        forget_session_clock = true;
                    }
                }
                MediaCallbackSessionState::ReconnectGrace => {
                    if session_clock
                        .reconnect_elapsed(&session_id)
                        .is_none_or(|elapsed| elapsed >= RECONNECT_GRACE)
                    {
                        end_session(&mut transaction, &session, timeline_position_ms, now).await?;
                        forget_session_clock = true;
                    }
                }
                MediaCallbackSessionState::Live | MediaCallbackSessionState::Ended => {}
            },
            MediaCallbackKind::PlaybackReady => {
                if status != MediaCallbackSessionState::Live {
                    let evidence = playback_evidence
                        .as_ref()
                        .ok_or(MediaCallbackProcessingError::PlaybackNotVerified)?;
                    if !evidence.has_reproducible_segment
                        || callback.playback_path.as_deref()
                            != Some(evidence.manifest_path.as_str())
                    {
                        return Err(MediaCallbackProcessingError::PlaybackNotVerified);
                    }
                }

                match status {
                    MediaCallbackSessionState::Preparing => {
                        if session_clock
                            .preparing_elapsed(&session_id)
                            .is_none_or(|elapsed| elapsed >= PREPARING_TIMEOUT)
                            || !session_clock.mark_live(&session_id)
                        {
                            end_session(&mut transaction, &session, timeline_position_ms, now)
                                .await?;
                            forget_session_clock = true;
                        } else {
                            start_playback(
                                &mut transaction,
                                &session,
                                callback.playback_path.as_deref(),
                                timeline_position_ms,
                                now,
                            )
                            .await?;
                        }
                    }
                    MediaCallbackSessionState::ReconnectGrace => {
                        if session_clock
                            .reconnect_elapsed(&session_id)
                            .is_none_or(|elapsed| elapsed >= RECONNECT_GRACE)
                            || !session_clock.mark_live(&session_id)
                        {
                            end_session(&mut transaction, &session, timeline_position_ms, now)
                                .await?;
                            forget_session_clock = true;
                        } else {
                            start_playback(
                                &mut transaction,
                                &session,
                                callback.playback_path.as_deref(),
                                timeline_position_ms,
                                now,
                            )
                            .await?;
                        }
                    }
                    MediaCallbackSessionState::Live | MediaCallbackSessionState::Ended => {}
                }
            }
            MediaCallbackKind::SourceLost => match status {
                MediaCallbackSessionState::Preparing => {
                    end_session(&mut transaction, &session, timeline_position_ms, now).await?;
                    forget_session_clock = true;
                }
                MediaCallbackSessionState::Live => {
                    let elapsed_before_anchor = elapsed_since(callback.received_at, now)?;
                    if elapsed_before_anchor >= RECONNECT_GRACE
                        || !session_clock.start_reconnect_grace(
                            &session_id,
                            callback.source_generation,
                            elapsed_before_anchor,
                        )
                    {
                        end_session(&mut transaction, &session, timeline_position_ms, now).await?;
                        forget_session_clock = true;
                    } else {
                        enter_reconnect_grace(
                            &mut transaction,
                            &session,
                            callback.received_at,
                            timeline_position_ms,
                            now,
                        )
                        .await?;
                    }
                }
                MediaCallbackSessionState::ReconnectGrace => {
                    if session_clock
                        .reconnect_elapsed(&session_id)
                        .is_none_or(|elapsed| elapsed >= RECONNECT_GRACE)
                    {
                        end_session(&mut transaction, &session, timeline_position_ms, now).await?;
                        forget_session_clock = true;
                    } else {
                        // Release only this generation's publisher reservation. Failed reconnects
                        // keep the original monotonic loss anchor and deadline.
                        sqlx::query("UPDATE stream_sessions SET source_claimed = FALSE WHERE session_id = $1")
                            .bind(session_id.as_str())
                            .execute(&mut *transaction)
                            .await
                            .map_err(processing_persistence_error)?;
                    }
                }
                MediaCallbackSessionState::Ended => {}
            },
        }

        complete_callback(&mut transaction, callback.event_id).await?;
        transaction
            .commit()
            .await
            .map_err(processing_persistence_error)?;
        if forget_session_clock {
            session_clock.forget(&session_id);
        }
        Ok(())
    }
}

#[derive(FromRow)]
struct CallbackRecord {
    event_type: String,
    payload_hash: Vec<u8>,
    processing_error_code: Option<String>,
}

#[derive(FromRow)]
struct CallbackSession {
    stream_id: String,
    stream_generation: i64,
    source_generation: i64,
    source_claimed: bool,
    status: String,
}

#[derive(FromRow)]
struct ClaimedCallbackRow {
    event_id: Uuid,
    event_type: String,
    stream_id: String,
    session_id: String,
    stream_generation: i64,
    source_generation: i64,
    fencing_token: i64,
    payload: Value,
    received_at: OffsetDateTime,
    processing_attempts: i32,
    session_status: String,
    current_playback_path: Option<String>,
    media_node_id: Option<String>,
}

impl ClaimedCallbackRow {
    fn into_claimed(self) -> Result<ClaimedMediaCallback, MediaCallbackProcessingError> {
        let event_type = callback_kind(&self.event_type)?;
        let session_id = SessionId::parse(self.session_id)
            .ok_or(MediaCallbackProcessingError::InvalidStoredData)?;
        let stream_id = crate::domain::ids::StreamId::parse(self.stream_id)
            .ok_or(MediaCallbackProcessingError::InvalidStoredData)?;
        let playback_path = self
            .payload
            .get("playbackPath")
            .and_then(Value::as_str)
            .map(str::to_owned);
        Ok(ClaimedMediaCallback {
            event_id: self.event_id,
            kind: event_type,
            stream_id,
            session_id,
            stream_generation: self.stream_generation,
            source_generation: self.source_generation,
            fencing_token: self.fencing_token,
            playback_path,
            received_at: self.received_at,
            processing_attempts: self.processing_attempts,
            session_state: session_status(&self.session_status)?,
            current_playback_path: self.current_playback_path,
            media_node_id: self.media_node_id,
        })
    }
}

#[derive(FromRow)]
struct CallbackClaimRow {
    processing_owner_instance_id: Option<String>,
    processing_lease_expires_at: Option<OffsetDateTime>,
}

#[derive(FromRow)]
struct CallbackDeadLetterRow {
    session_id: String,
    event_type: String,
    payload: Value,
    processing_attempts: i32,
    received_at: OffsetDateTime,
    processing_owner_instance_id: Option<String>,
    processing_lease_expires_at: Option<OffsetDateTime>,
    processed_at: Option<OffsetDateTime>,
}

#[derive(FromRow)]
struct ProcessingSessionRow {
    session_id: String,
    stream_id: String,
    stream_generation: i64,
    source_generation: i64,
    source_claimed: bool,
    status: String,
    session_version: i64,
    fencing_token: i64,
    owner_instance_id: Option<String>,
    owner_lease_expires_at: Option<OffsetDateTime>,
    started_at: Option<OffsetDateTime>,
    timeline_position_ms: i64,
    count_version: i64,
}

#[derive(FromRow)]
struct DeadlineCandidateRow {
    session_id: String,
}

async fn complete_callback(
    transaction: &mut Transaction<'_, Postgres>,
    event_id: Uuid,
) -> Result<(), MediaCallbackProcessingError> {
    sqlx::query(
        "UPDATE media_callback_inbox SET processed_at = clock_timestamp(), processing_owner_instance_id = NULL, processing_lease_expires_at = NULL, processing_error_code = NULL WHERE event_id = $1",
    )
    .bind(event_id)
    .execute(&mut **transaction)
    .await
    .map_err(processing_persistence_error)?;
    Ok(())
}

async fn start_playback(
    transaction: &mut Transaction<'_, Postgres>,
    session: &ProcessingSessionRow,
    playback_path: Option<&str>,
    timeline_position_ms: i64,
    now: OffsetDateTime,
) -> Result<(), MediaCallbackProcessingError> {
    let playback_path = playback_path.ok_or(MediaCallbackProcessingError::InvalidStoredData)?;
    let first_playback = session.started_at.is_none();
    let session_version = next_version(session.session_version)?;
    let timeline_position_ms = if first_playback {
        0
    } else {
        timeline_position_ms.max(session.timeline_position_ms)
    };
    let viewer_count = sqlx::query_scalar::<_, i64>(
        "SELECT count(*) FROM viewer_leases WHERE session_id = $1 AND closed_at IS NULL AND heartbeat_at > $2 - INTERVAL '30 seconds'",
    ).bind(&session.session_id).bind(now).fetch_one(&mut **transaction).await.map_err(processing_persistence_error)?;
    let count_version = session
        .count_version
        .checked_add(1)
        .filter(|version| *version > 0)
        .ok_or(MediaCallbackProcessingError::InvalidStoredData)?;
    let update = sqlx::query(
        "UPDATE stream_sessions SET status = 'LIVE', availability = 'PLAYABLE', session_version = $2, playback_path = $3, started_at = COALESCE(started_at, $4), timeline_position_ms = $5, timeline_sampled_at = $4, grace_deadline_at = NULL, viewer_count = $6, count_version = $7, viewer_count_observed_at = $4, updated_at = $4 WHERE session_id = $1 AND status IN ('PREPARING', 'RECONNECT_GRACE')",
    )
    .bind(&session.session_id)
    .bind(session_version)
    .bind(playback_path)
    .bind(now)
    .bind(timeline_position_ms)
    .bind(viewer_count)
    .bind(count_version)
    .execute(&mut **transaction)
    .await
    .map_err(processing_persistence_error)?;
    if update.rows_affected() != 1 {
        return Err(MediaCallbackProcessingError::InvalidStoredData);
    }

    append_session_event(
        transaction,
        Uuid::now_v7(),
        &session.session_id,
        session_version,
        if first_playback {
            "StreamSessionStarted"
        } else {
            "StreamSessionAvailabilityChanged"
        },
        json!({
            "sessionId": session.session_id,
            "streamId": session.stream_id,
            "streamGeneration": session.stream_generation,

            "status": "LIVE",
            "availability": "PLAYABLE",
            "sessionVersion": session_version,

        }),
    )
    .await?;

    Ok(())
}

async fn enter_reconnect_grace(
    transaction: &mut Transaction<'_, Postgres>,
    session: &ProcessingSessionRow,
    loss_received_at: OffsetDateTime,
    timeline_position_ms: i64,
    now: OffsetDateTime,
) -> Result<(), MediaCallbackProcessingError> {
    let session_version = next_version(session.session_version)?;
    let timeline_position_ms = timeline_position_ms.max(session.timeline_position_ms);
    let deadline = loss_received_at + TimeDuration::seconds(RECONNECT_GRACE.as_secs() as i64);
    let update = sqlx::query(
        "UPDATE stream_sessions SET status = 'RECONNECT_GRACE', source_claimed = FALSE, availability = 'RECONNECTING', session_version = $2, timeline_position_ms = $3, timeline_sampled_at = $4, grace_deadline_at = $5, updated_at = $4 WHERE session_id = $1 AND status = 'LIVE'",
    )
    .bind(&session.session_id)
    .bind(session_version)
    .bind(timeline_position_ms)
    .bind(now)
    .bind(deadline)
    .execute(&mut **transaction)
    .await
    .map_err(processing_persistence_error)?;
    if update.rows_affected() != 1 {
        return Err(MediaCallbackProcessingError::InvalidStoredData);
    }
    append_session_event(
        transaction,
        Uuid::now_v7(),
        &session.session_id,
        session_version,
        "StreamSessionAvailabilityChanged",
        json!({
            "sessionId": session.session_id,
            "streamId": session.stream_id,
            "streamGeneration": session.stream_generation,

            "status": "LIVE",
            "availability": "RECONNECTING",
            "sessionVersion": session_version,

        }),
    )
    .await?;
    Ok(())
}

async fn end_session(
    transaction: &mut Transaction<'_, Postgres>,
    session: &ProcessingSessionRow,
    timeline_position_ms: i64,
    now: OffsetDateTime,
) -> Result<(), MediaCallbackProcessingError> {
    let session_version = next_version(session.session_version)?;
    let timeline_position_ms = timeline_position_ms.max(session.timeline_position_ms);
    let count_version = session
        .count_version
        .checked_add(1)
        .filter(|version| *version > 0)
        .ok_or(MediaCallbackProcessingError::InvalidStoredData)?;
    let update = sqlx::query(
        "UPDATE stream_sessions SET status = 'ENDED', availability = 'OFFLINE', session_version = $2, timeline_position_ms = $3, timeline_sampled_at = $4, grace_deadline_at = NULL, ended_at = $4, owner_lease_expires_at = NULL, viewer_count = 0, count_version = $5, viewer_count_observed_at = $4, updated_at = $4 WHERE session_id = $1 AND status <> 'ENDED'",
    )
    .bind(&session.session_id)
    .bind(session_version)
    .bind(timeline_position_ms)
    .bind(now)
    .bind(count_version)
    .execute(&mut **transaction)
    .await
    .map_err(processing_persistence_error)?;

    if update.rows_affected() != 1 {
        return Err(MediaCallbackProcessingError::InvalidStoredData);
    }
    sqlx::query(
        "UPDATE viewer_leases SET closed_at = $2 WHERE session_id = $1 AND closed_at IS NULL",
    )
    .bind(&session.session_id)
    .bind(now)
    .execute(&mut **transaction)
    .await
    .map_err(processing_persistence_error)?;
    append_session_event(
        transaction,
        Uuid::now_v7(),
        &session.session_id,
        session_version,
        "StreamSessionEnded",
        json!({
            "sessionId": session.session_id,
            "streamId": session.stream_id,
            "streamGeneration": session.stream_generation,

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
) -> Result<(), MediaCallbackProcessingError> {
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
    .map_err(processing_persistence_error)?;
    Ok(())
}

fn timeline_position_ms(
    session: &ProcessingSessionRow,
    session_clock: &dyn SessionMonotonicClock,
) -> Result<i64, MediaCallbackProcessingError> {
    if session.timeline_position_ms < 0 {
        return Err(MediaCallbackProcessingError::InvalidStoredData);
    }
    let session_id = SessionId::parse(session.session_id.clone())
        .ok_or(MediaCallbackProcessingError::InvalidStoredData)?;
    let Some(elapsed) = session_clock.timeline_elapsed(&session_id) else {
        return Ok(session.timeline_position_ms);
    };
    let elapsed_ms = i64::try_from(elapsed.as_millis())
        .map_err(|_| MediaCallbackProcessingError::InvalidStoredData)?;
    Ok(session.timeline_position_ms.max(elapsed_ms))
}

fn callback_kind(value: &str) -> Result<MediaCallbackKind, MediaCallbackProcessingError> {
    match value {
        "SOURCE_CONNECTED" => Ok(MediaCallbackKind::SourceConnected),
        "PLAYBACK_READY" => Ok(MediaCallbackKind::PlaybackReady),
        "SOURCE_LOST" => Ok(MediaCallbackKind::SourceLost),
        _ => Err(MediaCallbackProcessingError::InvalidStoredData),
    }
}

fn session_status(value: &str) -> Result<MediaCallbackSessionState, MediaCallbackProcessingError> {
    match value {
        "PREPARING" => Ok(MediaCallbackSessionState::Preparing),
        "LIVE" => Ok(MediaCallbackSessionState::Live),
        "RECONNECT_GRACE" => Ok(MediaCallbackSessionState::ReconnectGrace),
        "ENDED" => Ok(MediaCallbackSessionState::Ended),
        _ => Err(MediaCallbackProcessingError::InvalidStoredData),
    }
}

fn next_version(version: i64) -> Result<i64, MediaCallbackProcessingError> {
    version
        .checked_add(1)
        .filter(|next| *next > 0)
        .ok_or(MediaCallbackProcessingError::InvalidStoredData)
}

fn elapsed_since(
    start: OffsetDateTime,
    end: OffsetDateTime,
) -> Result<Duration, MediaCallbackProcessingError> {
    let nanos = (end - start).whole_nanoseconds();
    if nanos < 0 {
        return Err(MediaCallbackProcessingError::InvalidStoredData);
    }
    let nanos =
        u64::try_from(nanos).map_err(|_| MediaCallbackProcessingError::InvalidStoredData)?;
    Ok(Duration::from_nanos(nanos))
}

fn persistence_error(_: sqlx::Error) -> MediaCallbackRepositoryError {
    MediaCallbackRepositoryError::Unavailable
}

fn processing_persistence_error(_: sqlx::Error) -> MediaCallbackProcessingError {
    MediaCallbackProcessingError::Unavailable
}

fn deadline_persistence_error(_: sqlx::Error) -> SessionDeadlineError {
    SessionDeadlineError::Unavailable
}

fn deadline_error(error: MediaCallbackProcessingError) -> SessionDeadlineError {
    match error {
        MediaCallbackProcessingError::LeaseLost
        | MediaCallbackProcessingError::InvalidStoredData
        | MediaCallbackProcessingError::PlaybackNotVerified => {
            SessionDeadlineError::InvalidStoredData
        }
        MediaCallbackProcessingError::Unavailable => SessionDeadlineError::Unavailable,
    }
}
