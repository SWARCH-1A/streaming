use std::{sync::Arc, time::Duration};

use crate::{
    application::ports::{
        media_callbacks::{
            ClaimedMediaCallback, MediaCallbackKind, MediaCallbackProcessingError,
            MediaCallbackProcessingRepository, MediaCallbackSessionState,
        },
        media_server::{MediaServerError, MediaServerGateway, PlaybackEvidence},
        session_clock::SessionMonotonicClock,
    },
    domain::session::{PREPARING_TIMEOUT, RECONNECT_GRACE},
};

const POLL_INTERVAL: Duration = Duration::from_millis(100);

pub struct ProcessMediaCallbacks<R, G> {
    repository: Arc<R>,
    media_server: Arc<G>,
    session_clock: Arc<dyn SessionMonotonicClock>,
}

impl<R, G> ProcessMediaCallbacks<R, G>
where
    R: MediaCallbackProcessingRepository + 'static,
    G: MediaServerGateway + 'static,
{
    pub fn new(
        repository: Arc<R>,
        media_server: Arc<G>,
        session_clock: Arc<dyn SessionMonotonicClock>,
    ) -> Self {
        Self {
            repository,
            media_server,
            session_clock,
        }
    }

    pub async fn run(&self, owner_instance_id: String) {
        loop {
            match self.repository.claim_next(owner_instance_id.clone()).await {
                Ok(Some(callback)) => self.process(callback, &owner_instance_id).await,
                Ok(None) => tokio::time::sleep(POLL_INTERVAL).await,
                Err(error) => {
                    tracing::warn!(error = %error, "could not claim a media callback");
                    tokio::time::sleep(Duration::from_millis(500)).await;
                }
            }
        }
    }

    async fn process(&self, callback: ClaimedMediaCallback, owner_instance_id: &str) {
        let expired_without_clock = match callback.session_state {
            MediaCallbackSessionState::Preparing => self
                .session_clock
                .preparing_elapsed(&callback.session_id)
                .is_none_or(|elapsed| elapsed >= PREPARING_TIMEOUT),
            MediaCallbackSessionState::ReconnectGrace => self
                .session_clock
                .reconnect_elapsed(&callback.session_id)
                .is_none_or(|elapsed| elapsed >= RECONNECT_GRACE),
            MediaCallbackSessionState::Live | MediaCallbackSessionState::Ended => false,
        };

        let playback_evidence = if callback.kind == MediaCallbackKind::PlaybackReady
            && callback.session_state != MediaCallbackSessionState::Live
            && !expired_without_clock
        {
            match self.verify_playback(&callback).await {
                Ok(evidence) => Some(evidence),
                Err(error_code) => {
                    self.schedule_retry(&callback, owner_instance_id, error_code)
                        .await;
                    return;
                }
            }
        } else {
            None
        };

        if let Err(error) = self
            .repository
            .apply(
                callback.clone(),
                owner_instance_id.to_owned(),
                playback_evidence,
                self.session_clock.as_ref(),
            )
            .await
        {
            match error {
                MediaCallbackProcessingError::LeaseLost => {
                    tracing::debug!(
                        event_id = %callback.event_id,
                        session_id = %callback.session_id.as_str(),
                        "media callback claim expired before application"
                    );
                    return;
                }
                MediaCallbackProcessingError::InvalidStoredData
                | MediaCallbackProcessingError::PlaybackNotVerified => {
                    let reason_code = processing_error_code(error);
                    match self
                        .repository
                        .dead_letter(callback.event_id, owner_instance_id.to_owned(), reason_code)
                        .await
                    {
                        Ok(()) => tracing::error!(
                            event_id = %callback.event_id,
                            session_id = %callback.session_id.as_str(),
                            reason_code,
                            "media callback moved to the durable dead-letter queue"
                        ),
                        Err(dead_letter_error) => tracing::warn!(
                            event_id = %callback.event_id,
                            session_id = %callback.session_id.as_str(),
                            error = %dead_letter_error,
                            "could not dead-letter a permanent media callback error"
                        ),
                    }
                    return;
                }
                MediaCallbackProcessingError::Unavailable => {}
            }
            self.schedule_retry(&callback, owner_instance_id, processing_error_code(error))
                .await;
        }
    }

    async fn verify_playback(
        &self,
        callback: &ClaimedMediaCallback,
    ) -> Result<PlaybackEvidence, &'static str> {
        let Some(media_node_id) = callback.media_node_id.clone() else {
            return Err("MEDIA_NODE_UNAVAILABLE");
        };
        let Some(playback_path) = callback.playback_path.clone() else {
            return Err("INVALID_PLAYBACK_PATH");
        };
        let evidence = self
            .media_server
            .verify_playback(media_node_id, playback_path.clone())
            .await
            .map_err(media_error_code)?;
        if evidence.manifest_path != playback_path || !evidence.has_reproducible_segment {
            return Err("PLAYBACK_EVIDENCE_MISSING");
        }
        Ok(evidence)
    }

    async fn schedule_retry(
        &self,
        callback: &ClaimedMediaCallback,
        owner_instance_id: &str,
        error_code: &'static str,
    ) {
        tracing::warn!(
            event_id = %callback.event_id,
            session_id = %callback.session_id.as_str(),
            attempt = callback.processing_attempts,
            error_code,
            "media callback processing will be retried"
        );
        if let Err(error) = self
            .repository
            .retry(
                callback.event_id,
                owner_instance_id.to_owned(),
                error_code,
                retry_delay(callback.processing_attempts),
            )
            .await
        {
            tracing::warn!(
                event_id = %callback.event_id,
                session_id = %callback.session_id.as_str(),
                error = %error,
                "could not reschedule a media callback"
            );
        }
    }
}

fn retry_delay(attempt: i32) -> Duration {
    match attempt {
        i32::MIN..=1 => Duration::from_millis(100),
        2 => Duration::from_millis(250),
        3 => Duration::from_millis(500),
        4 => Duration::from_secs(1),
        _ => Duration::from_secs(2),
    }
}

fn media_error_code(error: MediaServerError) -> &'static str {
    match error {
        MediaServerError::NodeUnavailable => "MEDIA_NODE_UNAVAILABLE",
        MediaServerError::ControlApiUnavailable => "MEDIA_CONTROL_API_UNAVAILABLE",
        MediaServerError::ManifestUnavailable => "HLS_MANIFEST_UNAVAILABLE",
        MediaServerError::SegmentUnavailable => "HLS_SEGMENT_UNAVAILABLE",
        MediaServerError::InvalidResponse => "HLS_INVALID_RESPONSE",
    }
}

fn processing_error_code(error: MediaCallbackProcessingError) -> &'static str {
    match error {
        MediaCallbackProcessingError::LeaseLost => "PROCESSING_LEASE_LOST",
        MediaCallbackProcessingError::PlaybackNotVerified => "PLAYBACK_NOT_VERIFIED",
        MediaCallbackProcessingError::InvalidStoredData => "INVALID_STORED_DATA",
        MediaCallbackProcessingError::Unavailable => "PERSISTENCE_UNAVAILABLE",
    }
}
