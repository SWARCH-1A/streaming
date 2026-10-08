use std::future::Future;

use sqlx::{FromRow, PgPool};

use crate::application::ports::worker_queue_metrics::{
    WorkerQueueMetricsError, WorkerQueueMetricsRepository, WorkerQueueSnapshot,
};

pub struct PostgresWorkerQueueMetricsRepository {
    pool: PgPool,
}

impl PostgresWorkerQueueMetricsRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

impl WorkerQueueMetricsRepository for PostgresWorkerQueueMetricsRepository {
    fn snapshot(
        &self,
    ) -> impl Future<Output = Result<WorkerQueueSnapshot, WorkerQueueMetricsError>> + Send {
        async move {
            let row = sqlx::query_as::<_, WorkerQueueMetricsRow>(
                "SELECT \
                    (SELECT COUNT(*)::BIGINT FROM media_callback_inbox WHERE processed_at IS NULL) AS callback_queue_depth, \
                    (SELECT (EXTRACT(EPOCH FROM (clock_timestamp() - MIN(received_at))) * 1000)::BIGINT FROM media_callback_inbox WHERE processed_at IS NULL) AS callback_oldest_age_millis, \
                    (SELECT COALESCE(MAX(processing_attempts), 0)::INTEGER FROM media_callback_inbox WHERE processed_at IS NULL) AS callback_max_attempts, \
                    (SELECT COUNT(*)::BIGINT FROM streaming_outbox WHERE published_at IS NULL AND dead_letter_at IS NULL AND closed_at IS NULL) AS outbox_queue_depth, \
                    (SELECT (EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))) * 1000)::BIGINT FROM streaming_outbox WHERE published_at IS NULL AND dead_letter_at IS NULL AND closed_at IS NULL) AS outbox_oldest_age_millis, \
                    (SELECT COALESCE(MAX(attempts), 0)::INTEGER FROM streaming_outbox WHERE published_at IS NULL AND dead_letter_at IS NULL AND closed_at IS NULL) AS outbox_max_attempts, \
                    ((SELECT COUNT(*)::BIGINT FROM streaming_dead_letters WHERE closed_at IS NULL)+(SELECT COUNT(*)::BIGINT FROM streaming_outbox WHERE dead_letter_at IS NOT NULL AND closed_at IS NULL)) AS open_dead_letter_count, \
                    (SELECT (EXTRACT(EPOCH FROM (clock_timestamp() - MIN(first_failed_at))) * 1000)::BIGINT FROM (SELECT first_failed_at FROM streaming_dead_letters WHERE closed_at IS NULL UNION ALL SELECT first_attempt_at FROM streaming_outbox WHERE dead_letter_at IS NOT NULL AND closed_at IS NULL) d) AS dead_letter_oldest_age_millis, \
                    (SELECT COALESCE(MAX(attempts), 0)::INTEGER FROM (SELECT attempts FROM streaming_dead_letters WHERE closed_at IS NULL UNION ALL SELECT attempts FROM streaming_outbox WHERE dead_letter_at IS NOT NULL AND closed_at IS NULL) d) AS dead_letter_max_attempts",
            )
            .fetch_one(&self.pool)
            .await
            .map_err(|_| WorkerQueueMetricsError)?;

            Ok(WorkerQueueSnapshot {
                callback_queue_depth: row.callback_queue_depth,
                callback_oldest_age_millis: row.callback_oldest_age_millis,
                callback_max_attempts: row.callback_max_attempts,
                outbox_queue_depth: row.outbox_queue_depth,
                outbox_oldest_age_millis: row.outbox_oldest_age_millis,
                outbox_max_attempts: row.outbox_max_attempts,
                open_dead_letter_count: row.open_dead_letter_count,
                dead_letter_oldest_age_millis: row.dead_letter_oldest_age_millis,
                dead_letter_max_attempts: row.dead_letter_max_attempts,
            })
        }
    }
}

#[derive(FromRow)]
struct WorkerQueueMetricsRow {
    callback_queue_depth: i64,
    callback_oldest_age_millis: Option<i64>,
    callback_max_attempts: i32,
    outbox_queue_depth: i64,
    outbox_oldest_age_millis: Option<i64>,
    outbox_max_attempts: i32,
    open_dead_letter_count: i64,
    dead_letter_oldest_age_millis: Option<i64>,
    dead_letter_max_attempts: i32,
}
