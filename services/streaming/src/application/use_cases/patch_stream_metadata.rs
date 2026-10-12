use std::sync::Arc;

use crate::{
    application::ports::{
        identity::SessionCredential,
        owner_context::{
            OwnerContextError, OwnerContextGateway, OwnerContextRequest, OwnerOperation,
        },
        stream_config::{
            PatchStreamMetadataCommand, StreamConfigRepository, StreamConfigRepositoryError,
        },
        streaming_repository::{RepositoryError, StreamingRepository},
    },
    domain::ids::StreamId,
};

pub struct PatchStreamMetadataRequest {
    pub stream_id: String,
    pub title: Option<String>,
    pub category_id: Option<String>,
    pub tag_ids: Option<Vec<String>>,
}

pub struct PatchedStreamMetadata {
    pub stream_id: StreamId,
    pub channel_id: String,
    pub title: String,
    pub category_id: String,
    pub tag_ids: Vec<String>,
    pub metadata_version: i64,
    pub changed_fields: Vec<String>,
}

pub struct PatchStreamMetadata<I, R, Q> {
    identity: Arc<I>,
    write_repository: Arc<R>,
    read_repository: Arc<Q>,
}

impl<I, R, Q> PatchStreamMetadata<I, R, Q>
where
    I: OwnerContextGateway + 'static,
    R: StreamConfigRepository + 'static,
    Q: StreamingRepository + 'static,
{
    pub fn new(identity: Arc<I>, write_repository: Arc<R>, read_repository: Arc<Q>) -> Self {
        Self {
            identity,
            write_repository,
            read_repository,
        }
    }

    pub async fn execute(
        &self,
        credential: SessionCredential,
        request: PatchStreamMetadataRequest,
    ) -> Result<PatchedStreamMetadata, PatchStreamMetadataError> {
        let stream_id =
            StreamId::parse(request.stream_id.clone()).ok_or(PatchStreamMetadataError::NotFound)?;
        if request.title.is_none() && request.category_id.is_none() && request.tag_ids.is_none() {
            return Err(PatchStreamMetadataError::EmptyPatch);
        }
        validate_patch(&request)?;
        let config = self
            .read_repository
            .find_stream_config(stream_id.clone())
            .await
            .map_err(map_read_error)?
            .ok_or(PatchStreamMetadataError::NotFound)?;
        let context = self
            .identity
            .authorize(
                credential,
                OwnerContextRequest {
                    command_id: uuid::Uuid::new_v4(),
                    operation: OwnerOperation::PatchMetadata,
                    channel_id: config.channel_id.clone(),
                    category_id: request.category_id.clone(),
                    tag_ids: request.tag_ids.clone(),
                },
            )
            .await
            .map_err(map_owner_error)?;
        if config.owner_user_id != context.user_id {
            return Err(PatchStreamMetadataError::Forbidden);
        }
        let category_label = context.category;
        let tag_labels = context.tags;
        let result = self
            .write_repository
            .patch_metadata(PatchStreamMetadataCommand {
                authorization_expires_at: Some(context.expires_at),
                category_label,
                tag_labels,
                stream_id,
                title: request.title,
                category_id: request.category_id,
                tag_ids: request.tag_ids,
            })
            .await
            .map_err(map_write_error)?;
        if result.config.owner_user_id != context.user_id {
            return Err(PatchStreamMetadataError::Forbidden);
        }
        Ok(PatchedStreamMetadata {
            stream_id: result.config.stream_id,
            channel_id: result.config.channel_id,
            title: result.config.title,
            category_id: result.config.category_id,
            tag_ids: result.config.tag_ids,
            metadata_version: result.config.metadata_version,
            changed_fields: result.changed_fields,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PatchStreamMetadataError {
    EmptyPatch,
    InvalidRequest,
    IdentityInactive,
    IdentityUnavailable,
    NotFound,
    Forbidden,
    TaxonomyValueInactiveOrUnknown,
    TaxonomyUnavailable,
    StreamTransitionInProgress,
    Unavailable,
    InvalidStoredData,
}

fn validate_patch(request: &PatchStreamMetadataRequest) -> Result<(), PatchStreamMetadataError> {
    if request.title.as_ref().is_some_and(|title| {
        let length = title.chars().count();
        title.contains('\0') || title.trim().is_empty() || !(1..=100).contains(&length)
    }) || request
        .category_id
        .as_ref()
        .is_some_and(|id| !valid_identifier(id))
        || request.tag_ids.as_ref().is_some_and(|tag_ids| {
            tag_ids.len() > 5 || tag_ids.iter().any(|tag_id| !valid_identifier(tag_id)) || {
                let mut ordered = tag_ids.iter().collect::<Vec<_>>();
                ordered.sort_unstable();
                ordered.windows(2).any(|pair| pair[0] == pair[1])
            }
        })
    {
        return Err(PatchStreamMetadataError::InvalidRequest);
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

fn map_owner_error(error: OwnerContextError) -> PatchStreamMetadataError {
    match error {
        OwnerContextError::Inactive => PatchStreamMetadataError::IdentityInactive,
        OwnerContextError::Forbidden => PatchStreamMetadataError::Forbidden,
        OwnerContextError::NotFound => PatchStreamMetadataError::NotFound,
        OwnerContextError::InvalidCatalog => {
            PatchStreamMetadataError::TaxonomyValueInactiveOrUnknown
        }
        OwnerContextError::Unavailable => PatchStreamMetadataError::IdentityUnavailable,
    }
}

fn map_read_error(error: RepositoryError) -> PatchStreamMetadataError {
    match error {
        RepositoryError::NotFound => PatchStreamMetadataError::NotFound,
        RepositoryError::Conflict => PatchStreamMetadataError::Forbidden,
        RepositoryError::Unavailable => PatchStreamMetadataError::Unavailable,
        RepositoryError::InvalidStoredData => PatchStreamMetadataError::InvalidStoredData,
    }
}

fn map_write_error(error: StreamConfigRepositoryError) -> PatchStreamMetadataError {
    match error {
        StreamConfigRepositoryError::NotFound => PatchStreamMetadataError::NotFound,
        StreamConfigRepositoryError::Conflict => {
            PatchStreamMetadataError::StreamTransitionInProgress
        }
        StreamConfigRepositoryError::IdempotencyKeyReused => PatchStreamMetadataError::Unavailable,
        StreamConfigRepositoryError::Unavailable => PatchStreamMetadataError::Unavailable,
        StreamConfigRepositoryError::InvalidStoredData => {
            PatchStreamMetadataError::InvalidStoredData
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(title: &str) -> PatchStreamMetadataRequest {
        PatchStreamMetadataRequest {
            stream_id: String::from("str_550e8400-e29b-41d4-a716-446655440000"),
            title: Some(title.to_owned()),
            category_id: None,
            tag_ids: None,
        }
    }

    #[test]
    fn patch_rejects_titles_containing_only_whitespace() {
        for title in [" ", "\t", " \t\n\r ", "\u{2003}", "\0", "Title\0suffix"] {
            assert_eq!(
                validate_patch(&request(title)),
                Err(PatchStreamMetadataError::InvalidRequest)
            );
        }
    }

    #[test]
    fn patch_accepts_a_nonblank_title_without_rewriting_it() {
        let request = request("  Evening stream  ");

        assert_eq!(validate_patch(&request), Ok(()));
        assert_eq!(request.title.as_deref(), Some("  Evening stream  "));
    }
}
