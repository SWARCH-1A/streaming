use super::internal_auth::{service_token_hash, valid_service_authorization};
use crate::application::{
    ports::{
        discovery_projection::{DiscoveryProjectionRepository, ProjectionError},
        streaming_repository::StreamingRepository,
    },
    use_cases::{GetChannelSnapshots, GetPublicStreamSession, GetPublicStreamSessionError},
};
use axum::{
    Extension, Json, Router,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, StatusCode},
    middleware,
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::Deserialize;
use serde_json::json;
use std::sync::Arc;
use time::format_description::well_known::Rfc3339;
struct ContextState<R, D> {
    session: Arc<GetPublicStreamSession<R>>,
    channels: GetChannelSnapshots<R>,
    discovery: Arc<D>,
    token: Option<[u8; 32]>,
}
pub fn router<R: StreamingRepository + 'static, D: DiscoveryProjectionRepository + 'static>(
    repository: Arc<R>,
    session: Arc<GetPublicStreamSession<R>>,
    discovery: Arc<D>,
    token: Option<&str>,
) -> Router {
    let state = Arc::new(ContextState {
        channels: GetChannelSnapshots::new(repository, Arc::clone(&session)),
        session,
        discovery,
        token: service_token_hash(token),
    });
    Router::new()
        .route(
            "/internal/streaming/sessions/{id}/context",
            get(context::<R, D>),
        )
        .route(
            "/internal/streaming/channels/snapshots",
            post(channels::<R, D>),
        )
        .route(
            "/internal/streaming/discovery/snapshots",
            post(discovery_page::<R, D>),
        )
        .layer(DefaultBodyLimit::max(16384))
        .layer(middleware::from_fn(
            |request: axum::extract::Request, next: middleware::Next| async move {
                let mut response = next.run(request).await;
                response.headers_mut().insert(
                    axum::http::header::CACHE_CONTROL,
                    axum::http::HeaderValue::from_static("no-store"),
                );
                response
            },
        ))
        .layer(middleware::from_fn(super::request_id::attach_request_id))
        .with_state(state)
}
fn error_response(status: StatusCode, code: &'static str, request_id: &str) -> Response {
    (status,Json(json!({"code":code,"message":"Streaming could not serve the private context.","requestId":request_id}))).into_response()
}
async fn context<R: StreamingRepository + 'static, D: DiscoveryProjectionRepository + 'static>(
    State(state): State<Arc<ContextState<R, D>>>,
    Path(id): Path<String>,
    headers: HeaderMap,
    Extension(request_id): Extension<super::request_id::RequestId>,
) -> Response {
    let error = |status, code| error_response(status, code, &request_id.0);
    if !valid_service_authorization(&headers, state.token.as_ref()) {
        return error(StatusCode::UNAUTHORIZED, "SERVICE_AUTH_REQUIRED");
    }
    match state.session.execute(id).await {
        Ok(view) => {
            let sampled = view
                .snapshot
                .timeline_sampled_at
                .unwrap_or_else(time::OffsetDateTime::now_utc)
                .format(&Rfc3339);
            match sampled { Ok(sampled)=>(StatusCode::OK,Json(json!({"streamId":view.snapshot.stream_id,"sessionId":view.snapshot.session_id,"streamGeneration":view.snapshot.stream_generation,"sessionVersion":view.snapshot.session_version,"status":view.snapshot.status.as_public_str(),"availability":view.snapshot.availability.as_db_str(),"timelinePositionMs":view.snapshot.timeline_position_ms,"timelineSampledAtUtc":sampled}))).into_response(),Err(_)=>error(StatusCode::SERVICE_UNAVAILABLE,"STREAMING_UNAVAILABLE") }
        }
        Err(GetPublicStreamSessionError::NotFound) => {
            error(StatusCode::NOT_FOUND, "SESSION_NOT_FOUND")
        }
        Err(_) => error(StatusCode::SERVICE_UNAVAILABLE, "STREAMING_UNAVAILABLE"),
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ChannelsRequest {
    channel_ids: Vec<String>,
}
async fn channels<R: StreamingRepository + 'static, D: DiscoveryProjectionRepository + 'static>(
    State(state): State<Arc<ContextState<R, D>>>,
    headers: HeaderMap,
    Extension(request_id): Extension<super::request_id::RequestId>,
    request: Result<Json<ChannelsRequest>, axum::extract::rejection::JsonRejection>,
) -> Response {
    let error = |status, code| error_response(status, code, &request_id.0);
    if !valid_service_authorization(&headers, state.token.as_ref()) {
        return error(StatusCode::UNAUTHORIZED, "SERVICE_AUTH_REQUIRED");
    }
    let request = match request {
        Ok(Json(request)) => request,
        Err(rejection) => return error(rejection.status(), "INVALID_CHANNEL_IDS"),
    };
    match state.channels.execute(request.channel_ids).await {
        Ok(value) => (StatusCode::OK, Json(value)).into_response(),
        Err(crate::application::ports::streaming_repository::RepositoryError::Conflict) => {
            error(StatusCode::UNPROCESSABLE_ENTITY, "INVALID_CHANNEL_IDS")
        }
        Err(_) => error(StatusCode::SERVICE_UNAVAILABLE, "STREAMING_UNAVAILABLE"),
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SnapshotRequest {
    limit: i32,
    cursor: Option<String>,
}
async fn discovery_page<
    R: StreamingRepository + 'static,
    D: DiscoveryProjectionRepository + 'static,
>(
    State(state): State<Arc<ContextState<R, D>>>,
    headers: HeaderMap,
    Extension(request_id): Extension<super::request_id::RequestId>,
    request: Result<Json<SnapshotRequest>, axum::extract::rejection::JsonRejection>,
) -> Response {
    let error = |status, code| error_response(status, code, &request_id.0);
    if !valid_service_authorization(&headers, state.token.as_ref()) {
        return error(StatusCode::UNAUTHORIZED, "SERVICE_AUTH_REQUIRED");
    }
    let request = match request {
        Ok(Json(request)) => request,
        Err(rejection) => return error(rejection.status(), "INVALID_SNAPSHOT_REQUEST"),
    };
    match state.discovery.page(request.limit, request.cursor).await {
        Ok(value) => (StatusCode::OK, Json(value)).into_response(),
        Err(ProjectionError::InvalidRequest) => {
            error(StatusCode::UNPROCESSABLE_ENTITY, "INVALID_SNAPSHOT_REQUEST")
        }
        Err(ProjectionError::Expired) => error(StatusCode::GONE, "SNAPSHOT_EXPIRED"),
        Err(_) => error(StatusCode::SERVICE_UNAVAILABLE, "STREAMING_UNAVAILABLE"),
    }
}
