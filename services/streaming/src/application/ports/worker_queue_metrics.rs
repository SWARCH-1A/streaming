use std::future::Future;

use thiserror::Error;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WorkerQueueSnapshot {
    pub callback_queue_depth: i64,
    pub callback_oldest_age_millis: Option<i64>,
    pub callback_max_attempts: i32,
    pub outbox_queue_depth: i64,
    pub outbox_oldest_age_millis: Option<i64>,
    pub outbox_max_attempts: i32,
    pub open_dead_letter_count: i64,
    pub dead_letter_oldest_age_millis: Option<i64>,
    pub dead_letter_max_attempts: i32,
}

#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
#[error("worker queue metrics are unavailable")]
pub struct WorkerQueueMetricsError;

pub trait WorkerQueueMetricsRepository: Send + Sync {
    fn snapshot(
        &self,
    ) -> impl Future<Output = Result<WorkerQueueSnapshot, WorkerQueueMetricsError>> + Send;
}
