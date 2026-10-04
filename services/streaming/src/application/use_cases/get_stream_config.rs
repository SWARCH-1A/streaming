use std::sync::Arc;

use crate::{
    application::ports::{
        channels::{ChannelsError, ChannelsGateway},
        identity::{IdentityError, IdentityGateway, SessionCredential},
        streaming_repository::{RepositoryError, StreamConfigSnapshot, StreamingRepository},
    },
    domain::session::StreamSession,
};

pub struct OwnedStreamConfigView {
    pub config: StreamConfigSnapshot,
    pub active_session: Option<StreamSession>,
    pub rtmp_url: String,
}

pub struct GetStreamConfig<I, C, R> {
    identity: Arc<I>,
    channels: Arc<C>,
    repository: Arc<R>,
    rtmp_ingest_base_url: Option<String>,
}

impl<I, C, R> GetStreamConfig<I, C, R>
where
    I: IdentityGateway + 'static,
    C: ChannelsGateway + 'static,
    R: StreamingRepository + 'static,
{
    pub fn new(
        identity: Arc<I>,
        channels: Arc<C>,
        repository: Arc<R>,
        rtmp_ingest_base_url: Option<String>,
    ) -> Self {
        Self {
            identity,
            channels,
            repository,
            rtmp_ingest_base_url,
        }
    }

    pub async fn execute(
        &self,
        credential: SessionCredential,
        channel_id: String,
    ) -> Result<OwnedStreamConfigView, GetStreamConfigError> {
        if !valid_identifier(&channel_id) {
            return Err(GetStreamConfigError::NotFound);
        }
        let rtmp_ingest_base_url = self
            .rtmp_ingest_base_url
            .as_deref()
            .ok_or(GetStreamConfigError::Unavailable)?;
        let principal = self
            .identity
            .introspect(credential)
            .await
            .map_err(map_identity_error)?;
        let channel = self
            .channels
            .find_owner_channel(principal.user_id.clone())
            .await
            .map_err(map_channels_error)?
            .ok_or(GetStreamConfigError::ChannelNotFound)?;
        if channel.channel_id != channel_id || channel.owner_user_id != principal.user_id {
            return Err(GetStreamConfigError::Forbidden);
        }
        let config = self
            .repository
            .find_stream_config_by_channel(channel_id.clone())
            .await
            .map_err(map_repository_error)?
            .ok_or(GetStreamConfigError::NotFound)?;
        if config.owner_user_id != principal.user_id || config.channel_id != channel_id {
            return Err(GetStreamConfigError::Forbidden);
        }
        let active_session = self
            .repository
            .find_active_session_for_stream(config.stream_id.clone())
            .await
            .map_err(map_repository_error)?;
        if active_session.as_ref().is_some_and(|session| {
            session.stream_id != config.stream_id || session.channel_id != config.channel_id
        }) {
            return Err(GetStreamConfigError::InvalidStoredData);
        }
        let rtmp_url = format!(
            "{}/{}",
            rtmp_ingest_base_url.trim_end_matches('/'),
            config.stream_id.as_str()
        );
        Ok(OwnedStreamConfigView {
            config,
            active_session,
            rtmp_url,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum GetStreamConfigError {
    IdentityInactive,
    IdentityUnavailable,
    ChannelNotFound,
    NotFound,
    Forbidden,
    Unavailable,
    InvalidStoredData,
}

fn valid_identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}

fn map_identity_error(error: IdentityError) -> GetStreamConfigError {
    match error {
        IdentityError::Inactive => GetStreamConfigError::IdentityInactive,
        IdentityError::Unavailable | IdentityError::InvalidResponse => {
            GetStreamConfigError::IdentityUnavailable
        }
    }
}

fn map_channels_error(error: ChannelsError) -> GetStreamConfigError {
    match error {
        ChannelsError::NotFound => GetStreamConfigError::ChannelNotFound,
        ChannelsError::Unavailable | ChannelsError::InvalidResponse => {
            GetStreamConfigError::Unavailable
        }
    }
}

fn map_repository_error(error: RepositoryError) -> GetStreamConfigError {
    match error {
        RepositoryError::NotFound => GetStreamConfigError::NotFound,
        RepositoryError::Conflict => GetStreamConfigError::Forbidden,
        RepositoryError::Unavailable => GetStreamConfigError::Unavailable,
        RepositoryError::InvalidStoredData => GetStreamConfigError::InvalidStoredData,
    }
}
