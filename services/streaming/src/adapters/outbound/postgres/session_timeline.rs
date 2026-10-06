use std::future::Future;

use sqlx::{FromRow, PgPool};
use time::OffsetDateTime;

use crate::application::ports::{
    session_clock::SessionMonotonicClock,
    session_timeline::{SessionTimelineError, SessionTimelineRepository},
};

pub struct PostgresSessionTimelineRepository {
    pool: PgPool,
}

impl PostgresSessionTimelineRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

impl SessionTimelineRepository for PostgresSessionTimelineRepository {
    fn checkpoint_due_samples<'a>(
        &'a self,
        owner_instance_id: String,
        session_clock: &'a dyn SessionMonotonicClock,
    ) -> impl Future<Output = Result<u64, SessionTimelineError>> + Send + 'a {
        async move {
            let mut transaction = self.pool.begin().await.map_err(unavailable)?;
            sqlx::query(
                "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
            )
            .execute(&mut *transaction)
            .await
            .map_err(unavailable)?;
            let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
                .fetch_one(&mut *transaction)
                .await
                .map_err(unavailable)?;
            let sessions = sqlx::query_as::<_, TimelineSessionRow>(
                "SELECT session_id, session_version, timeline_position_ms, timeline_sampled_at FROM stream_sessions WHERE status IN ('LIVE', 'RECONNECT_GRACE') AND owner_instance_id = $1 AND owner_lease_expires_at > $2 ORDER BY session_id FOR UPDATE SKIP LOCKED",
            )
            .bind(&owner_instance_id)
            .bind(now)
            .fetch_all(&mut *transaction)
            .await
            .map_err(unavailable)?;

            let mut checkpointed = 0_u64;
            for session in sessions {
                if session.timeline_position_ms < 0 || session.session_version < 1 {
                    return Err(SessionTimelineError::InvalidStoredData);
                }
                let Some(sampled_at) = session.timeline_sampled_at else {
                    // The deadline worker closes active sessions whose monotonic anchor is missing.
                    continue;
                };
                if sampled_at > now {
                    return Err(SessionTimelineError::InvalidStoredData);
                }
                if sampled_at > now - time::Duration::seconds(1) {
                    continue;
                }

                let session_id = crate::domain::ids::SessionId::parse(session.session_id.clone())
                    .ok_or(SessionTimelineError::InvalidStoredData)?;
                let Some(elapsed) = session_clock.timeline_elapsed(&session_id) else {
                    continue;
                };
                let elapsed_ms = i64::try_from(elapsed.as_millis())
                    .map_err(|_| SessionTimelineError::InvalidStoredData)?;
                let timeline_position_ms = session.timeline_position_ms.max(elapsed_ms);
                let updated = sqlx::query(
                    "UPDATE stream_sessions SET timeline_position_ms = $2, timeline_sampled_at = $3, updated_at = $3 WHERE session_id = $1 AND owner_instance_id = $4 AND owner_lease_expires_at > clock_timestamp() AND status IN ('LIVE', 'RECONNECT_GRACE') AND session_version = $5",
                )
                .bind(&session.session_id)
                .bind(timeline_position_ms)
                .bind(now)
                .bind(&owner_instance_id)
                .bind(session.session_version)
                .execute(&mut *transaction)
                .await
                .map_err(unavailable)?;
                if updated.rows_affected() != 1 {
                    return Err(SessionTimelineError::InvalidStoredData);
                }
                checkpointed = checkpointed
                    .checked_add(1)
                    .ok_or(SessionTimelineError::InvalidStoredData)?;
            }

            transaction.commit().await.map_err(unavailable)?;
            Ok(checkpointed)
        }
    }
}

fn unavailable(_: sqlx::Error) -> SessionTimelineError {
    SessionTimelineError::Unavailable
}

#[derive(FromRow)]
struct TimelineSessionRow {
    session_id: String,
    session_version: i64,
    timeline_position_ms: i64,
    timeline_sampled_at: Option<OffsetDateTime>,
}
