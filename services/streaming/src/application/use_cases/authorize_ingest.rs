use std::sync::Arc;

use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::{
    application::ports::{
        ingest_authorization::{
            AuthorizeIngestCommand, IngestAuthorization, IngestAuthorizationError,
            IngestAuthorizationRepository,
        },
        session_clock::SessionMonotonicClock,
    },
    domain::ids::{SessionId, StreamId},
};

pub struct AuthorizeIngest<R> {
    repository: Arc<R>,
    session_clock: Arc<dyn SessionMonotonicClock>,
    owner_instance_id: String,
}

impl<R> AuthorizeIngest<R>
where
    R: IngestAuthorizationRepository + 'static,
{
    pub fn new(
        repository: Arc<R>,
        session_clock: Arc<dyn SessionMonotonicClock>,
        owner_instance_id: String,
    ) -> Self {
        Self {
            repository,
            session_clock,
            owner_instance_id,
        }
    }

    pub async fn execute(
        &self,
        ingest_attempt_id: Uuid,
        stream_key: String,
    ) -> Result<IngestAuthorization, IngestAuthorizationError> {
        self.execute_for_stream(ingest_attempt_id, stream_key, None)
            .await
    }

    pub async fn execute_for_stream(
        &self,
        ingest_attempt_id: Uuid,
        stream_key: String,
        expected_stream_id: Option<StreamId>,
    ) -> Result<IngestAuthorization, IngestAuthorizationError> {
        if stream_key.is_empty() || stream_key.len() > 512 {
            return Err(IngestAuthorizationError::InvalidStreamKey);
        }

        let stream_key_hash: [u8; 32] = Sha256::digest(stream_key.as_bytes()).into();
        let mut fingerprint = Sha256::new();
        fingerprint.update(b"streaming.ingest.authorize.v1\0");
        fingerprint.update(ingest_attempt_id.as_bytes());
        fingerprint.update(stream_key_hash);
        if let Some(stream_id) = expected_stream_id.as_ref() {
            fingerprint.update(stream_id.as_str().as_bytes());
        }
        let request_fingerprint: [u8; 32] = fingerprint.finalize().into();

        let result = self
            .repository
            .authorize(
                AuthorizeIngestCommand {
                    ingest_attempt_id,
                    expected_stream_id,
                    stream_key_hash,
                    request_fingerprint,
                    candidate_session_id: SessionId::new(),
                    owner_instance_id: self.owner_instance_id.clone(),
                },
                self.session_clock.as_ref(),
            )
            .await?;

        for session_id in result.sessions_ended {
            self.session_clock.forget(&session_id);
        }

        Ok(result.authorization)
    }
}
