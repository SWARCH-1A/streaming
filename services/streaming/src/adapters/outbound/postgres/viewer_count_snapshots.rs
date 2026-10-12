use std::future::Future;

use sqlx::{FromRow, PgPool};
use time::OffsetDateTime;

use crate::application::ports::viewer_count_snapshots::{
    ViewerCountSnapshotError, ViewerCountSnapshotRepository,
};

pub struct PostgresViewerCountSnapshotRepository {
    pool: PgPool,
}

impl PostgresViewerCountSnapshotRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

impl ViewerCountSnapshotRepository for PostgresViewerCountSnapshotRepository {
    fn refresh_due_snapshots(
        &self,
    ) -> impl Future<Output = Result<(), ViewerCountSnapshotError>> + Send {
        async move {
            let mut transaction = self.pool.begin().await.map_err(unavailable)?;
            sqlx::query(
                "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
            )
            .execute(&mut *transaction)
            .await
            .map_err(unavailable)?;
            let sessions = sqlx::query_as::<_, ActiveViewerCountRow>(
                "SELECT session_id, viewer_count, count_version, viewer_count_observed_at FROM stream_sessions WHERE status IN ('LIVE', 'RECONNECT_GRACE') ORDER BY COALESCE(viewer_count_observed_at, started_at), session_id FOR UPDATE SKIP LOCKED",
            ).fetch_all(&mut *transaction).await.map_err(unavailable)?;
            let now = sqlx::query_scalar::<_, OffsetDateTime>("SELECT clock_timestamp()")
                .fetch_one(&mut *transaction)
                .await
                .map_err(unavailable)?;
            for session in sessions {
                let current_count = sqlx::query_scalar::<_, i64>(
                    "SELECT count(*) FROM viewer_leases WHERE session_id = $1 AND closed_at IS NULL AND heartbeat_at > $2 - INTERVAL '30 seconds'",
                ).bind(&session.session_id).bind(now).fetch_one(&mut *transaction).await.map_err(unavailable)?;
                if !snapshot_due(
                    current_count != session.viewer_count,
                    session.viewer_count_observed_at,
                    now,
                ) {
                    continue;
                }
                let count_version = session
                    .count_version
                    .checked_add(1)
                    .filter(|version| *version > 0)
                    .ok_or(ViewerCountSnapshotError)?;
                sqlx::query(
                    "UPDATE stream_sessions SET viewer_count = $2, count_version = $3, viewer_count_observed_at = $4, viewer_count_changed_at = CASE WHEN viewer_count IS DISTINCT FROM $2 THEN $4 ELSE viewer_count_changed_at END, updated_at = $4 WHERE session_id = $1",
                ).bind(&session.session_id).bind(current_count).bind(count_version).bind(now)
                    .execute(&mut *transaction).await.map_err(unavailable)?;
            }
            transaction.commit().await.map_err(unavailable)
        }
    }
}

fn snapshot_due(changed: bool, observed_at: Option<OffsetDateTime>, now: OffsetDateTime) -> bool {
    observed_at.is_none_or(|observed| {
        observed <= now - time::Duration::seconds(1)
            || (changed && observed <= now - time::Duration::seconds(1))
    })
}
fn unavailable(_: sqlx::Error) -> ViewerCountSnapshotError {
    ViewerCountSnapshotError
}

#[derive(FromRow)]
struct ActiveViewerCountRow {
    session_id: String,
    viewer_count: i64,
    count_version: i64,
    viewer_count_observed_at: Option<OffsetDateTime>,
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn snapshots_coalesce_changes_and_refresh_unchanged_counts() {
        let now = OffsetDateTime::UNIX_EPOCH + time::Duration::seconds(100);
        assert!(snapshot_due(false, None, now));
        assert!(!snapshot_due(
            true,
            Some(now - time::Duration::milliseconds(999)),
            now
        ));
        assert!(snapshot_due(
            true,
            Some(now - time::Duration::seconds(1)),
            now
        ));
        assert!(!snapshot_due(
            false,
            Some(now - time::Duration::milliseconds(999)),
            now
        ));
        assert!(snapshot_due(
            false,
            Some(now - time::Duration::seconds(1)),
            now
        ));
    }
}
