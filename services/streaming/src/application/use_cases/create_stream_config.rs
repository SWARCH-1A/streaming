use std::sync::Arc;

use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::{
    application::ports::{
        identity::SessionCredential,
        owner_context::{
            OwnerContextError, OwnerContextGateway, OwnerContextRequest, OwnerOperation,
        },
        stream_config::{
            CreateStreamConfigCommand, SecretGenerator, StreamConfigRepository,
            StreamConfigRepositoryError,
        },
    },
    domain::ids::StreamId,
};

pub struct CreateStreamConfigRequest {
    pub channel_id: String,
    pub title: String,
    pub category_id: String,
    pub tag_ids: Vec<String>,
    pub idempotency_key: Uuid,
}

pub struct CreatedStreamConfig {
    pub stream_id: StreamId,
    pub channel_id: String,
    pub title: String,
    pub category_id: String,
    pub tag_ids: Vec<String>,
    pub metadata_version: i64,
    pub rtmp_url: String,
    pub stream_key: Option<String>,
    pub created: bool,
}

pub struct CreateStreamConfig<I, R, G> {
    identity: Arc<I>,
    repository: Arc<R>,
    secret_generator: Arc<G>,
    rtmp_ingest_base_url: Option<String>,
}

impl<I, R, G> CreateStreamConfig<I, R, G>
where
    I: OwnerContextGateway + 'static,
    R: StreamConfigRepository + 'static,
    G: SecretGenerator + 'static,
{
    pub fn new(
        identity: Arc<I>,
        repository: Arc<R>,
        secret_generator: Arc<G>,
        rtmp_ingest_base_url: Option<String>,
    ) -> Self {
        Self {
            identity,
            repository,
            secret_generator,
            rtmp_ingest_base_url,
        }
    }

    pub async fn execute(
        &self,
        credential: SessionCredential,
        request: CreateStreamConfigRequest,
    ) -> Result<CreatedStreamConfig, CreateStreamConfigError> {
        validate_request(&request)?;
        let rtmp_ingest_base_url = self
            .rtmp_ingest_base_url
            .as_deref()
            .ok_or(CreateStreamConfigError::Unavailable)?;
        let context = self
            .identity
            .authorize(
                credential,
                OwnerContextRequest {
                    command_id: Uuid::new_v4(),
                    operation: OwnerOperation::CreateConfig,
                    channel_id: request.channel_id.clone(),
                    category_id: Some(request.category_id.clone()),
                    tag_ids: Some(request.tag_ids.clone()),
                },
            )
            .await
            .map_err(map_owner_error)?;
        let mut catalog_labels = vec![
            context
                .category
                .ok_or(CreateStreamConfigError::TaxonomyUnavailable)?,
        ];
        catalog_labels.extend(
            context
                .tags
                .ok_or(CreateStreamConfigError::TaxonomyUnavailable)?,
        );
        let stream_key = self
            .secret_generator
            .generate_stream_key()
            .await
            .map_err(|_| CreateStreamConfigError::SecretGenerationUnavailable)?;
        let ingest_key_hash: [u8; 32] = Sha256::digest(stream_key.as_bytes()).into();
        let request_fingerprint = request_fingerprint(&request);
        let result = self
            .repository
            .create_if_absent(CreateStreamConfigCommand {
                authorization_expires_at: Some(context.expires_at),
                catalog_labels,
                stream_id: StreamId::new(),
                channel_id: request.channel_id,
                owner_user_id: context.user_id.clone(),
                title: request.title,
                category_id: request.category_id,
                tag_ids: request.tag_ids,
                ingest_key_hash,
                idempotency_key: request.idempotency_key,
                request_fingerprint,
            })
            .await
            .map_err(map_repository_error)?;
        if result.config.owner_user_id != context.user_id {
            return Err(CreateStreamConfigError::Forbidden);
        }

        let rtmp_url = format!(
            "{}/{}",
            rtmp_ingest_base_url.trim_end_matches('/'),
            result.config.stream_id.as_str()
        );

        Ok(CreatedStreamConfig {
            stream_id: result.config.stream_id,
            channel_id: result.config.channel_id,
            title: result.config.title,
            category_id: result.config.category_id,
            tag_ids: result.config.tag_ids,
            metadata_version: result.config.metadata_version,
            rtmp_url,
            stream_key: result.created.then_some(stream_key),
            created: result.created,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CreateStreamConfigError {
    InvalidRequest,
    IdentityInactive,
    IdentityUnavailable,
    ChannelNotFound,
    Forbidden,
    TaxonomyValueInactiveOrUnknown,
    TaxonomyUnavailable,
    IdempotencyKeyReused,
    SecretGenerationUnavailable,
    Unavailable,
    InvalidStoredData,
}

fn validate_request(request: &CreateStreamConfigRequest) -> Result<(), CreateStreamConfigError> {
    let title_length = request.title.chars().count();
    if request.title.contains('\0')
        || request.title.trim().is_empty()
        || !(1..=100).contains(&title_length)
        || request.category_id.is_empty()
        || request.tag_ids.len() > 5
        || !valid_identifier(&request.channel_id)
        || !valid_identifier(&request.category_id)
        || request.tag_ids.iter().any(|tag| !valid_identifier(tag))
    {
        return Err(CreateStreamConfigError::InvalidRequest);
    }
    let mut tags = request.tag_ids.iter().collect::<Vec<_>>();
    tags.sort_unstable();
    if tags.windows(2).any(|pair| pair[0] == pair[1]) {
        return Err(CreateStreamConfigError::InvalidRequest);
    }
    Ok(())
}

fn valid_identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}

fn request_fingerprint(request: &CreateStreamConfigRequest) -> [u8; 32] {
    let mut fingerprint = Sha256::new();
    fingerprint.update(b"streaming.stream-config.create.v1\0");
    for value in std::iter::once(request.channel_id.as_str())
        .chain(std::iter::once(request.title.as_str()))
        .chain(std::iter::once(request.category_id.as_str()))
        .chain(request.tag_ids.iter().map(String::as_str))
    {
        fingerprint.update((value.len() as u64).to_be_bytes());
        fingerprint.update(value.as_bytes());
    }
    fingerprint.update((request.tag_ids.len() as u64).to_be_bytes());
    fingerprint.finalize().into()
}

fn map_owner_error(error: OwnerContextError) -> CreateStreamConfigError {
    match error {
        OwnerContextError::Inactive => CreateStreamConfigError::IdentityInactive,
        OwnerContextError::Forbidden => CreateStreamConfigError::Forbidden,
        OwnerContextError::NotFound => CreateStreamConfigError::ChannelNotFound,
        OwnerContextError::InvalidCatalog => {
            CreateStreamConfigError::TaxonomyValueInactiveOrUnknown
        }
        OwnerContextError::Unavailable => CreateStreamConfigError::IdentityUnavailable,
    }
}

fn map_repository_error(error: StreamConfigRepositoryError) -> CreateStreamConfigError {
    match error {
        StreamConfigRepositoryError::NotFound | StreamConfigRepositoryError::Conflict => {
            CreateStreamConfigError::InvalidStoredData
        }
        StreamConfigRepositoryError::IdempotencyKeyReused => {
            CreateStreamConfigError::IdempotencyKeyReused
        }
        StreamConfigRepositoryError::Unavailable => CreateStreamConfigError::Unavailable,
        StreamConfigRepositoryError::InvalidStoredData => {
            CreateStreamConfigError::InvalidStoredData
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(title: &str) -> CreateStreamConfigRequest {
        CreateStreamConfigRequest {
            channel_id: String::from("channel-1"),
            title: title.to_owned(),
            category_id: String::from("category-1"),
            tag_ids: Vec::new(),
            idempotency_key: Uuid::nil(),
        }
    }

    #[test]
    fn create_rejects_titles_containing_only_whitespace() {
        for title in [" ", "\t", " \t\n\r ", "\u{2003}", "\0", "Title\0suffix"] {
            assert_eq!(
                validate_request(&request(title)),
                Err(CreateStreamConfigError::InvalidRequest)
            );
        }
    }

    #[test]
    fn create_accepts_a_nonblank_title_without_rewriting_it() {
        let request = request("  Evening stream  ");

        assert_eq!(validate_request(&request), Ok(()));
        assert_eq!(request.title, "  Evening stream  ");
    }
}
