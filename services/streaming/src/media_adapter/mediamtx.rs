use reqwest::StatusCode;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use uuid::Uuid;

use super::{MediaState, repository::Source};

#[derive(Deserialize)]
pub(super) struct PathInfo {
    #[serde(default)]
    pub online: bool,
    #[serde(default)]
    pub ready: bool,
    pub source: Option<PathSource>,
}
#[derive(Deserialize)]
pub(super) struct PathSource {
    pub id: Uuid,
    #[serde(rename = "type")]
    pub kind: String,
}

#[derive(Deserialize)]
struct PublisherInfo {
    id: Uuid,
    state: String,
    path: String,
}

#[derive(Deserialize)]
pub(super) struct SessionStatus {
    pub status: String,
    pub availability: String,
}

impl MediaState {
    pub(super) async fn media_path(&self, path: &str) -> Result<Option<PathInfo>, ()> {
        let response = self
            .client
            .get(format!("{}/v3/paths/get/{}", self.config.control_url, path))
            .basic_auth(
                &self.config.control_user,
                Some(&self.config.control_password),
            )
            .send()
            .await
            .map_err(|_| ())?;
        if response.status() == StatusCode::NOT_FOUND {
            return Ok(None);
        }
        if response.status() != StatusCode::OK
            || response.content_length().is_some_and(|n| n > 65536)
        {
            return Err(());
        }
        serde_json::from_slice(
            &crate::adapters::outbound::http_body::limited(response, 65536).await?,
        )
        .map(Some)
        .map_err(|_| ())
    }
    pub(super) async fn source_path(&self, source: &Source) -> Result<Option<String>, ()> {
        // The secret path lives only in MediaMTX and transient request memory.
        // Recover it through the private Control API, bound to the authorized publisher/hash.
        for protocol in ["rtmp", "rtmps"] {
            let response = self
                .client
                .get(format!(
                    "{}/v3/{protocol}/conns/get/{}",
                    self.config.control_url, source.publisher_id
                ))
                .basic_auth(
                    &self.config.control_user,
                    Some(&self.config.control_password),
                )
                .send()
                .await
                .map_err(|_| ())?;
            if response.status() == StatusCode::NOT_FOUND {
                continue;
            }
            if response.status() != StatusCode::OK {
                return Err(());
            }
            let publisher: PublisherInfo = serde_json::from_slice(
                &crate::adapters::outbound::http_body::limited(response, 65536).await?,
            )
            .map_err(|_| ())?;
            if publisher.id != source.publisher_id || publisher.state != "publish" {
                return Ok(None);
            }
            let Some(key) = super::ingest_key(&publisher.path) else {
                return Ok(None);
            };
            let hash = Sha256::digest(key.as_bytes());
            if source
                .request_hash
                .as_slice()
                .ct_eq(hash.as_slice())
                .unwrap_u8()
                != 1
            {
                return Ok(None);
            }
            return Ok(self
                .media_path(&publisher.path)
                .await?
                .filter(|path| {
                    (path.online || path.ready)
                        && path.source.as_ref().is_some_and(|publisher| {
                            publisher.id == source.publisher_id
                                && matches!(publisher.kind.as_str(), "rtmpConn" | "rtmpsConn")
                        })
                })
                .map(|_| publisher.path));
        }
        Ok(None)
    }
    pub(super) async fn matches_source(&self, source: &Source) -> Result<bool, ()> {
        self.source_path(source).await.map(|path| path.is_some())
    }
    pub(super) async fn publisher_exists(&self, id: Uuid) -> Result<bool, ()> {
        // MediaMTX keeps RTMP and RTMPS connections in separate API collections.
        for protocol in ["rtmp", "rtmps"] {
            let response = self
                .client
                .get(format!(
                    "{}/v3/{protocol}/conns/get/{id}",
                    self.config.control_url
                ))
                .basic_auth(
                    &self.config.control_user,
                    Some(&self.config.control_password),
                )
                .send()
                .await
                .map_err(|_| ())?;
            match response.status() {
                StatusCode::OK => return Ok(true),
                StatusCode::NOT_FOUND => {}
                _ => return Err(()),
            }
        }
        Ok(false)
    }
    pub(super) async fn kick(&self, id: Uuid) -> Result<(), ()> {
        for protocol in ["rtmp", "rtmps"] {
            let response = self
                .client
                .post(format!(
                    "{}/v3/{protocol}/conns/kick/{id}",
                    self.config.control_url
                ))
                .basic_auth(
                    &self.config.control_user,
                    Some(&self.config.control_password),
                )
                .send()
                .await
                .map_err(|_| ())?;
            if response.status().is_success() {
                return Ok(());
            }
            if response.status() != StatusCode::NOT_FOUND {
                return Err(());
            }
        }
        // Only absence in both collections means the publisher is already gone.
        Ok(())
    }
    pub(super) async fn session_status(&self, session: &str) -> Result<String, ()> {
        self.session_state(session).await.map(|value| value.status)
    }
    pub(super) async fn session_state(&self, session: &str) -> Result<SessionStatus, ()> {
        let response = self
            .client
            .get(format!(
                "{}/api/streams/sessions/{session}",
                self.config.streaming_public_url
            ))
            .send()
            .await
            .map_err(|_| ())?;
        if response.status() == StatusCode::NOT_FOUND {
            return Ok(SessionStatus {
                status: "ENDED".to_owned(),
                availability: "OFFLINE".to_owned(),
            });
        }
        if response.status() != StatusCode::OK {
            return Err(());
        }
        let value: SessionStatus = serde_json::from_slice(
            &crate::adapters::outbound::http_body::limited(response, 65536).await?,
        )
        .map_err(|_| ())?;
        if !matches!(value.status.as_str(), "PREPARING" | "LIVE" | "ENDED") {
            return Err(());
        }
        Ok(value)
    }
}
