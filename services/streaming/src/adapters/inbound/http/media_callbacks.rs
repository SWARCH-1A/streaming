use std::sync::Arc;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    adapters::inbound::http::{internal_auth, request_id::RequestId},
    application::{
        ports::media_callbacks::{
            MediaCallbackKind, MediaCallbackReceipt, MediaCallbackRepository,
        },
        use_cases::{RecordMediaCallback, RecordMediaCallbackError, RecordMediaCallbackRequest},
    },
};

const MAX_REQUEST_BYTES: usize = 2048;

struct MediaCallbackHttpState<R> {
    record_media_callback: Arc<RecordMediaCallback<R>>,
    service_token_hash: Option<[u8; 32]>,
}

pub(super) fn router<R>(
    record_media_callback: Arc<RecordMediaCallback<R>>,
    media_adapter_service_token: Option<&str>,
) -> Router
where
    R: MediaCallbackRepository + 'static,
{
    let state = Arc::new(MediaCallbackHttpState {
        record_media_callback,
        service_token_hash: internal_auth::service_token_hash(media_adapter_service_token),
    });

    Router::new()
        .route(
            "/internal/streaming/sessions/{session_id}/source-connected",
            post(source_connected::<R>),
        )
        .route(
            "/internal/streaming/sessions/{session_id}/playback-ready",
            post(playback_ready::<R>),
        )
        .route(
            "/internal/streaming/sessions/{session_id}/source-lost",
            post(source_lost::<R>),
        )
        .layer(DefaultBodyLimit::max(MAX_REQUEST_BYTES))
        .with_state(state)
}

async fn source_connected<R>(
    State(state): State<Arc<MediaCallbackHttpState<R>>>,
    Path(session_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
    Json(request): Json<MediaCallbackBody>,
) -> Response
where
    R: MediaCallbackRepository + 'static,
{
    handle_callback(
        state,
        MediaCallbackKind::SourceConnected,
        session_id,
        headers,
        request_id,
        request,
    )
    .await
}

async fn playback_ready<R>(
    State(state): State<Arc<MediaCallbackHttpState<R>>>,
    Path(session_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
    Json(request): Json<MediaCallbackBody>,
) -> Response
where
    R: MediaCallbackRepository + 'static,
{
    handle_callback(
        state,
        MediaCallbackKind::PlaybackReady,
        session_id,
        headers,
        request_id,
        request,
    )
    .await
}

async fn source_lost<R>(
    State(state): State<Arc<MediaCallbackHttpState<R>>>,
    Path(session_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
    Json(request): Json<MediaCallbackBody>,
) -> Response
where
    R: MediaCallbackRepository + 'static,
{
    handle_callback(
        state,
        MediaCallbackKind::SourceLost,
        session_id,
        headers,
        request_id,
        request,
    )
    .await
}

async fn handle_callback<R>(
    state: Arc<MediaCallbackHttpState<R>>,
    kind: MediaCallbackKind,
    session_id: String,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
    request: MediaCallbackBody,
) -> Response
where
    R: MediaCallbackRepository + 'static,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    if !internal_auth::valid_service_authorization(&headers, state.service_token_hash.as_ref()) {
        return api_error(
            StatusCode::UNAUTHORIZED,
            "SERVICE_AUTHENTICATION_REQUIRED",
            "A valid media adapter service credential is required.",
            request_id,
        );
    }
    if request.session_id != session_id {
        return api_error(
            StatusCode::CONFLICT,
            "SESSION_MISMATCH",
            "The session in the path and callback body must match.",
            request_id,
        );
    }

    match state
        .record_media_callback
        .execute(RecordMediaCallbackRequest {
            event_id: request.event_id,
            kind,
            stream_id: request.stream_id,
            session_id,
            stream_generation: request.stream_generation,
            source_generation: request.source_generation,
            playback_path: request.playback_path,
        })
        .await
    {
        Ok(receipt) => callback_response(receipt, request.event_id),
        Err(error) => map_callback_error(error, request_id),
    }
}

fn callback_response(receipt: MediaCallbackReceipt, event_id: Uuid) -> Response {
    let response = match receipt {
        MediaCallbackReceipt::Accepted => (
            StatusCode::ACCEPTED,
            MediaCallbackResponse {
                accepted: true,
                duplicate: Some(false),
                ignored: None,
                reason: None,
                event_id,
            },
        ),
        MediaCallbackReceipt::Duplicate => (
            StatusCode::OK,
            MediaCallbackResponse {
                accepted: true,
                duplicate: Some(true),
                ignored: None,
                reason: None,
                event_id,
            },
        ),
        MediaCallbackReceipt::StaleGeneration => (
            StatusCode::OK,
            MediaCallbackResponse {
                accepted: true,
                duplicate: None,
                ignored: Some(true),
                reason: Some("STALE_GENERATION"),
                event_id,
            },
        ),
        MediaCallbackReceipt::DuplicateStaleGeneration => (
            StatusCode::OK,
            MediaCallbackResponse {
                accepted: true,
                duplicate: Some(true),
                ignored: Some(true),
                reason: Some("STALE_GENERATION"),
                event_id,
            },
        ),
    };
    (response.0, Json(response.1)).into_response()
}

fn map_callback_error(error: RecordMediaCallbackError, request_id: String) -> Response {
    let (status, code, message) = match error {
        RecordMediaCallbackError::InvalidRequest => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "INVALID_MEDIA_CALLBACK",
            "The media callback fields are invalid.",
        ),
        RecordMediaCallbackError::InvalidPlaybackPath => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "INVALID_PLAYBACK_PATH",
            "The playback path must be a relative HLS manifest under this session.",
        ),
        RecordMediaCallbackError::EventIdConflict => (
            StatusCode::CONFLICT,
            "EVENT_ID_CONFLICT",
            "The eventId was already accepted with a different callback payload.",
        ),
        RecordMediaCallbackError::SessionMismatch => (
            StatusCode::CONFLICT,
            "SESSION_MISMATCH",
            "The callback does not match the stream session.",
        ),
        RecordMediaCallbackError::SessionEnded => (
            StatusCode::GONE,
            "SESSION_ENDED",
            "The stream session has ended.",
        ),
        RecordMediaCallbackError::Unavailable | RecordMediaCallbackError::InvalidStoredData => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming could not durably accept the callback.",
        ),
    };
    api_error(status, code, message, request_id)
}

fn api_error(
    status: StatusCode,
    code: &'static str,
    message: &'static str,
    request_id: String,
) -> Response {
    (
        status,
        Json(ApiError {
            code,
            message,
            request_id,
        }),
    )
        .into_response()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MediaCallbackBody {
    event_id: Uuid,
    stream_id: String,
    session_id: String,
    stream_generation: i64,
    source_generation: i64,
    #[serde(default)]
    playback_path: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MediaCallbackResponse {
    accepted: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    duplicate: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ignored: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<&'static str>,
    event_id: Uuid,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ApiError {
    code: &'static str,
    message: &'static str,
    request_id: String,
}
