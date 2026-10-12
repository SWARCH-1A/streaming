use std::future::Future;

use serde_json::Value;
use time::OffsetDateTime;
use uuid::Uuid;

#[derive(Clone, Debug)]
pub struct MediaDeadLetter {
    pub dead_letter_id: Uuid,
    pub event_id: Uuid,
    pub aggregate_id: String,
    pub event_type: String,
    pub payload: Value,
    pub reason_code: String,
    pub attempts: i32,
    pub first_failed_at: OffsetDateTime,
    pub last_failed_at: OffsetDateTime,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum MediaDeadLetterRepositoryError {
    #[error("dead-letter record was not found")]
    NotFound,
    #[error("dead-letter record is already closed")]
    AlreadyClosed,
    #[error("dead-letter record is currently being processed or is inconsistent")]
    NotActionable,
    #[error("dead-letter record contains invalid stored data")]
    InvalidStoredData,
    #[error("streaming persistence is unavailable")]
    Unavailable,
}

pub trait MediaDeadLetterRepository: Send + Sync {
    fn list_open(
        &self,
        limit: i64,
    ) -> impl Future<Output = Result<Vec<MediaDeadLetter>, MediaDeadLetterRepositoryError>> + Send;

    fn redrive(
        &self,
        event_id: Uuid,
        operator_id: String,
        resolution_note: String,
    ) -> impl Future<Output = Result<(), MediaDeadLetterRepositoryError>> + Send;

    fn close(
        &self,
        event_id: Uuid,
        operator_id: String,
        resolution_note: String,
    ) -> impl Future<Output = Result<(), MediaDeadLetterRepositoryError>> + Send;
}
