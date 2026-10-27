use std::sync::Arc;

use crate::{
    application::ports::{
        session_clock::SessionMonotonicClock,
        streaming_repository::{PublicStreamSessionSnapshot, RepositoryError, StreamingRepository},
    },
    domain::{
        ids::SessionId,
        session::{Availability, SessionStatus},
    },
};

#[derive(Clone, Debug)]
pub struct PublicStreamSessionView {
    pub snapshot: PublicStreamSessionSnapshot,
    pub playback_url: Option<String>,
}

pub struct GetPublicStreamSession<R> {
    repository: Arc<R>,
    public_hls_base_url: Option<String>,
    session_clock: Arc<dyn SessionMonotonicClock>,
}

impl<R> GetPublicStreamSession<R>
where
    R: StreamingRepository + 'static,
{
    pub fn new(
        repository: Arc<R>,
        public_hls_base_url: Option<String>,
        session_clock: Arc<dyn SessionMonotonicClock>,
    ) -> Self {
        Self {
            repository,
            public_hls_base_url,
            session_clock,
        }
    }

    pub async fn execute(
        &self,
        session_id: String,
    ) -> Result<PublicStreamSessionView, GetPublicStreamSessionError> {
        let session_id =
            SessionId::parse(session_id).ok_or(GetPublicStreamSessionError::NotFound)?;
        let mut snapshot = self
            .repository
            .find_public_session(session_id.clone())
            .await
            .map_err(map_repository_error)?
            .ok_or(GetPublicStreamSessionError::NotFound)?;

        if snapshot.session_id != session_id
            || snapshot.stream_generation < 1
            || snapshot.metadata_version < 1
            || snapshot.session_version < 1
            || snapshot.timeline_position_ms < 0
            || snapshot.viewer_count < 0
            || snapshot.count_version < 0
            || !state_is_consistent(snapshot.status, snapshot.availability)
        {
            return Err(GetPublicStreamSessionError::InvalidStoredData);
        }

        if matches!(
            snapshot.status,
            SessionStatus::Live | SessionStatus::ReconnectGrace
        ) {
            let elapsed = self
                .session_clock
                .timeline_elapsed(&session_id)
                .ok_or(GetPublicStreamSessionError::Unavailable)?;
            let elapsed_ms = i64::try_from(elapsed.as_millis())
                .map_err(|_| GetPublicStreamSessionError::InvalidStoredData)?;
            snapshot.timeline_position_ms = snapshot.timeline_position_ms.max(elapsed_ms);
            snapshot.timeline_sampled_at = Some(time::OffsetDateTime::now_utc());
        }

        if snapshot.status == SessionStatus::Preparing
            && self
                .session_clock
                .preparing_elapsed(&session_id)
                .is_none_or(|elapsed| elapsed >= std::time::Duration::from_secs(30))
        {
            return Err(GetPublicStreamSessionError::Unavailable);
        }
        if snapshot.status == SessionStatus::ReconnectGrace
            && self
                .session_clock
                .reconnect_elapsed(&session_id)
                .is_none_or(|elapsed| elapsed >= std::time::Duration::from_secs(30))
        {
            return Err(GetPublicStreamSessionError::Unavailable);
        }
        let playback_url = match (snapshot.availability, snapshot.playback_path.as_deref()) {
            (Availability::Playable, Some(path)) if valid_playback_path(path, &session_id) => {
                let base_url = self
                    .public_hls_base_url
                    .as_deref()
                    .ok_or(GetPublicStreamSessionError::Unavailable)?
                    .trim_end_matches('/');
                Some(format!("{base_url}{path}"))
            }
            (Availability::Playable, _) => {
                return Err(GetPublicStreamSessionError::InvalidStoredData);
            }
            (_, None) => None,
            (_, Some(_)) => return Err(GetPublicStreamSessionError::InvalidStoredData),
        };

        Ok(PublicStreamSessionView {
            snapshot,
            playback_url,
        })
    }

    pub async fn renditions<M: crate::application::ports::renditions::RenditionsGateway>(
        &self,
        session_id: String,
        media: &M,
    ) -> Result<PublicRenditionsView, GetPublicStreamSessionError> {
        let before = self.execute(session_id.clone()).await?;
        let path = before
            .snapshot
            .playback_path
            .ok_or(GetPublicStreamSessionError::Unavailable)?;
        let renditions = media
            .available_renditions(path.clone())
            .await
            .map_err(|_| GetPublicStreamSessionError::Unavailable)?;
        // Do not publish a response observed before stop/reconnect or a source replacement.
        let after = self.execute(session_id).await?;
        if before.snapshot.session_version != after.snapshot.session_version
            || after.snapshot.playback_path.as_deref() != Some(path.as_str())
            || renditions.is_empty()
            || renditions.len() > crate::domain::rendition::MAX_RENDITIONS
            || renditions
                .iter()
                .map(|r| &r.rendition_id)
                .collect::<std::collections::HashSet<_>>()
                .len()
                != renditions.len()
        {
            return Err(GetPublicStreamSessionError::Unavailable);
        }
        Ok(PublicRenditionsView {
            session_id: after.snapshot.session_id,
            renditions,
            master_playlist_uri: path,
        })
    }
}

#[derive(Clone, Debug)]
pub struct PublicRenditionsView {
    pub session_id: SessionId,
    pub renditions: Vec<crate::domain::rendition::Rendition>,
    pub master_playlist_uri: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GetPublicStreamSessionError {
    NotFound,
    Unavailable,
    InvalidStoredData,
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

fn valid_playback_path(path: &str, session_id: &SessionId) -> bool {
    let prefix = format!("/hls/{}/", session_id.as_str());
    let Some(relative_path) = path.strip_prefix(&prefix) else {
        return false;
    };
    !relative_path.is_empty()
        && relative_path.ends_with(".m3u8")
        && !path.contains(['?', '#', '\\', '%', ':'])
        && !path.chars().any(char::is_control)
        && relative_path
            .split('/')
            .all(|segment| !segment.is_empty() && segment != "." && segment != "..")
}

fn map_repository_error(error: RepositoryError) -> GetPublicStreamSessionError {
    match error {
        RepositoryError::NotFound => GetPublicStreamSessionError::NotFound,
        RepositoryError::Conflict | RepositoryError::InvalidStoredData => {
            GetPublicStreamSessionError::InvalidStoredData
        }
        RepositoryError::Unavailable => GetPublicStreamSessionError::Unavailable,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::future::{Future, ready};

    use crate::{
        application::ports::streaming_repository::{PublicStreamSnapshot, StreamConfigSnapshot},
        domain::{ids::StreamId, session::StreamSession},
    };

    struct SessionRepository {
        snapshot: Option<PublicStreamSessionSnapshot>,
    }

    impl StreamingRepository for SessionRepository {
        fn find_stream_config(
            &self,
            _stream_id: StreamId,
        ) -> impl Future<Output = Result<Option<StreamConfigSnapshot>, RepositoryError>> + Send
        {
            ready(Ok(None))
        }

        fn find_stream_config_by_channel(
            &self,
            _channel_id: String,
        ) -> impl Future<Output = Result<Option<StreamConfigSnapshot>, RepositoryError>> + Send
        {
            ready(Ok(None))
        }

        fn find_session(
            &self,
            _session_id: SessionId,
        ) -> impl Future<Output = Result<Option<StreamSession>, RepositoryError>> + Send {
            ready(Ok(None))
        }

        fn find_public_session(
            &self,
            session_id: SessionId,
        ) -> impl Future<Output = Result<Option<PublicStreamSessionSnapshot>, RepositoryError>> + Send
        {
            let snapshot = self
                .snapshot
                .clone()
                .filter(|snapshot| snapshot.session_id == session_id);
            ready(Ok(snapshot))
        }

        fn find_public_stream(
            &self,
            _stream_id: StreamId,
        ) -> impl Future<Output = Result<Option<PublicStreamSnapshot>, RepositoryError>> + Send
        {
            ready(Ok(None))
        }

        fn find_active_session_for_stream(
            &self,
            _stream_id: StreamId,
        ) -> impl Future<Output = Result<Option<StreamSession>, RepositoryError>> + Send {
            ready(Ok(None))
        }

        fn stop_session(
            &self,
            _session_id: SessionId,
            _timeline_position_ms: Option<i64>,
            _authorization_expires_at: Option<std::time::Instant>,
        ) -> impl Future<Output = Result<StreamSession, RepositoryError>> + Send {
            ready(Err(RepositoryError::NotFound))
        }
    }

    struct FixedClock(Option<std::time::Duration>);
    impl SessionMonotonicClock for FixedClock {
        fn start_preparing(&self, _: &SessionId) {}
        fn mark_live(&self, _: &SessionId) -> bool {
            self.0.is_some()
        }
        fn start_reconnect_grace(&self, _: &SessionId, _: i64, _: std::time::Duration) -> bool {
            self.0.is_some()
        }
        fn preparing_elapsed(&self, _: &SessionId) -> Option<std::time::Duration> {
            self.0
        }
        fn reconnect_elapsed(&self, _: &SessionId) -> Option<std::time::Duration> {
            self.0
        }
        fn timeline_elapsed(&self, _: &SessionId) -> Option<std::time::Duration> {
            self.0
        }
        fn forget(&self, _: &SessionId) {}
    }
    fn clock_for(_: &SessionId) -> Arc<dyn SessionMonotonicClock> {
        Arc::new(FixedClock(Some(std::time::Duration::from_secs(29))))
    }

    fn snapshot(
        session_id: SessionId,
        status: SessionStatus,
        availability: Availability,
        playback_path: Option<String>,
    ) -> PublicStreamSessionSnapshot {
        PublicStreamSessionSnapshot {
            stream_id: StreamId::new(),
            session_id,
            channel_id: String::from("channel-1"),
            title: String::from("Test stream"),
            category_id: String::from("category-1"),
            tag_ids: Vec::new(),
            metadata_version: 1,
            stream_generation: 1,
            status,
            availability,
            session_version: 1,
            playback_path,
            timeline_position_ms: 0,
            timeline_sampled_at: None,
            viewer_count: 0,
            count_version: 0,
            viewer_count_observed_at: None,
        }
    }

    #[test]
    fn only_valid_session_state_and_availability_pairs_are_public() {
        let valid_pairs = [
            (SessionStatus::Preparing, Availability::Offline),
            (SessionStatus::Live, Availability::Playable),
            (SessionStatus::ReconnectGrace, Availability::Reconnecting),
            (SessionStatus::Ended, Availability::Offline),
        ];
        let invalid_pairs = [
            (SessionStatus::Preparing, Availability::Playable),
            (SessionStatus::Live, Availability::Offline),
            (SessionStatus::ReconnectGrace, Availability::Playable),
            (SessionStatus::Ended, Availability::Reconnecting),
        ];

        for (status, availability) in valid_pairs {
            assert!(state_is_consistent(status, availability));
        }
        for (status, availability) in invalid_pairs {
            assert!(!state_is_consistent(status, availability));
        }
    }

    #[test]
    fn public_playback_path_rejects_wrong_session_and_path_traversal() {
        let session_id = SessionId::new();
        let valid_path = format!("/hls/{}/index.m3u8", session_id.as_str());
        let wrong_session_path =
            String::from("/hls/ses_550e8400-e29b-41d4-a716-446655440000/index.m3u8");
        let traversal_path = format!("/hls/{}/../index.m3u8", session_id.as_str());

        assert!(valid_playback_path(&valid_path, &session_id));
        assert!(!valid_playback_path(&wrong_session_path, &session_id));
        assert!(!valid_playback_path(&traversal_path, &session_id));
    }

    #[tokio::test]
    async fn playable_session_builds_public_url_without_duplicate_slash() {
        let session_id = SessionId::new();
        let path = format!("/hls/{}/index.m3u8", session_id.as_str());
        let base_url = String::from("https://cdn.example.test/hls/");
        let repository = Arc::new(SessionRepository {
            snapshot: Some(snapshot(
                session_id.clone(),
                SessionStatus::Live,
                Availability::Playable,
                Some(path.clone()),
            )),
        });
        let use_case =
            GetPublicStreamSession::new(repository, Some(base_url.clone()), clock_for(&session_id));

        let result = use_case.execute(session_id.as_str().to_owned()).await;

        assert!(result.is_ok());
        assert_eq!(
            result
                .as_ref()
                .ok()
                .map(|view| view.snapshot.timeline_position_ms),
            Some(29_000)
        );
        assert_eq!(
            result
                .as_ref()
                .ok()
                .and_then(|view| view.playback_url.as_deref()),
            Some(format!("{}{path}", base_url.trim_end_matches('/')).as_str())
        );
    }

    #[tokio::test]
    async fn reconnecting_session_does_not_expose_a_playback_url() {
        let session_id = SessionId::new();
        let repository = Arc::new(SessionRepository {
            snapshot: Some(snapshot(
                session_id.clone(),
                SessionStatus::ReconnectGrace,
                Availability::Reconnecting,
                None,
            )),
        });
        let use_case = GetPublicStreamSession::new(
            repository,
            Some(String::from("https://cdn.example.test")),
            clock_for(&session_id),
        );

        let result = use_case.execute(session_id.as_str().to_owned()).await;

        assert!(result.is_ok());
        assert_eq!(
            result
                .as_ref()
                .ok()
                .map(|view| view.snapshot.timeline_position_ms),
            Some(29_000)
        );
        assert_eq!(
            result
                .as_ref()
                .ok()
                .and_then(|view| view.playback_url.as_deref()),
            None
        );
    }
    #[tokio::test]
    async fn a_live_session_without_its_clock_is_unavailable() {
        let session_id = SessionId::new();
        let repository = Arc::new(SessionRepository {
            snapshot: Some(snapshot(
                session_id.clone(),
                SessionStatus::Live,
                Availability::Playable,
                Some(format!("/hls/{}/index.m3u8", session_id.as_str())),
            )),
        });
        let use_case = GetPublicStreamSession::new(
            repository,
            Some("https://media.example.test".into()),
            Arc::new(FixedClock(None)),
        );
        assert!(matches!(
            use_case.execute(session_id.as_str().into()).await,
            Err(GetPublicStreamSessionError::Unavailable)
        ));
    }
    #[tokio::test]
    async fn an_ended_session_keeps_its_frozen_timeline_without_a_clock() {
        let session_id = SessionId::new();
        let mut ended = snapshot(
            session_id.clone(),
            SessionStatus::Ended,
            Availability::Offline,
            None,
        );
        ended.timeline_position_ms = 42_000;
        let repository = Arc::new(SessionRepository {
            snapshot: Some(ended),
        });
        let use_case = GetPublicStreamSession::new(repository, None, Arc::new(FixedClock(None)));
        let result = use_case.execute(session_id.as_str().into()).await;
        assert!(result.is_ok());
        assert_eq!(
            result.ok().map(|view| view.snapshot.timeline_position_ms),
            Some(42_000)
        );
    }

    struct MediaRenditions {
        result: Result<
            Vec<crate::domain::rendition::Rendition>,
            crate::application::ports::media_server::MediaServerError,
        >,
        calls: std::sync::atomic::AtomicUsize,
        delay: std::time::Duration,
    }

    impl crate::application::ports::renditions::RenditionsGateway for MediaRenditions {
        async fn available_renditions(
            &self,
            _: String,
        ) -> Result<
            Vec<crate::domain::rendition::Rendition>,
            crate::application::ports::media_server::MediaServerError,
        > {
            self.calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            if !self.delay.is_zero() {
                tokio::time::sleep(self.delay).await;
            }
            self.result.clone()
        }
    }

    fn media_renditions() -> MediaRenditions {
        MediaRenditions {
            result: Ok(vec![crate::domain::rendition::Rendition {
                rendition_id: "rend_720".into(),
                width: 1280,
                height: 720,
                bitrate_bps: 2_500_000,
                video_codec: "avc1.64001f".into(),
                audio_codec: Some("mp4a.40.2".into()),
            }]),
            calls: std::sync::atomic::AtomicUsize::new(0),
            delay: std::time::Duration::ZERO,
        }
    }

    fn rendition_session(
        session_id: &SessionId,
        status: SessionStatus,
        availability: Availability,
    ) -> GetPublicStreamSession<SessionRepository> {
        GetPublicStreamSession::new(
            Arc::new(SessionRepository {
                snapshot: Some(snapshot(
                    session_id.clone(),
                    status,
                    availability,
                    (availability == Availability::Playable)
                        .then(|| format!("/hls/{}/index.m3u8", session_id.as_str())),
                )),
            }),
            Some("https://media.test".into()),
            clock_for(session_id),
        )
    }

    #[tokio::test]
    async fn renditions_do_not_query_media_for_offline_or_reconnecting_sessions() {
        let id = SessionId::new();
        let media = media_renditions();
        for (status, availability) in [
            (SessionStatus::Preparing, Availability::Offline),
            (SessionStatus::ReconnectGrace, Availability::Reconnecting),
            (SessionStatus::Ended, Availability::Offline),
        ] {
            let session = rendition_session(&id, status, availability);
            assert!(matches!(
                session.renditions(id.as_str().into(), &media).await,
                Err(GetPublicStreamSessionError::Unavailable)
            ));
        }
        assert_eq!(media.calls.load(std::sync::atomic::Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn rendition_errors_never_become_a_successful_empty_or_truncated_list() {
        let id = SessionId::new();
        let session = rendition_session(&id, SessionStatus::Live, Availability::Playable);
        let mut media = media_renditions();
        let original = media.result.clone().unwrap_or_default();
        for result in [
            Ok(vec![]),
            Ok(original.iter().cycle().take(2).cloned().collect()),
            Ok(original.iter().cycle().take(5).cloned().collect()),
            Err(crate::application::ports::media_server::MediaServerError::ManifestUnavailable),
        ] {
            media.result = result;
            assert!(matches!(
                session.renditions(id.as_str().into(), &media).await,
                Err(GetPublicStreamSessionError::Unavailable)
            ));
        }
        assert!(matches!(
            session.renditions("invalid".into(), &media).await,
            Err(GetPublicStreamSessionError::NotFound)
        ));
    }

    #[tokio::test]
    async fn renditions_discard_media_read_when_session_control_is_lost() {
        use crate::adapters::outbound::monotonic_clock::MonotonicSessionClock;

        struct LostSource {
            clock: Arc<dyn SessionMonotonicClock>,
            session_id: SessionId,
        }
        impl crate::application::ports::renditions::RenditionsGateway for LostSource {
            async fn available_renditions(
                &self,
                _: String,
            ) -> Result<
                Vec<crate::domain::rendition::Rendition>,
                crate::application::ports::media_server::MediaServerError,
            > {
                self.clock.forget(&self.session_id);
                media_renditions().result
            }
        }

        let id = SessionId::new();
        let clock: Arc<dyn SessionMonotonicClock> = Arc::new(MonotonicSessionClock::default());
        clock.start_preparing(&id);
        assert!(clock.mark_live(&id));
        let mut session = rendition_session(&id, SessionStatus::Live, Availability::Playable);
        session.session_clock = Arc::clone(&clock);
        let media = LostSource {
            clock,
            session_id: id.clone(),
        };
        assert!(matches!(
            session.renditions(id.as_str().into(), &media).await,
            Err(GetPublicStreamSessionError::Unavailable)
        ));
    }

    #[tokio::test]
    async fn renditions_http_returns_p2_shape_no_store_and_bounded_errors()
    -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        use axum::http::StatusCode;
        let id = SessionId::new();
        for (status, delay, expected) in [
            (
                SessionStatus::Live,
                std::time::Duration::ZERO,
                StatusCode::OK,
            ),
            (
                SessionStatus::Ended,
                std::time::Duration::ZERO,
                StatusCode::SERVICE_UNAVAILABLE,
            ),
            (
                SessionStatus::Live,
                std::time::Duration::from_secs(3),
                StatusCode::GATEWAY_TIMEOUT,
            ),
        ] {
            let availability = if status == SessionStatus::Live {
                Availability::Playable
            } else {
                Availability::Offline
            };
            let session = Arc::new(rendition_session(&id, status, availability));
            let mut media = media_renditions();
            media.delay = delay;
            let router =
                crate::adapters::inbound::http::renditions::router(session, Arc::new(media));
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
            let address = listener.local_addr()?;
            let server = tokio::spawn(async move { axum::serve(listener, router).await });
            let client = reqwest::Client::builder().no_proxy().build()?;
            let response = client
                .get(format!(
                    "http://{address}/api/streams/sessions/{}/renditions",
                    id.as_str()
                ))
                .header("x-request-id", "req_renditions_test")
                .send()
                .await?;
            assert_eq!(response.status(), expected);
            assert_eq!(
                response
                    .headers()
                    .get("cache-control")
                    .and_then(|v| v.to_str().ok()),
                Some("no-store")
            );
            let body: serde_json::Value = response.json().await?;
            if expected == StatusCode::OK {
                assert_eq!(body["sessionId"], id.as_str());
                assert_eq!(body["renditions"][0]["height"], 720);
                assert_eq!(body["renditions"][0]["bitrateBps"], 2_500_000);
                assert_eq!(
                    body["masterPlaylistUri"],
                    format!("/hls/{}/index.m3u8", id.as_str())
                );
                assert_eq!(body["renditions"].as_array().map(Vec::len), Some(1));
            } else {
                assert_eq!(body["requestId"], "req_renditions_test");
                assert!(body.get("renditions").is_none());
            }
            let missing = client
                .get(format!(
                    "http://{address}/api/streams/sessions/invalid/renditions"
                ))
                .send()
                .await?;
            assert_eq!(missing.status(), StatusCode::NOT_FOUND);
            server.abort();
        }
        Ok(())
    }
}
