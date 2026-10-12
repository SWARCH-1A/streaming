use std::future::Future;

use uuid::Uuid;

use crate::{
    application::ports::session_clock::SessionMonotonicClock,
    domain::ids::{SessionId, StreamId},
};

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IngestAuthorization {
    pub stream_id: StreamId,
    pub session_id: SessionId,
    pub stream_generation: i64,
    pub source_generation: i64,
}

pub struct AuthorizeIngestCommand {
    pub ingest_attempt_id: Uuid,
    pub expected_stream_id: Option<StreamId>,
    pub stream_key_hash: [u8; 32],
    pub request_fingerprint: [u8; 32],
    pub candidate_session_id: SessionId,
    pub owner_instance_id: String,
}

pub struct AuthorizeIngestResult {
    pub authorization: IngestAuthorization,
    pub sessions_ended: Vec<SessionId>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum IngestAuthorizationError {
    #[error("invalid stream key")]
    InvalidStreamKey,
    #[error("channel already has an active source")]
    ChannelAlreadyActive,
    #[error("platform live session limit has been reached")]
    LiveSessionLimit,
    #[error("ingest attempt identifier was reused with a different request")]
    IdempotencyKeyReused,
    #[error("active session is owned by another healthy instance")]
    SessionOwnedElsewhere,
    #[error("no eligible media node is available")]
    MediaNodeUnavailable,
    #[error("stored streaming data is inconsistent")]
    InvalidStoredData,
    #[error("streaming persistence is unavailable")]
    PersistenceUnavailable,
}

pub trait IngestAuthorizationRepository: Send + Sync {
    fn authorize<'a>(
        &'a self,
        command: AuthorizeIngestCommand,
        session_clock: &'a dyn SessionMonotonicClock,
    ) -> impl Future<Output = Result<AuthorizeIngestResult, IngestAuthorizationError>> + Send + 'a;

    fn renew_owner_leases(
        &self,
        owner_instance_id: String,
    ) -> impl Future<Output = Result<(), IngestAuthorizationError>> + Send;
}
