use std::sync::Arc;

use sha2::{Digest, Sha256};

use crate::{
    application::ports::{
        identity::SessionCredential,
        owner_context::{
            OwnerContextError, OwnerContextGateway, OwnerContextRequest, OwnerOperation,
        },
        stream_config::{
            RotateIngestKeyCommand, SecretGenerator, StreamConfigRepository,
            StreamConfigRepositoryError,
        },
        streaming_repository::{RepositoryError, StreamingRepository},
    },
    domain::ids::StreamId,
};

pub struct RotatedIngestKey {
    pub stream_id: StreamId,
    pub channel_id: String,
    pub rtmp_url: String,
    pub stream_key: String,
    pub ingest_key_version: i64,
}

pub struct RotateIngestKey<I, R, Q, G> {
    identity: Arc<I>,
    write_repository: Arc<R>,
    read_repository: Arc<Q>,
    secret_generator: Arc<G>,
    rtmp_ingest_base_url: Option<String>,
}

impl<I, R, Q, G> RotateIngestKey<I, R, Q, G>
where
    I: OwnerContextGateway + 'static,
    R: StreamConfigRepository + 'static,
    Q: StreamingRepository + 'static,
    G: SecretGenerator + 'static,
{
    pub fn new(
        identity: Arc<I>,
        write_repository: Arc<R>,
        read_repository: Arc<Q>,
        secret_generator: Arc<G>,
        rtmp_ingest_base_url: Option<String>,
    ) -> Self {
        Self {
            identity,
            write_repository,
            read_repository,
            secret_generator,
            rtmp_ingest_base_url,
        }
    }

    pub async fn execute(
        &self,
        credential: SessionCredential,
        stream_id: String,
    ) -> Result<RotatedIngestKey, RotateIngestKeyError> {
        let stream_id = StreamId::parse(stream_id).ok_or(RotateIngestKeyError::NotFound)?;
        let rtmp_ingest_base_url = self
            .rtmp_ingest_base_url
            .as_deref()
            .ok_or(RotateIngestKeyError::Unavailable)?;
        let config = self
            .read_repository
            .find_stream_config(stream_id.clone())
            .await
            .map_err(map_read_error)?
            .ok_or(RotateIngestKeyError::NotFound)?;
        let context = self
            .identity
            .authorize(
                credential,
                OwnerContextRequest {
                    command_id: uuid::Uuid::new_v4(),
                    operation: OwnerOperation::RotateKey,
                    channel_id: config.channel_id.clone(),
                    category_id: None,
                    tag_ids: None,
                },
            )
            .await
            .map_err(map_owner_error)?;
        if config.owner_user_id != context.user_id {
            return Err(RotateIngestKeyError::Forbidden);
        }

        let stream_key = self
            .secret_generator
            .generate_stream_key()
            .await
            .map_err(|_| RotateIngestKeyError::Unavailable)?;
        let ingest_key_hash: [u8; 32] = Sha256::digest(stream_key.as_bytes()).into();
        let result = self
            .write_repository
            .rotate_ingest_key(RotateIngestKeyCommand {
                authorization_expires_at: Some(context.expires_at),
                stream_id,
                ingest_key_hash,
            })
            .await
            .map_err(map_write_error)?;
        if result.config.owner_user_id != context.user_id {
            return Err(RotateIngestKeyError::Forbidden);
        }
        let rtmp_url = format!(
            "{}/{}",
            rtmp_ingest_base_url.trim_end_matches('/'),
            result.config.stream_id.as_str()
        );
        Ok(RotatedIngestKey {
            stream_id: result.config.stream_id,
            channel_id: result.config.channel_id,
            rtmp_url,
            stream_key,
            ingest_key_version: result.ingest_key_version,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RotateIngestKeyError {
    IdentityInactive,
    IdentityUnavailable,
    NotFound,
    Forbidden,
    ActiveSession,
    Unavailable,
    InvalidStoredData,
}

fn map_owner_error(error: OwnerContextError) -> RotateIngestKeyError {
    match error {
        OwnerContextError::Inactive => RotateIngestKeyError::IdentityInactive,
        OwnerContextError::Forbidden => RotateIngestKeyError::Forbidden,
        OwnerContextError::NotFound => RotateIngestKeyError::NotFound,
        OwnerContextError::InvalidCatalog | OwnerContextError::Unavailable => {
            RotateIngestKeyError::IdentityUnavailable
        }
    }
}

fn map_read_error(error: RepositoryError) -> RotateIngestKeyError {
    match error {
        RepositoryError::NotFound => RotateIngestKeyError::NotFound,
        RepositoryError::Conflict => RotateIngestKeyError::Forbidden,
        RepositoryError::Unavailable => RotateIngestKeyError::Unavailable,
        RepositoryError::InvalidStoredData => RotateIngestKeyError::InvalidStoredData,
    }
}

fn map_write_error(error: StreamConfigRepositoryError) -> RotateIngestKeyError {
    match error {
        StreamConfigRepositoryError::NotFound => RotateIngestKeyError::NotFound,
        StreamConfigRepositoryError::Conflict => RotateIngestKeyError::ActiveSession,
        StreamConfigRepositoryError::IdempotencyKeyReused
        | StreamConfigRepositoryError::Unavailable => RotateIngestKeyError::Unavailable,
        StreamConfigRepositoryError::InvalidStoredData => RotateIngestKeyError::InvalidStoredData,
    }
}
