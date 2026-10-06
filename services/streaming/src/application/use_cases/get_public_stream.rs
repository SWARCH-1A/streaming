use std::{collections::HashSet, sync::Arc};

use crate::{
    application::ports::{
        streaming_repository::{PublicStreamSnapshot, RepositoryError, StreamingRepository},
        taxonomy::{TaxonomyGateway, TaxonomyValue},
    },
    domain::{
        ids::StreamId,
        session::{Availability, SessionStatus},
    },
};

#[derive(Clone, Debug)]
pub struct PublicStreamView {
    pub snapshot: PublicStreamSnapshot,
    pub category: TaxonomyValue,
    pub tags: Vec<TaxonomyValue>,
}

pub struct GetPublicStream<R, T> {
    repository: Arc<R>,
    taxonomy: Arc<T>,
}

impl<R, T> GetPublicStream<R, T>
where
    R: StreamingRepository + 'static,
    T: TaxonomyGateway + 'static,
{
    pub fn new(repository: Arc<R>, taxonomy: Arc<T>) -> Self {
        Self {
            repository,
            taxonomy,
        }
    }

    pub async fn execute(
        &self,
        stream_id: String,
    ) -> Result<PublicStreamView, GetPublicStreamError> {
        let stream_id = StreamId::parse(stream_id).ok_or(GetPublicStreamError::NotFound)?;
        let snapshot = self
            .repository
            .find_public_stream(stream_id.clone())
            .await
            .map_err(map_repository_error)?
            .ok_or(GetPublicStreamError::NotFound)?;

        if snapshot.stream_id != stream_id
            || snapshot.metadata_version < 1
            || snapshot.stream_generation < 0
            || snapshot.title.trim().is_empty()
            || !valid_identifier(&snapshot.category_id)
            || snapshot.tag_ids.len() > 5
        {
            return Err(GetPublicStreamError::InvalidStoredData);
        }
        if let Some(session) = snapshot.session.as_ref() {
            if snapshot.stream_generation < 1
                || session.session_version < 1
                || session.viewer_count < 0
                || session.count_version < 0
                || !state_is_consistent(session.status, session.availability)
            {
                return Err(GetPublicStreamError::InvalidStoredData);
            }
        } else if snapshot.stream_generation != 0 {
            return Err(GetPublicStreamError::InvalidStoredData);
        }

        let mut expected_ids = Vec::with_capacity(snapshot.tag_ids.len() + 1);
        expected_ids.push(snapshot.category_id.clone());
        expected_ids.extend(snapshot.tag_ids.iter().cloned());
        let mut unique_ids = HashSet::with_capacity(expected_ids.len());
        if expected_ids
            .iter()
            .any(|id| !valid_identifier(id) || !unique_ids.insert(id.as_str()))
        {
            return Err(GetPublicStreamError::InvalidStoredData);
        }

        let (values, resolved) = match self.taxonomy.resolve_values(expected_ids.clone()).await {
            Ok(values) => (values, true),
            Err(_) => (
                self.repository
                    .stored_catalog_values(stream_id.clone())
                    .await
                    .map_err(map_repository_error)?,
                false,
            ),
        };
        if values.len() != expected_ids.len()
            || values
                .iter()
                .zip(&expected_ids)
                .any(|(value, expected_id)| {
                    value.id != *expected_id
                        || value.name.trim().is_empty()
                        || value.name.chars().any(char::is_control)
                })
        {
            return Err(GetPublicStreamError::TaxonomyUnavailable);
        }

        if resolved {
            self.repository
                .remember_catalog_values(stream_id, snapshot.metadata_version, values.clone())
                .await
                .map_err(map_repository_error)?;
        }
        let mut values = values.into_iter();
        let category = values
            .next()
            .ok_or(GetPublicStreamError::TaxonomyUnavailable)?;
        let tags = values.collect();
        Ok(PublicStreamView {
            snapshot,
            category,
            tags,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GetPublicStreamError {
    NotFound,
    Unavailable,
    TaxonomyUnavailable,
    InvalidStoredData,
}

fn valid_identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}

fn state_is_consistent(status: SessionStatus, availability: Availability) -> bool {
    matches!(
        (status, availability),
        (
            SessionStatus::Preparing | SessionStatus::Ended,
            Availability::Offline
        ) | (SessionStatus::Live, Availability::Playable)
            | (SessionStatus::ReconnectGrace, Availability::Reconnecting)
    )
}

fn map_repository_error(error: RepositoryError) -> GetPublicStreamError {
    match error {
        RepositoryError::NotFound => GetPublicStreamError::NotFound,
        RepositoryError::Conflict | RepositoryError::InvalidStoredData => {
            GetPublicStreamError::InvalidStoredData
        }
        RepositoryError::Unavailable => GetPublicStreamError::Unavailable,
    }
}
