use reqwest::StatusCode;
use serde::Deserialize;
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
    pub(super) async fn matches_source(&self, source: &Source) -> Result<bool, ()> {
        Ok(self
            .media_path(&source.ingest_path)
            .await?
            .is_some_and(|path| {
                (path.online || path.ready)
                    && path.source.is_some_and(|publisher| {
                        publisher.id == source.publisher_id
                            && matches!(publisher.kind.as_str(), "rtmpConn" | "rtmpsConn")
                    })
            }))
    }
    pub(super) async fn publisher_exists(&self, id: Uuid) -> Result<bool, ()> {
        let response = self
            .client
            .get(format!(
                "{}/v3/rtmp/conns/get/{id}",
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
            StatusCode::OK => Ok(true),
            StatusCode::NOT_FOUND => Ok(false),
            _ => Err(()),
        }
    }
    pub(super) async fn kick(&self, id: Uuid) -> Result<(), ()> {
        let response = self
            .client
            .post(format!(
                "{}/v3/rtmp/conns/kick/{id}",
                self.config.control_url
            ))
            .basic_auth(
                &self.config.control_user,
                Some(&self.config.control_password),
            )
            .send()
            .await
            .map_err(|_| ())?;
        if response.status().is_success() || response.status() == StatusCode::NOT_FOUND {
            Ok(())
        } else {
            Err(())
        }
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
