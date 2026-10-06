use std::future::Future;

use serde_json::Value;
use sqlx::{FromRow, PgPool, Postgres, Transaction};
use time::OffsetDateTime;
use uuid::Uuid;

use crate::application::ports::media_dead_letters::{
    MediaDeadLetter, MediaDeadLetterRepository, MediaDeadLetterRepositoryError,
};

pub struct PostgresMediaDeadLetterRepository {
    pool: PgPool,
}

impl PostgresMediaDeadLetterRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

impl MediaDeadLetterRepository for PostgresMediaDeadLetterRepository {
    fn list_open(
        &self,
        limit: i64,
    ) -> impl Future<Output = Result<Vec<MediaDeadLetter>, MediaDeadLetterRepositoryError>> + Send
    {
        async move {
            let rows = sqlx::query_as::<_, DeadLetterRow>(
                "SELECT dead_letter_id, event_id, aggregate_id, event_type, payload, reason_code, attempts, first_failed_at, last_failed_at FROM streaming_dead_letters WHERE closed_at IS NULL ORDER BY first_failed_at, event_id LIMIT $1",
            )
            .bind(limit)
            .fetch_all(&self.pool)
            .await
            .map_err(unavailable)?;
            Ok(rows.into_iter().map(DeadLetterRow::into_domain).collect())
        }
    }

    fn redrive(
        &self,
        event_id: Uuid,
        operator_id: String,
        resolution_note: String,
    ) -> impl Future<Output = Result<(), MediaDeadLetterRepositoryError>> + Send {
        async move {
            let mut transaction = self.pool.begin().await.map_err(unavailable)?;
            let inbox = lock_inbox(&mut transaction, event_id)
                .await?
                .ok_or(MediaDeadLetterRepositoryError::InvalidStoredData)?;
            if !inbox.is_completed() {
                return Err(MediaDeadLetterRepositoryError::NotActionable);
            }
            let dead_letter = lock_open_dead_letter(&mut transaction, event_id).await?;
            if inbox.event_type != dead_letter.event_type || inbox.payload != dead_letter.payload {
                return Err(MediaDeadLetterRepositoryError::InvalidStoredData);
            }
            let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
                .fetch_one(&mut *transaction)
                .await
                .map_err(unavailable)?;

            let updated = sqlx::query(
                "UPDATE media_callback_inbox SET available_at = $2, processing_attempts = 0, processing_owner_instance_id = NULL, processing_lease_expires_at = NULL, processed_at = NULL, processing_error_code = NULL WHERE event_id = $1 AND processed_at IS NOT NULL",
            )
            .bind(event_id)
            .bind(now)
            .execute(&mut *transaction)
            .await
            .map_err(unavailable)?
            .rows_affected();
            if updated != 1 {
                return Err(MediaDeadLetterRepositoryError::NotActionable);
            }

            close_dead_letter(
                &mut transaction,
                dead_letter.dead_letter_id,
                event_id,
                &operator_id,
                &resolution_note,
                now,
            )
            .await?;
            record_operation(
                &mut transaction,
                dead_letter.dead_letter_id,
                event_id,
                "REDRIVE",
                &operator_id,
                &resolution_note,
                now,
            )
            .await?;
            transaction.commit().await.map_err(unavailable)?;
            Ok(())
        }
    }

    fn close(
        &self,
        event_id: Uuid,
        operator_id: String,
        resolution_note: String,
    ) -> impl Future<Output = Result<(), MediaDeadLetterRepositoryError>> + Send {
        async move {
            let mut transaction = self.pool.begin().await.map_err(unavailable)?;
            if lock_inbox(&mut transaction, event_id)
                .await?
                .is_some_and(|state| !state.is_completed())
            {
                return Err(MediaDeadLetterRepositoryError::NotActionable);
            }
            let dead_letter = lock_open_dead_letter(&mut transaction, event_id).await?;
            let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
                .fetch_one(&mut *transaction)
                .await
                .map_err(unavailable)?;
            close_dead_letter(
                &mut transaction,
                dead_letter.dead_letter_id,
                event_id,
                &operator_id,
                &resolution_note,
                now,
            )
            .await?;
            record_operation(
                &mut transaction,
                dead_letter.dead_letter_id,
                event_id,
                "CLOSE",
                &operator_id,
                &resolution_note,
                now,
            )
            .await?;
            transaction.commit().await.map_err(unavailable)?;
            Ok(())
        }
    }
}

#[derive(FromRow)]
struct DeadLetterRow {
    dead_letter_id: Uuid,
    event_id: Uuid,
    aggregate_id: String,
    event_type: String,
    payload: Value,
    reason_code: String,
    attempts: i32,
    first_failed_at: OffsetDateTime,
    last_failed_at: OffsetDateTime,
}

impl DeadLetterRow {
    fn into_domain(self) -> MediaDeadLetter {
        MediaDeadLetter {
            dead_letter_id: self.dead_letter_id,
            event_id: self.event_id,
            aggregate_id: self.aggregate_id,
            event_type: self.event_type,
            payload: self.payload,
            reason_code: self.reason_code,
            attempts: self.attempts,
            first_failed_at: self.first_failed_at,
            last_failed_at: self.last_failed_at,
        }
    }
}

#[derive(FromRow)]
struct CallbackInboxState {
    event_type: String,
    payload: Value,
    processed_at: Option<OffsetDateTime>,
    processing_owner_instance_id: Option<String>,
    processing_lease_expires_at: Option<OffsetDateTime>,
}

impl CallbackInboxState {
    fn is_completed(&self) -> bool {
        self.processed_at.is_some()
            && self.processing_owner_instance_id.is_none()
            && self.processing_lease_expires_at.is_none()
    }
}

#[derive(FromRow)]
struct OpenDeadLetter {
    dead_letter_id: Uuid,
    closed_at: Option<OffsetDateTime>,
    event_type: String,
    payload: Value,
}

async fn lock_inbox(
    transaction: &mut Transaction<'_, Postgres>,
    event_id: Uuid,
) -> Result<Option<CallbackInboxState>, MediaDeadLetterRepositoryError> {
    sqlx::query_as::<_, CallbackInboxState>(
        "SELECT event_type, payload, processed_at, processing_owner_instance_id, processing_lease_expires_at FROM media_callback_inbox WHERE event_id = $1 FOR UPDATE",
    )
    .bind(event_id)
    .fetch_optional(&mut **transaction)
    .await
    .map_err(unavailable)
}

async fn lock_open_dead_letter(
    transaction: &mut Transaction<'_, Postgres>,
    event_id: Uuid,
) -> Result<OpenDeadLetter, MediaDeadLetterRepositoryError> {
    let record = sqlx::query_as::<_, OpenDeadLetter>(
        "SELECT dead_letter_id, closed_at, event_type, payload FROM streaming_dead_letters WHERE event_id = $1 FOR UPDATE",
    )
    .bind(event_id)
    .fetch_optional(&mut **transaction)
    .await
    .map_err(unavailable)?
    .ok_or(MediaDeadLetterRepositoryError::NotFound)?;

    if record.closed_at.is_some() {
        return Err(MediaDeadLetterRepositoryError::AlreadyClosed);
    }
    Ok(record)
}

async fn close_dead_letter(
    transaction: &mut Transaction<'_, Postgres>,
    dead_letter_id: Uuid,
    event_id: Uuid,
    operator_id: &str,
    resolution_note: &str,
    now: OffsetDateTime,
) -> Result<(), MediaDeadLetterRepositoryError> {
    let updated = sqlx::query(
        "UPDATE streaming_dead_letters SET closed_at = $3, closed_by = $4, resolution_note = $5 WHERE dead_letter_id = $1 AND event_id = $2 AND closed_at IS NULL",
    )
    .bind(dead_letter_id)
    .bind(event_id)
    .bind(now)
    .bind(operator_id)
    .bind(resolution_note)
    .execute(&mut **transaction)
    .await
    .map_err(unavailable)?
    .rows_affected();
    if updated != 1 {
        return Err(MediaDeadLetterRepositoryError::AlreadyClosed);
    }
    Ok(())
}

async fn record_operation(
    transaction: &mut Transaction<'_, Postgres>,
    dead_letter_id: Uuid,
    event_id: Uuid,
    action: &str,
    operator_id: &str,
    resolution_note: &str,
    occurred_at: OffsetDateTime,
) -> Result<(), MediaDeadLetterRepositoryError> {
    sqlx::query(
        "INSERT INTO streaming_dead_letter_operations (dead_letter_id, event_id, action, operator_id, resolution_note, occurred_at) VALUES ($1, $2, $3, $4, $5, $6)",
    )
    .bind(dead_letter_id)
    .bind(event_id)
    .bind(action)
    .bind(operator_id)
    .bind(resolution_note)
    .bind(occurred_at)
    .execute(&mut **transaction)
    .await
    .map_err(unavailable)?;
    Ok(())
}

fn unavailable(_: sqlx::Error) -> MediaDeadLetterRepositoryError {
    MediaDeadLetterRepositoryError::Unavailable
}
