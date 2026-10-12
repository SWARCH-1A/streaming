use std::sync::Arc;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    adapters::inbound::http::request_id::RequestId,
    application::{
        ports::ingest_authorization::{IngestAuthorizationError, IngestAuthorizationRepository},
        use_cases::AuthorizeIngest,
    },
};

use super::internal_auth;

const MAX_REQUEST_BYTES: usize = 2048;

struct IngestAuthorizationHttpState<R> {
    authorize_ingest: Arc<AuthorizeIngest<R>>,
    service_token_hash: Option<[u8; 32]>,
}

pub(super) fn router<R>(
    authorize_ingest: Arc<AuthorizeIngest<R>>,
    media_adapter_service_token: Option<&str>,
) -> Router
where
    R: IngestAuthorizationRepository + 'static,
{
    let state = Arc::new(IngestAuthorizationHttpState {
        authorize_ingest,
        service_token_hash: internal_auth::service_token_hash(media_adapter_service_token),
    });

    Router::new()
        .route("/internal/streaming/ingest/authorize", post(authorize::<R>))
        .layer(DefaultBodyLimit::max(MAX_REQUEST_BYTES))
        .with_state(state)
}

async fn authorize<R>(
    State(state): State<Arc<IngestAuthorizationHttpState<R>>>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
    Json(request): Json<AuthorizeIngestRequest>,
) -> Response
where
    R: IngestAuthorizationRepository + 'static,
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

    match state
        .authorize_ingest
        .execute_for_stream(
            request.ingest_attempt_id,
            request.stream_key,
            request.stream_id,
        )
        .await
    {
        Ok(authorization) => (StatusCode::OK, Json(authorization)).into_response(),
        Err(error) => map_authorization_error(error, request_id),
    }
}

fn map_authorization_error(error: IngestAuthorizationError, request_id: String) -> Response {
    let (status, code, message) = match error {
        IngestAuthorizationError::InvalidStreamKey => (
            StatusCode::UNAUTHORIZED,
            "INVALID_STREAM_KEY",
            "The stream key is invalid.",
        ),
        IngestAuthorizationError::ChannelAlreadyActive => (
            StatusCode::CONFLICT,
            "CHANNEL_ALREADY_ACTIVE",
            "The channel already has an active source.",
        ),
        IngestAuthorizationError::LiveSessionLimit => (
            StatusCode::CONFLICT,
            "LIVE_SESSION_LIMIT",
            "The platform live session limit has been reached.",
        ),
        IngestAuthorizationError::IdempotencyKeyReused => (
            StatusCode::CONFLICT,
            "INGEST_ATTEMPT_REUSED",
            "The ingest attempt identifier was reused with a different request.",
        ),
        IngestAuthorizationError::SessionOwnedElsewhere => (
            StatusCode::SERVICE_UNAVAILABLE,
            "SESSION_OWNER_UNAVAILABLE",
            "The active session must be handled by its current owner.",
        ),
        IngestAuthorizationError::InvalidStoredData
        | IngestAuthorizationError::PersistenceUnavailable
        | IngestAuthorizationError::MediaNodeUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming could not authorize this ingest request.",
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
#[serde(rename_all = "camelCase")]
struct AuthorizeIngestRequest {
    ingest_attempt_id: Uuid,
    #[serde(default)]
    stream_id: Option<crate::domain::ids::StreamId>,
    stream_key: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ApiError {
    code: &'static str,
    message: &'static str,
    request_id: String,
}
