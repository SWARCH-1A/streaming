use std::future::Future;

use time::OffsetDateTime;

use crate::domain::{
    ids::{SessionId, StreamId},
    session::{Availability, SessionStatus, StreamSession},
};

#[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamConfigSnapshot {
    pub stream_id: StreamId,
    pub channel_id: String,
    pub owner_user_id: String,
    pub title: String,
    pub category_id: String,
    pub tag_ids: Vec<String>,
    pub metadata_version: i64,
    pub stream_generation: i64,
}

#[derive(Clone, Debug)]
pub struct PublicStreamSessionSnapshot {
    pub stream_id: StreamId,
    pub session_id: SessionId,
    pub channel_id: String,
    pub title: String,
    pub category_id: String,
    pub tag_ids: Vec<String>,
    pub metadata_version: i64,
    pub stream_generation: i64,
    pub status: SessionStatus,
    pub availability: Availability,
    pub session_version: i64,
    pub playback_path: Option<String>,
    pub timeline_position_ms: i64,
    pub timeline_sampled_at: Option<OffsetDateTime>,
    pub viewer_count: i64,
    pub count_version: i64,
    pub viewer_count_observed_at: Option<OffsetDateTime>,
}

#[derive(Clone, Debug)]
pub struct PublicStreamSnapshot {
    pub stream_id: StreamId,
    pub channel_id: String,
    pub title: String,
    pub category_id: String,
    pub tag_ids: Vec<String>,
    pub metadata_version: i64,
    pub stream_generation: i64,
    pub session: Option<PublicStreamState>,
}

#[derive(Clone, Debug)]
pub struct PublicStreamState {
    pub session_id: SessionId,
    pub status: SessionStatus,
    pub availability: Availability,
    pub session_version: i64,
    pub viewer_count: i64,
    pub count_version: i64,
    pub viewer_count_observed_at: Option<OffsetDateTime>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RepositoryError {
    NotFound,
    Conflict,
    Unavailable,
    InvalidStoredData,
}

pub trait StreamingRepository: Send + Sync {
    fn remember_catalog_values(
        &self,
        _stream_id: StreamId,
        _metadata_version: i64,
        _values: Vec<super::taxonomy::TaxonomyValue>,
    ) -> impl Future<Output = Result<(), RepositoryError>> + Send {
        async { Ok(()) }
    }

    fn stored_catalog_values(
        &self,
        _stream_id: StreamId,
    ) -> impl Future<Output = Result<Vec<super::taxonomy::TaxonomyValue>, RepositoryError>> + Send
    {
        async { Err(RepositoryError::Unavailable) }
    }

    fn find_stream_config(
        &self,
        stream_id: StreamId,
    ) -> impl Future<Output = Result<Option<StreamConfigSnapshot>, RepositoryError>> + Send;

    fn find_stream_config_by_channel(
        &self,
        channel_id: String,
    ) -> impl Future<Output = Result<Option<StreamConfigSnapshot>, RepositoryError>> + Send;

    fn find_session(
        &self,
        session_id: SessionId,
    ) -> impl Future<Output = Result<Option<StreamSession>, RepositoryError>> + Send;

    fn find_public_session(
        &self,
        session_id: SessionId,
    ) -> impl Future<Output = Result<Option<PublicStreamSessionSnapshot>, RepositoryError>> + Send;

    fn find_public_stream(
        &self,
        stream_id: StreamId,
    ) -> impl Future<Output = Result<Option<PublicStreamSnapshot>, RepositoryError>> + Send;

    fn find_active_session_for_stream(
        &self,
        stream_id: StreamId,
    ) -> impl Future<Output = Result<Option<StreamSession>, RepositoryError>> + Send;

    fn stop_session(
        &self,
        session_id: SessionId,
        timeline_position_ms: Option<i64>,
        authorization_expires_at: Option<std::time::Instant>,
    ) -> impl Future<Output = Result<StreamSession, RepositoryError>> + Send;
}
