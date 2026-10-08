use std::sync::Arc;

use axum::{
    Json, Router,
    extract::{Path, State},
    http::{
        HeaderMap, StatusCode,
        header::{AUTHORIZATION, CACHE_CONTROL},
    },
    response::{IntoResponse, Response},
    routing::{delete, post, put},
};
use serde::Serialize;
use time::format_description::well_known::Rfc3339;
use uuid::Uuid;

use crate::{
    adapters::inbound::http::request_id::RequestId,
    application::use_cases::{
        CloseViewerLease, CreateViewerLease, HeartbeatViewerLease, ViewerLeaseError,
    },
};

struct ViewerLeaseHttpState<I, R> {
    create: Arc<CreateViewerLease<I, R>>,
    heartbeat: Arc<HeartbeatViewerLease<R>>,
    close: Arc<CloseViewerLease<R>>,
}

pub(super) fn router<I, R>(
    create: Arc<CreateViewerLease<I, R>>,
    heartbeat: Arc<HeartbeatViewerLease<R>>,
    close: Arc<CloseViewerLease<R>>,
) -> Router
where
    I: crate::application::ports::viewer_leases::ViewerLeaseCredentialIssuer + 'static,
    R: crate::application::ports::viewer_leases::ViewerLeaseRepository + 'static,
{
    let state = Arc::new(ViewerLeaseHttpState {
        create,
        heartbeat,
        close,
    });
    Router::new()
        .route(
            "/api/streams/sessions/{session_id}/viewer-leases",
            post(create_lease::<I, R>),
        )
        .route(
            "/api/streams/viewer-leases/{lease_id}/heartbeat",
            put(heartbeat_lease::<I, R>),
        )
        .route(
            "/api/streams/viewer-leases/{lease_id}",
            delete(close_lease::<I, R>),
        )
        .with_state(state)
}

async fn create_lease<I, R>(
    State(state): State<Arc<ViewerLeaseHttpState<I, R>>>,
    Path(session_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
) -> Response
where
    I: crate::application::ports::viewer_leases::ViewerLeaseCredentialIssuer,
    R: crate::application::ports::viewer_leases::ViewerLeaseRepository,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    let Some(idempotency_key) = headers
        .get("Idempotency-Key")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| Uuid::parse_str(value).ok())
    else {
        return error_response(
            StatusCode::BAD_REQUEST,
            "IDEMPOTENCY_KEY_REQUIRED",
            "A UUID Idempotency-Key header is required.",
            request_id,
        );
    };

    match state.create.execute(session_id, idempotency_key).await {
        Ok(lease) => {
            let status = if lease.created {
                StatusCode::CREATED
            } else {
                StatusCode::OK
            };
            (
                status,
                [(CACHE_CONTROL, "no-store")],
                Json(CreateViewerLeaseResponse {
                    lease_id: lease.lease_id,
                    lease_token: lease.lease_token,
                    session_id: lease.session_id,
                    heartbeat_every_seconds: 10,
                    expires_after_seconds: 30,
                }),
            )
                .into_response()
        }
        Err(error) => map_error(error, request_id),
    }
}

async fn heartbeat_lease<I, R>(
    State(state): State<Arc<ViewerLeaseHttpState<I, R>>>,
    Path(lease_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
) -> Response
where
    I: crate::application::ports::viewer_leases::ViewerLeaseCredentialIssuer,
    R: crate::application::ports::viewer_leases::ViewerLeaseRepository,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    let Some(token) = viewer_lease_token(&headers) else {
        return error_response(
            StatusCode::UNAUTHORIZED,
            "VIEWER_LEASE_TOKEN_REQUIRED",
            "A valid viewer lease token is required.",
            request_id,
        );
    };

    match state.heartbeat.execute(lease_id, token).await {
        Ok(lease) => match lease.expires_at.format(&Rfc3339) {
            Ok(expires_at) => (
                StatusCode::OK,
                Json(HeartbeatViewerLeaseResponse {
                    lease_id: lease.lease_id,
                    session_id: lease.session_id,
                    expires_at_utc: expires_at,
                }),
            )
                .into_response(),
            Err(_) => error_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The request could not be completed.",
                request_id,
            ),
        },
        Err(error) => map_error(error, request_id),
    }
}

async fn close_lease<I, R>(
    State(state): State<Arc<ViewerLeaseHttpState<I, R>>>,
    Path(lease_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
) -> Response
where
    I: crate::application::ports::viewer_leases::ViewerLeaseCredentialIssuer,
    R: crate::application::ports::viewer_leases::ViewerLeaseRepository,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    let Some(token) = viewer_lease_token(&headers) else {
        return error_response(
            StatusCode::UNAUTHORIZED,
            "VIEWER_LEASE_TOKEN_REQUIRED",
            "A valid viewer lease token is required.",
            request_id,
        );
    };
    match state.close.execute(lease_id, token).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => map_error(error, request_id),
    }
}

fn viewer_lease_token(headers: &HeaderMap) -> Option<String> {
    let value = headers.get(AUTHORIZATION)?.to_str().ok()?;
    let (scheme, token) = value.split_once(' ')?;
    (scheme.eq_ignore_ascii_case("ViewerLease")
        && !token.is_empty()
        && !token.contains(char::is_whitespace))
    .then(|| token.to_owned())
}

fn map_error(error: ViewerLeaseError, request_id: String) -> Response {
    let (status, code, message) = match error {
        ViewerLeaseError::InvalidSessionId | ViewerLeaseError::InvalidLeaseId => (
            StatusCode::NOT_FOUND,
            "VIEWER_LEASE_NOT_FOUND",
            "The viewer lease resource was not found.",
        ),
        ViewerLeaseError::CredentialsUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_CONFIGURATION_INCOMPLETE",
            "Viewer lease credentials are not configured.",
        ),
        ViewerLeaseError::NotFound => (
            StatusCode::NOT_FOUND,
            "VIEWER_LEASE_NOT_FOUND",
            "The viewer lease resource was not found.",
        ),
        ViewerLeaseError::SessionNotPlayable => (
            StatusCode::CONFLICT,
            "SESSION_NOT_PLAYABLE",
            "The session is not currently playable.",
        ),
        ViewerLeaseError::SessionEnded => (
            StatusCode::GONE,
            "SESSION_ENDED",
            "The streaming session has ended.",
        ),
        ViewerLeaseError::IdempotencyConflict => (
            StatusCode::CONFLICT,
            "IDEMPOTENCY_KEY_REUSED",
            "The idempotency key has already been used with different credentials.",
        ),
        ViewerLeaseError::InvalidToken => (
            StatusCode::UNAUTHORIZED,
            "INVALID_VIEWER_LEASE_TOKEN",
            "The viewer lease token is invalid or expired.",
        ),
        ViewerLeaseError::Unavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming is temporarily unavailable.",
        ),
        ViewerLeaseError::InvalidStoredData => (
            StatusCode::INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "The request could not be completed.",
        ),
    };
    error_response(status, code, message, request_id)
}

fn error_response(
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

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CreateViewerLeaseResponse {
    lease_id: String,
    lease_token: String,
    session_id: String,
    heartbeat_every_seconds: u8,
    expires_after_seconds: u8,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct HeartbeatViewerLeaseResponse {
    lease_id: String,
    session_id: String,
    expires_at_utc: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ApiError {
    code: &'static str,
    message: &'static str,
    request_id: String,
}
