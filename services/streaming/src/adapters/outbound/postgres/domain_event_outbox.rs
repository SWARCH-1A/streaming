use std::{future::Future, time::Duration};

use sqlx::{FromRow, PgPool};
use time::OffsetDateTime;
use uuid::Uuid;

use crate::application::ports::domain_event_outbox::{
    ClaimedDomainEvent, DomainEventEnvelope, DomainEventOutboxError, DomainEventOutboxRepository,
};

pub struct PostgresDomainEventOutboxRepository {
    pool: PgPool,
    consumer: &'static str,
}

impl PostgresDomainEventOutboxRepository {
    pub fn new(pool: PgPool) -> Self {
        Self {
            pool,
            consumer: "chat",
        }
    }
    pub fn for_discovery(pool: PgPool) -> Self {
        Self {
            pool,
            consumer: "discovery",
        }
    }
}

impl DomainEventOutboxRepository for PostgresDomainEventOutboxRepository {
    fn claim_next(
        &self,
        owner_instance_id: String,
        lease_duration: Duration,
    ) -> impl Future<Output = Result<Option<ClaimedDomainEvent>, DomainEventOutboxError>> + Send
    {
        async move {
            if owner_instance_id.trim().is_empty() {
                return Err(DomainEventOutboxError::InvalidClaim);
            }
            let lease_millis = i64::try_from(lease_duration.as_millis())
                .ok()
                .filter(|value| *value > 0)
                .ok_or(DomainEventOutboxError::InvalidClaim)?;
            let claim_token = Uuid::now_v7();
            let row = sqlx::query_as::<_, DomainEventOutboxRow>(
                "WITH candidate AS (SELECT pending.event_id FROM streaming_outbox AS pending WHERE pending.consumer=$4 AND pending.dead_letter_at IS NULL AND pending.published_at IS NULL AND pending.available_at <= clock_timestamp() AND (pending.claim_expires_at IS NULL OR pending.claim_expires_at <= clock_timestamp()) AND NOT EXISTS (SELECT 1 FROM streaming_outbox AS earlier WHERE earlier.aggregate_id = pending.aggregate_id AND earlier.sequence < pending.sequence AND earlier.consumer=pending.consumer AND earlier.dead_letter_at IS NULL AND earlier.published_at IS NULL) ORDER BY pending.created_at, pending.aggregate_id, pending.sequence FOR UPDATE OF pending SKIP LOCKED LIMIT 1) UPDATE streaming_outbox AS event SET claim_owner = $1, claim_token = $2, claim_expires_at = clock_timestamp() + ($3 * INTERVAL '1 millisecond'), attempts = event.attempts + 1,first_attempt_at=COALESCE(event.first_attempt_at,clock_timestamp()) FROM candidate WHERE event.event_id = candidate.event_id RETURNING event.event_id, event.aggregate_id, event.sequence, event.event_type, event.schema_version, event.payload, event.created_at, event.attempts, (EXTRACT(EPOCH FROM clock_timestamp()-event.first_attempt_at)*1000)::BIGINT AS retry_age_ms, (EXTRACT(EPOCH FROM clock_timestamp()-event.created_at)*1000)::BIGINT AS queue_age_ms, event.alerted_at IS NOT NULL AS alerted",
            )
            .bind(owner_instance_id)
            .bind(claim_token)
            .bind(lease_millis)
            .bind(self.consumer)
            .fetch_optional(&self.pool)
            .await
            .map_err(unavailable)?;
            row.map(|row| into_claimed_event(row, claim_token))
                .transpose()
        }
    }

    fn acknowledge(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        claim_token: Uuid,
    ) -> impl Future<Output = Result<(), DomainEventOutboxError>> + Send {
        async move {
            let mut transaction = self.pool.begin().await.map_err(unavailable)?;
            lock_current_claim(&mut transaction, event_id, &owner_instance_id, claim_token).await?;
            let result = sqlx::query(
                "UPDATE streaming_outbox SET published_at = clock_timestamp(), claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL, last_error_code = NULL WHERE event_id = $1 AND published_at IS NULL AND claim_owner = $2 AND claim_token = $3 AND claim_expires_at > clock_timestamp()",
            )
            .bind(event_id)
            .bind(owner_instance_id)
            .bind(claim_token)
            .execute(&mut *transaction)
            .await
            .map_err(unavailable)?;
            ensure_claim_updated(result.rows_affected())?;
            transaction.commit().await.map_err(unavailable)
        }
    }

    fn alert_once(
        &self,
        event_id: Uuid,
        owner: String,
        token: Uuid,
    ) -> impl Future<Output = Result<bool, DomainEventOutboxError>> + Send {
        async move {
            let result = sqlx::query("UPDATE streaming_outbox o SET alerted_at=clock_timestamp() WHERE event_id=$1 AND claim_owner=$2 AND claim_token=$3 AND claim_expires_at>clock_timestamp() AND alerted_at IS NULL AND NOT EXISTS(SELECT 1 FROM streaming_outbox p WHERE p.aggregate_id=o.aggregate_id AND p.consumer=o.consumer AND p.published_at IS NULL AND p.dead_letter_at IS NULL AND p.alerted_at IS NOT NULL)")
                .bind(event_id).bind(owner).bind(token).execute(&self.pool).await.map_err(unavailable)?;
            Ok(result.rows_affected() == 1)
        }
    }
    fn dead_letter(
        &self,
        event_id: Uuid,
        owner: String,
        token: Uuid,
        reason: &'static str,
    ) -> impl Future<Output = Result<(), DomainEventOutboxError>> + Send {
        async move {
            let mut tx = self.pool.begin().await.map_err(unavailable)?;
            lock_current_claim(&mut tx, event_id, &owner, token).await?;
            let result = sqlx::query("UPDATE streaming_outbox SET dead_letter_at=clock_timestamp(),last_error_code=$4,claim_owner=NULL,claim_token=NULL,claim_expires_at=NULL WHERE event_id=$1 AND claim_owner=$2 AND claim_token=$3 AND claim_expires_at>clock_timestamp()")
                .bind(event_id).bind(owner).bind(token).bind(reason).execute(&mut *tx).await.map_err(unavailable)?;
            ensure_claim_updated(result.rows_affected())?;
            tx.commit().await.map_err(unavailable)
        }
    }

    fn schedule_retry(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        claim_token: Uuid,
        error_code: &'static str,
        delay: Duration,
    ) -> impl Future<Output = Result<(), DomainEventOutboxError>> + Send {
        async move {
            let delay_millis = i64::try_from(delay.as_millis())
                .ok()
                .filter(|value| *value >= 0)
                .ok_or(DomainEventOutboxError::InvalidClaim)?;
            let mut transaction = self.pool.begin().await.map_err(unavailable)?;
            lock_current_claim(&mut transaction, event_id, &owner_instance_id, claim_token).await?;
            let result = sqlx::query(
                "UPDATE streaming_outbox SET available_at = LEAST(clock_timestamp() + ($4 * INTERVAL '1 millisecond'),first_attempt_at+INTERVAL '15 minutes'), last_error_code = $5, claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL WHERE event_id = $1 AND published_at IS NULL AND claim_owner = $2 AND claim_token = $3 AND claim_expires_at > clock_timestamp()",
            )
            .bind(event_id)
            .bind(owner_instance_id)
            .bind(claim_token)
            .bind(delay_millis)
            .bind(error_code)
            .execute(&mut *transaction)
            .await
            .map_err(unavailable)?;
            ensure_claim_updated(result.rows_affected())?;
            transaction.commit().await.map_err(unavailable)
        }
    }
}

async fn lock_current_claim(
    transaction: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    event_id: Uuid,
    owner: &str,
    token: Uuid,
) -> Result<(), DomainEventOutboxError> {
    let claim = sqlx::query_as::<_, (Option<String>, Option<Uuid>, Option<OffsetDateTime>, Option<OffsetDateTime>)>(
        "SELECT claim_owner, claim_token, claim_expires_at, published_at FROM streaming_outbox WHERE event_id = $1 FOR UPDATE",
    ).bind(event_id).fetch_optional(&mut **transaction).await.map_err(unavailable)?.ok_or(DomainEventOutboxError::LeaseLost)?;
    let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
        .fetch_one(&mut **transaction)
        .await
        .map_err(unavailable)?;
    if claim.0.as_deref() != Some(owner)
        || claim.1 != Some(token)
        || claim.2.is_none_or(|expiry| expiry <= now)
        || claim.3.is_some()
    {
        return Err(DomainEventOutboxError::LeaseLost);
    }
    Ok(())
}

fn into_claimed_event(
    row: DomainEventOutboxRow,
    claim_token: Uuid,
) -> Result<ClaimedDomainEvent, DomainEventOutboxError> {
    if row.aggregate_id.trim().is_empty()
        || row.sequence <= 0
        || row.event_type.trim().is_empty()
        || row.schema_version <= 0
        || row.attempts <= 0
    {
        return Err(DomainEventOutboxError::InvalidStoredEvent);
    }
    let occurred_at_utc = row
        .created_at
        .format(&time::format_description::well_known::Rfc3339)
        .map_err(|_| DomainEventOutboxError::InvalidStoredEvent)?;
    Ok(ClaimedDomainEvent {
        envelope: DomainEventEnvelope {
            event_id: row.event_id,
            event_type: row.event_type,
            schema_version: row.schema_version,
            aggregate_id: row.aggregate_id,
            sequence: row.sequence,
            occurred_at_utc,
            producer: "streaming",
            payload: row.payload,
        },
        claim_token,
        attempts: row.attempts,
        retry_age_ms: row.retry_age_ms,
        queue_age_ms: row.queue_age_ms,
        alerted: row.alerted,
    })
}

fn ensure_claim_updated(rows_affected: u64) -> Result<(), DomainEventOutboxError> {
    if rows_affected == 1 {
        Ok(())
    } else {
        Err(DomainEventOutboxError::LeaseLost)
    }
}

fn unavailable(_: sqlx::Error) -> DomainEventOutboxError {
    DomainEventOutboxError::Unavailable
}

#[derive(FromRow)]
struct DomainEventOutboxRow {
    event_id: Uuid,
    aggregate_id: String,
    sequence: i64,
    event_type: String,
    schema_version: i32,
    payload: serde_json::Value,
    created_at: OffsetDateTime,
    attempts: i32,
    retry_age_ms: i64,
    queue_age_ms: i64,
    alerted: bool,
}
