use std::sync::Arc;

use crate::{
    application::ports::{
        identity::SessionCredential,
        owner_context::{
            OwnerContextError, OwnerContextGateway, OwnerContextRequest, OwnerOperation,
        },
        session_clock::SessionMonotonicClock,
        streaming_repository::{RepositoryError, StreamingRepository},
    },
    domain::ids::SessionId,
};

pub struct StopStreamSession<I, R> {
    identity: Arc<I>,
    repository: Arc<R>,
    session_clock: Arc<dyn SessionMonotonicClock>,
}

impl<I, R> StopStreamSession<I, R>
where
    I: OwnerContextGateway + 'static,
    R: StreamingRepository + 'static,
{
    pub fn new(
        identity: Arc<I>,
        repository: Arc<R>,
        session_clock: Arc<dyn SessionMonotonicClock>,
    ) -> Self {
        Self {
            identity,
            repository,
            session_clock,
        }
    }

    pub async fn execute(
        &self,
        credential: SessionCredential,
        session_id: String,
    ) -> Result<(), StopStreamSessionError> {
        let session_id = SessionId::parse(session_id).ok_or(StopStreamSessionError::NotFound)?;
        let session = self
            .repository
            .find_session(session_id.clone())
            .await
            .map_err(map_repository_error)?
            .ok_or(StopStreamSessionError::NotFound)?;
        let config = self
            .repository
            .find_stream_config(session.stream_id.clone())
            .await
            .map_err(map_repository_error)?
            .ok_or(StopStreamSessionError::InvalidStoredData)?;
        if config.stream_id != session.stream_id || config.channel_id != session.channel_id {
            return Err(StopStreamSessionError::InvalidStoredData);
        }
        let context = self
            .identity
            .authorize(
                credential,
                OwnerContextRequest {
                    command_id: uuid::Uuid::new_v4(),
                    operation: OwnerOperation::StopSession,
                    channel_id: config.channel_id.clone(),
                    category_id: None,
                    tag_ids: None,
                },
            )
            .await
            .map_err(map_owner_error)?;
        if config.owner_user_id != context.user_id {
            return Err(StopStreamSessionError::Forbidden);
        }

        let timeline_position_ms = self
            .session_clock
            .timeline_elapsed(&session_id)
            .map(|elapsed| {
                i64::try_from(elapsed.as_millis())
                    .map_err(|_| StopStreamSessionError::InvalidStoredData)
            })
            .transpose()?;
        self.repository
            .stop_session(session_id, timeline_position_ms, Some(context.expires_at))
            .await
            .map_err(map_repository_error)?;
        self.session_clock.forget(&session.session_id);
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StopStreamSessionError {
    IdentityInactive,
    IdentityUnavailable,
    NotFound,
    Forbidden,
    Unavailable,
    InvalidStoredData,
}

fn map_owner_error(error: OwnerContextError) -> StopStreamSessionError {
    match error {
        OwnerContextError::Inactive => StopStreamSessionError::IdentityInactive,
        OwnerContextError::Forbidden => StopStreamSessionError::Forbidden,
        OwnerContextError::NotFound => StopStreamSessionError::NotFound,
        OwnerContextError::InvalidCatalog | OwnerContextError::Unavailable => {
            StopStreamSessionError::IdentityUnavailable
        }
    }
}

fn map_repository_error(error: RepositoryError) -> StopStreamSessionError {
    match error {
        RepositoryError::NotFound => StopStreamSessionError::NotFound,
        RepositoryError::Conflict => StopStreamSessionError::Forbidden,
        RepositoryError::Unavailable => StopStreamSessionError::Unavailable,
        RepositoryError::InvalidStoredData => StopStreamSessionError::InvalidStoredData,
    }
}
