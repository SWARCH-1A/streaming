use super::GetPublicStreamSession;
use crate::application::ports::streaming_repository::{RepositoryError, StreamingRepository};
use serde_json::{Value, json};
use std::sync::Arc;
use time::{OffsetDateTime, format_description::well_known::Rfc3339};

pub struct GetChannelSnapshots<R> {
    repository: Arc<R>,
    session: Arc<GetPublicStreamSession<R>>,
}
impl<R: StreamingRepository + 'static> GetChannelSnapshots<R> {
    pub fn new(repository: Arc<R>, session: Arc<GetPublicStreamSession<R>>) -> Self {
        Self {
            repository,
            session,
        }
    }
    pub async fn execute(&self, ids: Vec<String>) -> Result<Value, RepositoryError> {
        if ids.is_empty() || ids.len() > 50 || ids.iter().any(|id| !valid_id(id)) {
            return Err(RepositoryError::Conflict);
        }
        let mut items = Vec::with_capacity(ids.len());
        for id in ids {
            // Recheck version if a lifecycle or metadata commit crosses the composed read.
            let mut result = None;
            for _ in 0..3 {
                let Some(config) = self
                    .repository
                    .find_stream_config_by_channel(id.clone())
                    .await?
                else {
                    result = Some(
                        json!({"channelId":id,"configured":false,"stream":null,"session":null,"observedAtUtc":utc_now()?}),
                    );
                    break;
                };
                let before = self
                    .repository
                    .find_public_stream(config.stream_id.clone())
                    .await?
                    .ok_or(RepositoryError::InvalidStoredData)?;
                let labels = self
                    .repository
                    .stored_catalog_values(config.stream_id.clone())
                    .await?;
                let category = labels
                    .iter()
                    .find(|v| v.id == before.category_id)
                    .ok_or(RepositoryError::InvalidStoredData)?;
                let tags = before
                    .tag_ids
                    .iter()
                    .map(|id| {
                        labels
                            .iter()
                            .find(|v| &v.id == id)
                            .map(|v| json!({"id":v.id,"name":v.name}))
                            .ok_or(RepositoryError::InvalidStoredData)
                    })
                    .collect::<Result<Vec<_>, _>>()?;
                let session = if let Some(state) = &before.session {
                    let view = self
                        .session
                        .execute(state.session_id.as_str().to_owned())
                        .await
                        .map_err(|_| RepositoryError::Unavailable)?;
                    if view.snapshot.session_version != state.session_version
                        || view.snapshot.metadata_version != before.metadata_version
                        || view.snapshot.count_version != state.count_version
                    {
                        continue;
                    }
                    Some(
                        json!({"sessionId":view.snapshot.session_id,"streamId":view.snapshot.stream_id,"channelId":id,
                        "streamGeneration":view.snapshot.stream_generation,"status":view.snapshot.status.as_public_str(),"availability":view.snapshot.availability.as_db_str(),
                        "playbackUrl":view.playback_url,"timelinePositionMs":view.snapshot.timeline_position_ms,"timelineSampledAtUtc":view.snapshot.timeline_sampled_at.map(|v| v.format(&Rfc3339)).transpose().map_err(|_| RepositoryError::InvalidStoredData)?,
                        "metadataVersion":view.snapshot.metadata_version,"sessionVersion":view.snapshot.session_version,"viewerCount":view.snapshot.viewer_count,"countVersion":view.snapshot.count_version,
                        "viewerCountObservedAtUtc":view.snapshot.viewer_count_observed_at.map(|v| v.format(&Rfc3339)).transpose().map_err(|_| RepositoryError::InvalidStoredData)?}),
                    )
                } else {
                    None
                };
                let after = self
                    .repository
                    .find_public_stream(config.stream_id)
                    .await?
                    .ok_or(RepositoryError::InvalidStoredData)?;
                if before.metadata_version != after.metadata_version
                    || before.stream_generation != after.stream_generation
                    || before
                        .session
                        .as_ref()
                        .map(|v| (&v.session_id, v.session_version, v.count_version))
                        != after
                            .session
                            .as_ref()
                            .map(|v| (&v.session_id, v.session_version, v.count_version))
                {
                    continue;
                }
                let state = before.session.as_ref();
                let stream = json!({"streamId":before.stream_id,"channelId":id,"sessionId":state.map(|s| s.session_id.as_str()),"streamGeneration":before.stream_generation,
                    "title":before.title,"category":{"id":category.id,"name":category.name},"tags":tags,"metadataVersion":before.metadata_version,
                    "status":state.map_or("OFFLINE",|s| s.status.as_public_str()),"availability":state.map_or("OFFLINE",|s| s.availability.as_db_str()),"statusFresh":true,
                    "sessionVersion":state.map(|s| s.session_version),"viewerCount":state.map(|s| s.viewer_count),"countVersion":state.map(|s| s.count_version),
                    "viewerCountObservedAtUtc":state.and_then(|s| s.viewer_count_observed_at).map(|v| v.format(&Rfc3339)).transpose().map_err(|_| RepositoryError::InvalidStoredData)?});
                result = Some(
                    json!({"channelId":id,"configured":true,"stream":stream,"session":session,"observedAtUtc":utc_now()?}),
                );
                break;
            }
            items.push(result.ok_or(RepositoryError::Unavailable)?);
        }
        Ok(json!({"items":items}))
    }
}
fn utc_now() -> Result<String, RepositoryError> {
    OffsetDateTime::now_utc()
        .format(&Rfc3339)
        .map_err(|_| RepositoryError::InvalidStoredData)
}
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
}
