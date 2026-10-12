use std::sync::Arc;

use serde::Serialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::{
    application::ports::media_callbacks::{
        AcceptMediaCallbackCommand, MediaCallbackKind, MediaCallbackReceipt,
        MediaCallbackRepository, MediaCallbackRepositoryError,
    },
    domain::ids::{SessionId, StreamId},
};

const MAX_PLAYBACK_PATH_BYTES: usize = 512;

pub struct RecordMediaCallback<R> {
    repository: Arc<R>,
}

impl<R> RecordMediaCallback<R>
where
    R: MediaCallbackRepository + 'static,
{
    pub fn new(repository: Arc<R>) -> Self {
        Self { repository }
    }

    pub async fn execute(
        &self,
        request: RecordMediaCallbackRequest,
    ) -> Result<MediaCallbackReceipt, RecordMediaCallbackError> {
        let stream_id =
            StreamId::parse(request.stream_id).ok_or(RecordMediaCallbackError::InvalidRequest)?;
        let session_id =
            SessionId::parse(request.session_id).ok_or(RecordMediaCallbackError::InvalidRequest)?;
        if request.stream_generation <= 0 || request.source_generation <= 0 {
            return Err(RecordMediaCallbackError::InvalidRequest);
        }
        match (request.kind, request.playback_path.as_deref()) {
            (MediaCallbackKind::PlaybackReady, Some(path))
                if valid_playback_path(path, session_id.as_str()) => {}
            (MediaCallbackKind::PlaybackReady, _) => {
                return Err(RecordMediaCallbackError::InvalidPlaybackPath);
            }
            (_, None) => {}
            (_, Some(_)) => return Err(RecordMediaCallbackError::InvalidRequest),
        }

        let payload = MediaCallbackPayload {
            event_id: request.event_id,
            stream_id: stream_id.as_str(),
            session_id: session_id.as_str(),
            stream_generation: request.stream_generation,
            source_generation: request.source_generation,
            playback_path: request.playback_path.as_deref(),
        };
        let payload = serde_json::to_value(payload)
            .map_err(|_| RecordMediaCallbackError::InvalidStoredData)?;
        let fingerprint = serde_json::to_vec(&json!({
            "eventType": request.kind.as_db_str(),
            "payload": payload.clone(),
        }))
        .map_err(|_| RecordMediaCallbackError::InvalidStoredData)?;
        let payload_hash: [u8; 32] = Sha256::digest(fingerprint).into();

        self.repository
            .accept(AcceptMediaCallbackCommand {
                event_id: request.event_id,
                kind: request.kind,
                stream_id,
                session_id,
                stream_generation: request.stream_generation,
                source_generation: request.source_generation,
                payload_hash,
                payload,
            })
            .await
            .map_err(map_repository_error)
    }
}

pub struct RecordMediaCallbackRequest {
    pub event_id: Uuid,
    pub kind: MediaCallbackKind,
    pub stream_id: String,
    pub session_id: String,
    pub stream_generation: i64,
    pub source_generation: i64,
    pub playback_path: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RecordMediaCallbackError {
    InvalidRequest,
    InvalidPlaybackPath,
    EventIdConflict,
    SessionMismatch,
    SessionEnded,
    Unavailable,
    InvalidStoredData,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MediaCallbackPayload<'a> {
    event_id: Uuid,
    stream_id: &'a str,
    session_id: &'a str,
    stream_generation: i64,
    source_generation: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    playback_path: Option<&'a str>,
}

fn valid_playback_path(path: &str, session_id: &str) -> bool {
    let prefix = format!("/hls/{session_id}/");
    let Some(relative_path) = path.strip_prefix(&prefix) else {
        return false;
    };
    !relative_path.is_empty()
        && relative_path.ends_with(".m3u8")
        && path.len() <= MAX_PLAYBACK_PATH_BYTES
        && !path.contains(['?', '#', '\\', '%', ':'])
        && !path.chars().any(char::is_control)
        && relative_path
            .split('/')
            .all(|segment| !segment.is_empty() && segment != "." && segment != "..")
}

fn map_repository_error(error: MediaCallbackRepositoryError) -> RecordMediaCallbackError {
    match error {
        MediaCallbackRepositoryError::EventIdConflict => RecordMediaCallbackError::EventIdConflict,
        MediaCallbackRepositoryError::SessionMismatch => RecordMediaCallbackError::SessionMismatch,
        MediaCallbackRepositoryError::SessionEnded => RecordMediaCallbackError::SessionEnded,
        MediaCallbackRepositoryError::Unavailable => RecordMediaCallbackError::Unavailable,
        MediaCallbackRepositoryError::InvalidStoredData => {
            RecordMediaCallbackError::InvalidStoredData
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[derive(Default)]
    struct CountingRepository {
        accepted_callbacks: AtomicUsize,
    }

    impl MediaCallbackRepository for CountingRepository {
        fn accept(
            &self,
            _command: AcceptMediaCallbackCommand,
        ) -> impl std::future::Future<
            Output = Result<MediaCallbackReceipt, MediaCallbackRepositoryError>,
        > + Send {
            async move {
                self.accepted_callbacks.fetch_add(1, Ordering::Relaxed);
                Ok(MediaCallbackReceipt::Accepted)
            }
        }
    }

    fn request(
        kind: MediaCallbackKind,
        stream_id: &StreamId,
        session_id: &SessionId,
        playback_path: Option<String>,
    ) -> RecordMediaCallbackRequest {
        RecordMediaCallbackRequest {
            event_id: Uuid::from_u128(1),
            kind,
            stream_id: stream_id.as_str().to_owned(),
            session_id: session_id.as_str().to_owned(),
            stream_generation: 1,
            source_generation: 1,
            playback_path,
        }
    }

    #[test]
    fn playback_path_must_be_under_the_matching_session_and_end_in_playlist() {
        let session_id = SessionId::new();
        let prefix = format!("/hls/{}/", session_id.as_str());

        assert!(valid_playback_path(
            &format!("{prefix}index.m3u8"),
            session_id.as_str()
        ));
        assert!(valid_playback_path(
            &format!("{prefix}renditions/720p.m3u8"),
            session_id.as_str()
        ));
        assert!(!valid_playback_path(
            &format!("{prefix}segment.ts"),
            session_id.as_str()
        ));
        assert!(!valid_playback_path(
            "/hls/ses_550e8400-e29b-41d4-a716-446655440000/index.m3u8",
            session_id.as_str()
        ));
    }

    #[test]
    fn playback_path_rejects_traversal_and_url_components() {
        let session_id = SessionId::new();
        let prefix = format!("/hls/{}/", session_id.as_str());
        let invalid_paths = [
            format!("{prefix}../private/index.m3u8"),
            format!("{prefix}./index.m3u8"),
            format!("{prefix}nested//index.m3u8"),
            format!("{prefix}index.m3u8?token=x"),
            format!("{prefix}index.m3u8#fragment"),
            format!("{prefix}nested\\index.m3u8"),
            format!("{prefix}%2e%2e/index.m3u8"),
            format!("{prefix}nested/index.m3u8\n"),
        ];

        for path in invalid_paths {
            assert!(!valid_playback_path(&path, session_id.as_str()), "{path}");
        }
    }

    #[test]
    fn playback_path_has_a_bounded_size() {
        let session_id = SessionId::new();
        let path = format!("/hls/{}/{}.m3u8", session_id.as_str(), "x".repeat(512));

        assert!(!valid_playback_path(&path, session_id.as_str()));
    }

    #[tokio::test]
    async fn invalid_callback_is_rejected_before_repository_access() {
        let repository = Arc::new(CountingRepository::default());
        let use_case = RecordMediaCallback::new(Arc::clone(&repository));
        let stream_id = StreamId::new();
        let session_id = SessionId::new();
        let mut invalid = request(
            MediaCallbackKind::SourceConnected,
            &stream_id,
            &session_id,
            None,
        );
        invalid.stream_generation = 0;

        let result = use_case.execute(invalid).await;

        assert_eq!(result, Err(RecordMediaCallbackError::InvalidRequest));
        assert_eq!(repository.accepted_callbacks.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn valid_playback_callback_is_forwarded_to_repository() {
        let repository = Arc::new(CountingRepository::default());
        let use_case = RecordMediaCallback::new(Arc::clone(&repository));
        let stream_id = StreamId::new();
        let session_id = SessionId::new();
        let path = format!("/hls/{}/index.m3u8", session_id.as_str());

        let result = use_case
            .execute(request(
                MediaCallbackKind::PlaybackReady,
                &stream_id,
                &session_id,
                Some(path),
            ))
            .await;

        assert_eq!(result, Ok(MediaCallbackReceipt::Accepted));
        assert_eq!(repository.accepted_callbacks.load(Ordering::Relaxed), 1);
    }
}
