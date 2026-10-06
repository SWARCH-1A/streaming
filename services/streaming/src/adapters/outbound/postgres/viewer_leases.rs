use std::future::Future;

use sqlx::{FromRow, PgPool};
use subtle::ConstantTimeEq;
use time::{Duration, OffsetDateTime};
use uuid::Uuid;

use crate::application::ports::viewer_leases::{
    ViewerLeaseReceipt, ViewerLeaseRepository, ViewerLeaseRepositoryError,
};
use crate::domain::ids::SessionId;

const VIEWER_LEASE_TTL: Duration = Duration::seconds(30);

pub struct PostgresViewerLeaseRepository {
    pool: PgPool,
}

impl PostgresViewerLeaseRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

impl ViewerLeaseRepository for PostgresViewerLeaseRepository {
    fn create(
        &self,
        session_id: SessionId,
        idempotency_key: Uuid,
        lease_id: String,
        token_hash: [u8; 32],
    ) -> impl Future<Output = Result<ViewerLeaseReceipt, ViewerLeaseRepositoryError>> + Send {
        async move {
            let mut transaction = self.pool.begin().await.map_err(unavailable)?;
            sqlx::query(
                "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
            )
            .execute(&mut *transaction)
            .await
            .map_err(unavailable)?;
            let session = sqlx::query_as::<_, SessionStatusRow>(
                "SELECT status, availability FROM stream_sessions WHERE session_id = $1 FOR UPDATE",
            )
            .bind(session_id.as_str())
            .fetch_optional(&mut *transaction)
            .await
            .map_err(unavailable)?
            .ok_or(ViewerLeaseRepositoryError::NotFound)?;
            ensure_playable(&session)?;

            let now = database_now(&mut transaction).await?;
            let existing = sqlx::query_as::<_, ViewerLeaseRow>(
                "SELECT lease_id, session_id, token_hash, heartbeat_at, closed_at FROM viewer_leases WHERE session_id = $1 AND idempotency_key = $2",
            )
            .bind(session_id.as_str())
            .bind(idempotency_key)
            .fetch_optional(&mut *transaction)
            .await
            .map_err(unavailable)?;

            if let Some(existing) = existing {
                if existing.lease_id != lease_id
                    || !hash_matches(&existing.token_hash, &token_hash)?
                {
                    return Err(ViewerLeaseRepositoryError::IdempotencyConflict);
                }

                if existing.closed_at.is_some() || existing.heartbeat_at <= now - VIEWER_LEASE_TTL {
                    return Err(ViewerLeaseRepositoryError::Expired);
                }
                let expires_at = existing.heartbeat_at + VIEWER_LEASE_TTL;
                transaction.commit().await.map_err(unavailable)?;
                return Ok(ViewerLeaseReceipt {
                    created: false,
                    session_id: existing.session_id,
                    expires_at,
                });
            }

            sqlx::query(
                "INSERT INTO viewer_leases (lease_id, session_id, idempotency_key, token_hash, heartbeat_at, created_at) VALUES ($1, $2, $3, $4, $5, $5)",
            )
            .bind(&lease_id)
            .bind(session_id.as_str())
            .bind(idempotency_key)
            .bind(token_hash.as_slice())
            .bind(now)
            .execute(&mut *transaction)
            .await
            .map_err(unavailable)?;

            transaction.commit().await.map_err(unavailable)?;
            Ok(ViewerLeaseReceipt {
                created: true,
                session_id: session_id.as_str().to_owned(),
                expires_at: now + VIEWER_LEASE_TTL,
            })
        }
    }

    fn heartbeat(
        &self,
        lease_id: String,
        token_hash: [u8; 32],
    ) -> impl Future<Output = Result<ViewerLeaseReceipt, ViewerLeaseRepositoryError>> + Send {
        async move {
            let mut transaction = self.pool.begin().await.map_err(unavailable)?;
            sqlx::query(
                "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
            )
            .execute(&mut *transaction)
            .await
            .map_err(unavailable)?;
            let session_id = find_lease_session(&mut transaction, &lease_id).await?;
            let session = sqlx::query_as::<_, SessionOnlyRow>(
                "SELECT status FROM stream_sessions WHERE session_id = $1 FOR UPDATE",
            )
            .bind(&session_id)
            .fetch_optional(&mut *transaction)
            .await
            .map_err(unavailable)?
            .ok_or(ViewerLeaseRepositoryError::NotFound)?;
            if session.status == "ENDED" {
                return Err(ViewerLeaseRepositoryError::SessionEnded);
            }

            let lease = sqlx::query_as::<_, ViewerLeaseHeartbeatRow>(
                "SELECT token_hash, heartbeat_at, closed_at FROM viewer_leases WHERE lease_id = $1 AND session_id = $2 FOR UPDATE",
            )
            .bind(&lease_id)
            .bind(&session_id)
            .fetch_optional(&mut *transaction)
            .await
            .map_err(unavailable)?
            .ok_or(ViewerLeaseRepositoryError::NotFound)?;
            if !hash_matches(&lease.token_hash, &token_hash)? {
                return Err(ViewerLeaseRepositoryError::InvalidToken);
            }
            let now = database_now(&mut transaction).await?;
            if lease.closed_at.is_some() || lease.heartbeat_at <= now - VIEWER_LEASE_TTL {
                return Err(ViewerLeaseRepositoryError::Expired);
            }
            sqlx::query("UPDATE viewer_leases SET heartbeat_at = $2 WHERE lease_id = $1")
                .bind(&lease_id)
                .bind(now)
                .execute(&mut *transaction)
                .await
                .map_err(unavailable)?;

            transaction.commit().await.map_err(unavailable)?;
            Ok(ViewerLeaseReceipt {
                created: false,
                session_id,
                expires_at: now + VIEWER_LEASE_TTL,
            })
        }
    }

    fn close(
        &self,
        lease_id: String,
        token_hash: [u8; 32],
    ) -> impl Future<Output = Result<(), ViewerLeaseRepositoryError>> + Send {
        async move {
            let mut transaction = self.pool.begin().await.map_err(unavailable)?;
            sqlx::query(
                "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
            )
            .execute(&mut *transaction)
            .await
            .map_err(unavailable)?;
            let session_id = find_lease_session(&mut transaction, &lease_id).await?;
            let _session = sqlx::query_as::<_, SessionOnlyRow>(
                "SELECT status FROM stream_sessions WHERE session_id = $1 FOR UPDATE",
            )
            .bind(&session_id)
            .fetch_optional(&mut *transaction)
            .await
            .map_err(unavailable)?
            .ok_or(ViewerLeaseRepositoryError::NotFound)?;
            let lease = sqlx::query_as::<_, ViewerLeaseHeartbeatRow>(
                "SELECT token_hash, heartbeat_at, closed_at FROM viewer_leases WHERE lease_id = $1 AND session_id = $2 FOR UPDATE",
            )
            .bind(&lease_id)
            .bind(&session_id)
            .fetch_optional(&mut *transaction)
            .await
            .map_err(unavailable)?
            .ok_or(ViewerLeaseRepositoryError::NotFound)?;
            if !hash_matches(&lease.token_hash, &token_hash)? {
                return Err(ViewerLeaseRepositoryError::InvalidToken);
            }
            if lease.closed_at.is_none() {
                let now = database_now(&mut transaction).await?;
                sqlx::query("UPDATE viewer_leases SET closed_at = $2 WHERE lease_id = $1")
                    .bind(&lease_id)
                    .bind(now)
                    .execute(&mut *transaction)
                    .await
                    .map_err(unavailable)?;
            }
            transaction.commit().await.map_err(unavailable)
        }
    }
}

async fn find_lease_session(
    transaction: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    lease_id: &str,
) -> Result<String, ViewerLeaseRepositoryError> {
    sqlx::query_scalar::<_, String>("SELECT session_id FROM viewer_leases WHERE lease_id = $1")
        .bind(lease_id)
        .fetch_optional(&mut **transaction)
        .await
        .map_err(unavailable)?
        .ok_or(ViewerLeaseRepositoryError::NotFound)
}

async fn database_now(
    transaction: &mut sqlx::Transaction<'_, sqlx::Postgres>,
) -> Result<OffsetDateTime, ViewerLeaseRepositoryError> {
    sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
        .fetch_one(&mut **transaction)
        .await
        .map_err(unavailable)
}

fn ensure_playable(session: &SessionStatusRow) -> Result<(), ViewerLeaseRepositoryError> {
    match session.status.as_str() {
        "ENDED" => Err(ViewerLeaseRepositoryError::SessionEnded),
        "LIVE" if session.availability == "PLAYABLE" => Ok(()),
        _ => Err(ViewerLeaseRepositoryError::SessionNotPlayable),
    }
}

fn hash_matches(stored: &[u8], supplied: &[u8; 32]) -> Result<bool, ViewerLeaseRepositoryError> {
    let stored: [u8; 32] = stored
        .try_into()
        .map_err(|_| ViewerLeaseRepositoryError::InvalidStoredData)?;
    Ok(bool::from(stored.ct_eq(supplied)))
}

fn unavailable(_: sqlx::Error) -> ViewerLeaseRepositoryError {
    ViewerLeaseRepositoryError::Unavailable
}

#[derive(FromRow)]
struct SessionStatusRow {
    status: String,
    availability: String,
}

#[derive(FromRow)]
struct SessionOnlyRow {
    status: String,
}

#[derive(FromRow)]
struct ViewerLeaseRow {
    closed_at: Option<OffsetDateTime>,
    lease_id: String,
    session_id: String,
    token_hash: Vec<u8>,
    heartbeat_at: OffsetDateTime,
}

#[derive(FromRow)]
struct ViewerLeaseHeartbeatRow {
    token_hash: Vec<u8>,
    heartbeat_at: OffsetDateTime,
    closed_at: Option<OffsetDateTime>,
}
