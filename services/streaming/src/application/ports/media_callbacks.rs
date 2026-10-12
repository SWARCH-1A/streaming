use std::future::Future;
use std::time::Duration;

use serde_json::Value;
use time::OffsetDateTime;
use uuid::Uuid;

use crate::{
    application::ports::{media_server::PlaybackEvidence, session_clock::SessionMonotonicClock},
    domain::ids::{SessionId, StreamId},
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaCallbackKind {
    SourceConnected,
    PlaybackReady,
    SourceLost,
}

impl MediaCallbackKind {
    pub fn as_db_str(self) -> &'static str {
        match self {
            Self::SourceConnected => "SOURCE_CONNECTED",
            Self::PlaybackReady => "PLAYBACK_READY",
            Self::SourceLost => "SOURCE_LOST",
        }
    }
}

pub struct AcceptMediaCallbackCommand {
    pub event_id: Uuid,
    pub kind: MediaCallbackKind,
    pub stream_id: StreamId,
    pub session_id: SessionId,
    pub stream_generation: i64,
    pub source_generation: i64,
    pub payload_hash: [u8; 32],
    pub payload: Value,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaCallbackReceipt {
    Accepted,
    Duplicate,
    StaleGeneration,
    DuplicateStaleGeneration,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaCallbackSessionState {
    Preparing,
    Live,
    ReconnectGrace,
    Ended,
}

#[derive(Clone, Debug)]
pub struct ClaimedMediaCallback {
    pub event_id: Uuid,
    pub kind: MediaCallbackKind,
    pub stream_id: StreamId,
    pub session_id: SessionId,
    pub stream_generation: i64,
    pub source_generation: i64,
    pub fencing_token: i64,
    pub playback_path: Option<String>,
    pub received_at: OffsetDateTime,
    pub processing_attempts: i32,
    pub session_state: MediaCallbackSessionState,
    pub current_playback_path: Option<String>,
    pub media_node_id: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaCallbackRepositoryError {
    EventIdConflict,
    SessionMismatch,
    SessionEnded,
    Unavailable,
    InvalidStoredData,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum MediaCallbackProcessingError {
    #[error("callback claim is no longer owned by this worker")]
    LeaseLost,
    #[error("verified playback evidence is required")]
    PlaybackNotVerified,
    #[error("stored streaming callback data is inconsistent")]
    InvalidStoredData,
    #[error("streaming persistence is unavailable")]
    Unavailable,
}

pub trait MediaCallbackRepository: Send + Sync {
    fn accept(
        &self,
        command: AcceptMediaCallbackCommand,
    ) -> impl Future<Output = Result<MediaCallbackReceipt, MediaCallbackRepositoryError>> + Send;
}

pub trait MediaCallbackProcessingRepository: Send + Sync {
    fn claim_next(
        &self,
        owner_instance_id: String,
    ) -> impl Future<Output = Result<Option<ClaimedMediaCallback>, MediaCallbackProcessingError>> + Send;

    fn apply<'a>(
        &'a self,
        callback: ClaimedMediaCallback,
        owner_instance_id: String,
        playback_evidence: Option<PlaybackEvidence>,
        session_clock: &'a dyn SessionMonotonicClock,
    ) -> impl Future<Output = Result<(), MediaCallbackProcessingError>> + Send + 'a;

    fn retry(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        error_code: &'static str,
        delay: Duration,
    ) -> impl Future<Output = Result<(), MediaCallbackProcessingError>> + Send;

    fn dead_letter(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        reason_code: &'static str,
    ) -> impl Future<Output = Result<(), MediaCallbackProcessingError>> + Send;
}
