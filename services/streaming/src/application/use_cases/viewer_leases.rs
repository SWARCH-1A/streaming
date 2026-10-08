use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::{
    application::ports::viewer_leases::{
        ViewerLeaseCredentialError, ViewerLeaseCredentialIssuer, ViewerLeaseRepository,
        ViewerLeaseRepositoryError,
    },
    domain::ids::SessionId,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ViewerLeaseError {
    InvalidSessionId,
    InvalidLeaseId,
    CredentialsUnavailable,
    NotFound,
    SessionNotPlayable,
    SessionEnded,
    IdempotencyConflict,
    InvalidToken,
    Unavailable,
    InvalidStoredData,
}

pub struct CreatedViewerLease {
    pub lease_id: String,
    pub lease_token: String,
    pub session_id: String,
    pub created: bool,
}

pub struct ViewerLeaseHeartbeat {
    pub lease_id: String,
    pub session_id: String,
    pub expires_at: time::OffsetDateTime,
}

pub struct CreateViewerLease<I, R> {
    credential_issuer: I,
    repository: R,
}

impl<I, R> CreateViewerLease<I, R> {
    pub fn new(credential_issuer: I, repository: R) -> Self {
        Self {
            credential_issuer,
            repository,
        }
    }
}

impl<I, R> CreateViewerLease<I, R>
where
    I: ViewerLeaseCredentialIssuer,
    R: ViewerLeaseRepository,
{
    pub async fn execute(
        &self,
        session_id: String,
        idempotency_key: Uuid,
    ) -> Result<CreatedViewerLease, ViewerLeaseError> {
        let parsed_session_id =
            SessionId::parse(session_id.clone()).ok_or(ViewerLeaseError::InvalidSessionId)?;
        let credentials = self
            .credential_issuer
            .issue(&parsed_session_id, idempotency_key)
            .map_err(|ViewerLeaseCredentialError| ViewerLeaseError::CredentialsUnavailable)?;
        let receipt = self
            .repository
            .create(
                parsed_session_id,
                idempotency_key,
                credentials.lease_id.clone(),
                credentials.token_hash,
            )
            .await
            .map_err(map_repository_error)?;

        Ok(CreatedViewerLease {
            lease_id: credentials.lease_id,
            lease_token: credentials.lease_token,
            session_id: receipt.session_id,
            created: receipt.created,
        })
    }
}

pub struct HeartbeatViewerLease<R> {
    repository: R,
}

impl<R> HeartbeatViewerLease<R> {
    pub fn new(repository: R) -> Self {
        Self { repository }
    }
}

impl<R> HeartbeatViewerLease<R>
where
    R: ViewerLeaseRepository,
{
    pub async fn execute(
        &self,
        lease_id: String,
        token: String,
    ) -> Result<ViewerLeaseHeartbeat, ViewerLeaseError> {
        validate_lease_id(&lease_id)?;
        let token_hash = hash_token(&token)?;
        let receipt = self
            .repository
            .heartbeat(lease_id.clone(), token_hash)
            .await
            .map_err(map_repository_error)?;
        Ok(ViewerLeaseHeartbeat {
            lease_id,
            session_id: receipt.session_id,
            expires_at: receipt.expires_at,
        })
    }
}

pub struct CloseViewerLease<R> {
    repository: R,
}

impl<R> CloseViewerLease<R> {
    pub fn new(repository: R) -> Self {
        Self { repository }
    }
}

impl<R> CloseViewerLease<R>
where
    R: ViewerLeaseRepository,
{
    pub async fn execute(&self, lease_id: String, token: String) -> Result<(), ViewerLeaseError> {
        validate_lease_id(&lease_id)?;
        let token_hash = hash_token(&token)?;
        self.repository
            .close(lease_id, token_hash)
            .await
            .map_err(map_repository_error)
    }
}

fn hash_token(token: &str) -> Result<[u8; 32], ViewerLeaseError> {
    if token.len() != 67
        || !token.starts_with("vl_")
        || !token[3..].bytes().all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(ViewerLeaseError::InvalidToken);
    }
    Ok(Sha256::digest(token.as_bytes()).into())
}

fn validate_lease_id(lease_id: &str) -> Result<(), ViewerLeaseError> {
    if lease_id.len() != 38
        || !lease_id.starts_with("lease_")
        || !lease_id[6..].bytes().all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(ViewerLeaseError::InvalidLeaseId);
    }
    Ok(())
}

fn map_repository_error(error: ViewerLeaseRepositoryError) -> ViewerLeaseError {
    match error {
        ViewerLeaseRepositoryError::NotFound => ViewerLeaseError::NotFound,
        ViewerLeaseRepositoryError::SessionNotPlayable => ViewerLeaseError::SessionNotPlayable,
        ViewerLeaseRepositoryError::SessionEnded => ViewerLeaseError::SessionEnded,
        ViewerLeaseRepositoryError::IdempotencyConflict => ViewerLeaseError::IdempotencyConflict,
        ViewerLeaseRepositoryError::InvalidToken | ViewerLeaseRepositoryError::Expired => {
            ViewerLeaseError::InvalidToken
        }
        ViewerLeaseRepositoryError::Unavailable => ViewerLeaseError::Unavailable,
        ViewerLeaseRepositoryError::InvalidStoredData => ViewerLeaseError::InvalidStoredData,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lease_token_hash_is_stable_and_bound_to_the_full_token() {
        let token = format!("vl_{}", "a".repeat(64));
        let same_token = token.clone();
        let other_token = format!("vl_{}", "b".repeat(64));

        let first_hash = hash_token(&token);
        let repeated_hash = hash_token(&same_token);
        let other_hash = hash_token(&other_token);

        assert!(first_hash.is_ok());
        assert_eq!(first_hash, repeated_hash);
        assert_ne!(first_hash, other_hash);
    }

    #[test]
    fn lease_token_rejects_wrong_prefix_length_and_non_hex_characters() {
        assert_eq!(
            hash_token(&format!("xx_{}", "a".repeat(64))),
            Err(ViewerLeaseError::InvalidToken)
        );
        assert_eq!(hash_token("vl_short"), Err(ViewerLeaseError::InvalidToken));
        assert_eq!(
            hash_token(&format!("vl_{}g", "a".repeat(63))),
            Err(ViewerLeaseError::InvalidToken)
        );
    }

    #[test]
    fn lease_id_requires_its_prefix_and_32_hexadecimal_characters() {
        assert_eq!(
            validate_lease_id(&format!("lease_{}", "a".repeat(32))),
            Ok(())
        );
        assert_eq!(
            validate_lease_id(&format!("lease_{}", "F".repeat(32))),
            Ok(())
        );
        assert_eq!(
            validate_lease_id(&format!("other_{}", "a".repeat(32))),
            Err(ViewerLeaseError::InvalidLeaseId)
        );
        assert_eq!(
            validate_lease_id(&format!("lease_{}", "g".repeat(32))),
            Err(ViewerLeaseError::InvalidLeaseId)
        );
        assert_eq!(
            validate_lease_id(&format!("lease_{}", "a".repeat(31))),
            Err(ViewerLeaseError::InvalidLeaseId)
        );
    }
}
