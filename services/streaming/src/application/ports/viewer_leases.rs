use std::future::Future;

use time::OffsetDateTime;
use uuid::Uuid;

use crate::domain::ids::SessionId;

pub struct ViewerLeaseCredentials {
    pub lease_id: String,
    pub lease_token: String,
    pub token_hash: [u8; 32],
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ViewerLeaseReceipt {
    pub created: bool,
    pub session_id: String,
    pub expires_at: OffsetDateTime,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ViewerLeaseRepositoryError {
    NotFound,
    SessionNotPlayable,
    SessionEnded,
    InvalidToken,
    Expired,
    IdempotencyConflict,
    Unavailable,
    InvalidStoredData,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ViewerLeaseCredentialError;

pub trait ViewerLeaseCredentialIssuer: Send + Sync {
    fn issue(
        &self,
        session_id: &SessionId,
        idempotency_key: Uuid,
    ) -> Result<ViewerLeaseCredentials, ViewerLeaseCredentialError>;
}

pub trait ViewerLeaseRepository: Send + Sync {
    fn create(
        &self,
        session_id: SessionId,
        idempotency_key: Uuid,
        lease_id: String,
        token_hash: [u8; 32],
    ) -> impl Future<Output = Result<ViewerLeaseReceipt, ViewerLeaseRepositoryError>> + Send;

    fn heartbeat(
        &self,
        lease_id: String,
        token_hash: [u8; 32],
    ) -> impl Future<Output = Result<ViewerLeaseReceipt, ViewerLeaseRepositoryError>> + Send;

    fn close(
        &self,
        lease_id: String,
        token_hash: [u8; 32],
    ) -> impl Future<Output = Result<(), ViewerLeaseRepositoryError>> + Send;
}
