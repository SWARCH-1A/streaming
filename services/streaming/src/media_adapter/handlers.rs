use std::sync::Arc;

use axum::{
    Json, Router,
    body::Body,
    extract::{DefaultBodyLimit, OriginalUri, Path, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use futures_util::stream;
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use uuid::Uuid;

use super::MediaState;
use crate::{
    application::ports::ingest_authorization::IngestAuthorization,
    domain::ids::{SessionId, StreamId},
};

pub(super) fn router(state: Arc<MediaState>) -> Router {
    Router::new()
        .route("/health/live", get(|| async { StatusCode::OK }))
        .route("/health/ready", get(ready))
        .route("/internal/media/auth", post(authorize))
        .layer(DefaultBodyLimit::max(8192))
        .with_state(state)
}

pub(super) fn hls_router(state: Arc<MediaState>) -> Router {
    Router::new()
        .route("/hls/{session}/{*file}", get(hls))
        .with_state(state)
}
fn credential_matches(headers: &HeaderMap, expected: &str) -> bool {
    if headers.get_all(header::AUTHORIZATION).iter().count() != 1 {
        return false;
    }
    let Some(value) = headers.get(header::AUTHORIZATION) else {
        return false;
    };
    let a: [u8; 32] = Sha256::digest(value.as_bytes()).into();
    let b: [u8; 32] = Sha256::digest(expected.as_bytes()).into();
    bool::from(a.ct_eq(&b))
}
async fn ready(State(state): State<Arc<MediaState>>) -> StatusCode {
    if sqlx::query_scalar::<_, i32>("SELECT 1")
        .fetch_one(&state.repository.pool)
        .await
        .is_err()
        || state
            .repository
            .open_dead_letters()
            .await
            .map_or(true, |n| n >= state.config.max_open_dead_letters)
    {
        return StatusCode::SERVICE_UNAVAILABLE;
    }
    match state
        .client
        .get(format!("{}/v3/info", state.config.control_url))
        .basic_auth(
            &state.config.control_user,
            Some(&state.config.control_password),
        )
        .send()
        .await
    {
        Ok(response) if response.status().is_success() => StatusCode::OK,
        _ => StatusCode::SERVICE_UNAVAILABLE,
    }
}
#[derive(Deserialize)]
struct AuthRequest {
    action: String,
    protocol: Option<String>,
    id: Option<Uuid>,
    #[serde(default)]
    path: String,
    #[serde(default)]
    user: String,
    #[serde(default)]
    password: String,
}
async fn authorize(
    State(state): State<Arc<MediaState>>,
    headers: HeaderMap,
    Json(request): Json<AuthRequest>,
) -> Response {
    if !credential_matches(&headers, &state.config.auth_header) {
        return error(StatusCode::UNAUTHORIZED, "MEDIA_AUTH_REQUIRED");
    }
    if request.action == "api" {
        let presented =
            Sha256::digest(format!("{}\0{}", request.user, request.password).as_bytes());
        let expected = Sha256::digest(
            format!(
                "{}\0{}",
                state.config.control_user, state.config.control_password
            )
            .as_bytes(),
        );
        return if bool::from(presented.ct_eq(&expected)) {
            StatusCode::NO_CONTENT.into_response()
        } else {
            error(StatusCode::UNAUTHORIZED, "CONTROL_AUTH_REQUIRED")
        };
    }
    let Some(stream) = request
        .path
        .strip_prefix("live/")
        .and_then(|s| StreamId::parse(s.to_owned()))
    else {
        return error(StatusCode::FORBIDDEN, "INVALID_INGEST_PATH");
    };
    let Some(id) = request.id else {
        return error(StatusCode::FORBIDDEN, "INVALID_PUBLISHER");
    };
    if request.action != "publish"
        || request.protocol.as_deref() != Some("rtmp")
        || request.password.is_empty()
        || request.password.len() > 4096
    {
        return error(StatusCode::FORBIDDEN, "PUBLISH_NOT_ALLOWED");
    }
    if state
        .repository
        .open_dead_letters()
        .await
        .map_or(true, |n| n >= state.config.max_open_dead_letters)
    {
        return error(StatusCode::SERVICE_UNAVAILABLE, "MEDIA_QUEUE_CAPACITY");
    }
    let hash = Sha256::digest(format!("{}\0{}", request.path, request.password).as_bytes());
    match state.repository.source(id).await {
        Ok(Some(existing)) => {
            if existing
                .request_hash
                .as_slice()
                .ct_eq(hash.as_slice())
                .unwrap_u8()
                != 1
                || existing.retired_at.is_some()
                || existing.lost_at.is_some()
            {
                return error(StatusCode::FORBIDDEN, "PUBLISHER_REUSED");
            }
            // A lost response must not revive an authorization ended by Streaming.
            return match state.session_status(&existing.session_id).await {
                Ok(status) if status != "ENDED" => StatusCode::NO_CONTENT.into_response(),
                _ => error(StatusCode::FORBIDDEN, "SESSION_NOT_ACTIVE"),
            };
        }
        Ok(None) => {}
        Err(_) => return error(StatusCode::SERVICE_UNAVAILABLE, "MEDIA_STORAGE_UNAVAILABLE"),
    }
    let response = match state
        .client
        .post(format!(
            "{}/internal/streaming/ingest/authorize",
            state.config.streaming_url
        ))
        .bearer_auth(&state.config.streaming_token)
        .json(
            &json!({"ingestAttemptId":id,"streamId":stream.as_str(),"streamKey":request.password}),
        )
        .send()
        .await
    {
        Ok(response) => response,
        Err(_) => return error(StatusCode::SERVICE_UNAVAILABLE, "STREAMING_UNAVAILABLE"),
    };
    if !response.status().is_success() {
        return error(response.status(), "INGEST_DENIED");
    }
    let authorization = match crate::adapters::outbound::http_body::limited(response, 65536)
        .await
        .and_then(|bytes| serde_json::from_slice::<IngestAuthorization>(&bytes).map_err(|_| ()))
    {
        Ok(value)
            if value.stream_id == stream
                && value.stream_generation > 0
                && value.source_generation > 0 =>
        {
            value
        }
        _ => return error(StatusCode::BAD_GATEWAY, "INVALID_AUTHORIZATION"),
    };
    if !state
        .session_status(authorization.session_id.as_str())
        .await
        .is_ok_and(|status| status != "ENDED")
    {
        return error(StatusCode::FORBIDDEN, "SESSION_NOT_ACTIVE");
    }
    if state
        .repository
        .save_source(id, &request.path, hash.as_slice(), &authorization)
        .await
        .is_err()
    {
        return error(StatusCode::SERVICE_UNAVAILABLE, "MEDIA_STORAGE_UNAVAILABLE");
    }
    // Re-read after an ON CONFLICT: another request with the same publisher ID
    // cannot overwrite its stream, generations, or credential fingerprint.
    match state.repository.source(id).await {
        Ok(Some(source))
            if source.session_id == authorization.session_id.as_str()
                && source
                    .request_hash
                    .as_slice()
                    .ct_eq(hash.as_slice())
                    .unwrap_u8()
                    == 1
                && source.retired_at.is_none() =>
        {
            StatusCode::NO_CONTENT.into_response()
        }
        _ => error(StatusCode::CONFLICT, "PUBLISHER_REUSED"),
    }
}

async fn hls(
    State(state): State<Arc<MediaState>>,
    Path((session, file)): Path<(String, String)>,
    OriginalUri(uri): OriginalUri,
    headers: HeaderMap,
) -> Response {
    if SessionId::parse(session.clone()).is_none() || !valid_file(&file) || uri.path().contains('%')
    {
        return error(StatusCode::NOT_FOUND, "HLS_NOT_FOUND");
    }
    let source = match state.repository.binding(&session).await {
        Ok(Some(source)) => source,
        Ok(None) => return error(StatusCode::NOT_FOUND, "HLS_NOT_FOUND"),
        Err(_) => return error(StatusCode::SERVICE_UNAVAILABLE, "MEDIA_STORAGE_UNAVAILABLE"),
    };
    if source.retired_at.is_some() || source.lost_at.is_some() {
        return match state.session_status(&session).await {
            Ok(status) if status == "ENDED" => error(StatusCode::GONE, "SESSION_ENDED"),
            Ok(_) => error(StatusCode::SERVICE_UNAVAILABLE, "SOURCE_UNAVAILABLE"),
            Err(_) => error(StatusCode::SERVICE_UNAVAILABLE, "STREAMING_UNAVAILABLE"),
        };
    }
    let internal = credential_matches(
        &headers,
        &format!("Bearer {}", state.config.streaming_token),
    );
    if !internal {
        match state.session_status(&session).await {
            Ok(status) if status == "LIVE" => {}
            Ok(status) if status == "ENDED" => return error(StatusCode::GONE, "SESSION_ENDED"),
            Ok(_) => return error(StatusCode::SERVICE_UNAVAILABLE, "PLAYBACK_NOT_READY"),
            Err(_) => return error(StatusCode::SERVICE_UNAVAILABLE, "STREAMING_UNAVAILABLE"),
        }
    }
    if !state.matches_source(&source).await.unwrap_or(false) {
        return error(StatusCode::SERVICE_UNAVAILABLE, "SOURCE_UNAVAILABLE");
    }
    let mut url = match reqwest::Url::parse(&format!(
        "{}/{}/{}",
        state.config.hls_url, source.ingest_path, file
    )) {
        Ok(url) => url,
        Err(_) => return error(StatusCode::SERVICE_UNAVAILABLE, "MEDIA_CONFIGURATION_ERROR"),
    };
    if let Some(query) = uri.query() {
        if query.len() > 256 {
            return error(StatusCode::BAD_REQUEST, "INVALID_HLS_QUERY");
        }
        for (key, value) in reqwest::Url::parse(&format!("http://localhost/?{query}"))
            .ok()
            .into_iter()
            .flat_map(|u| {
                u.query_pairs()
                    .map(|(k, v)| (k.into_owned(), v.into_owned()))
                    .collect::<Vec<_>>()
            })
        {
            let valid = match key.as_str() {
                "_HLS_msn" | "_HLS_part" => {
                    !value.is_empty()
                        && value.len() <= 20
                        && value.bytes().all(|b| b.is_ascii_digit())
                }
                "_HLS_skip" => matches!(value.as_str(), "YES" | "v2"),
                _ => false,
            };
            if !valid {
                return error(StatusCode::BAD_REQUEST, "INVALID_HLS_QUERY");
            }
            url.query_pairs_mut().append_pair(&key, &value);
        }
    }
    let mut request = state.client.get(url).bearer_auth(&state.config.hls_secret);
    for key in [
        header::RANGE,
        header::IF_NONE_MATCH,
        header::IF_MODIFIED_SINCE,
    ] {
        if let Some(value) = headers.get(&key) {
            request = request.header(key, value);
        }
    }
    let response = match request.send().await {
        Ok(response) => response,
        Err(_) => return error(StatusCode::SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE"),
    };
    if response.status().is_redirection() || !state.matches_source(&source).await.unwrap_or(false) {
        return error(StatusCode::SERVICE_UNAVAILABLE, "SOURCE_CHANGED");
    }
    let status = response.status();
    if !status.is_success() && status != StatusCode::NOT_MODIFIED {
        return error(status, "HLS_UNAVAILABLE");
    }
    let mut public_headers = HeaderMap::new();
    for key in [
        header::CONTENT_TYPE,
        header::CONTENT_LENGTH,
        header::CONTENT_RANGE,
        header::ACCEPT_RANGES,
        header::ETAG,
        header::LAST_MODIFIED,
    ] {
        if let Some(value) = response.headers().get(&key) {
            public_headers.insert(key, value.clone());
        }
    }
    public_headers.insert(
        header::CACHE_CONTROL,
        axum::http::HeaderValue::from_static("no-store"),
    );
    let body = stream::try_unfold((response, 0usize), |(mut response, total)| async move {
        let chunk = response
            .chunk()
            .await
            .map_err(|_| std::io::Error::other("HLS transfer failed"))?;
        match chunk {
            Some(chunk) if total.saturating_add(chunk.len()) <= 16 * 1024 * 1024 => {
                let next = total + chunk.len();
                Ok(Some((chunk, (response, next))))
            }
            Some(_) => Err(std::io::Error::other("HLS object exceeds transport limit")),
            None => Ok(None),
        }
    });
    (status, public_headers, Body::from_stream(body)).into_response()
}
fn valid_file(file: &str) -> bool {
    !file.is_empty()
        && file.len() <= 256
        && [".m3u8", ".mp4", ".m4s", ".ts"]
            .iter()
            .any(|suffix| file.ends_with(suffix))
        && file.split('/').all(|part| {
            !part.is_empty()
                && part != "."
                && part != ".."
                && part
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
        })
}
fn error(status: StatusCode, code: &'static str) -> Response {
    (
        status,
        Json(json!({"code":code,"message":"Media request could not be completed."})),
    )
        .into_response()
}
