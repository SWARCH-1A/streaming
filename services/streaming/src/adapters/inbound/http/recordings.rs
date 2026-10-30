use super::internal_auth;
use axum::{
    Json, Router,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, put},
};
use sqlx::PgPool;
#[derive(Clone)]
struct RecordingState {
    pool: PgPool,
    token: Option<[u8; 32]>,
}
pub(crate) fn router(pool: PgPool, token: Option<&str>) -> Router {
    Router::new()
        .route("/internal/streaming/recordings", get(list))
        .route(
            "/internal/streaming/recordings/{session}/acknowledged",
            put(ack),
        )
        .with_state(RecordingState {
            pool,
            token: internal_auth::service_token_hash(token),
        })
}
async fn list(State(s): State<RecordingState>, headers: HeaderMap) -> Response {
    if !internal_auth::valid_service_authorization(&headers, s.token.as_ref()) {
        return error(&headers, StatusCode::UNAUTHORIZED, "SERVICE_UNAUTHORIZED");
    }
    match crate::adapters::outbound::postgres::recordings::snapshots(&s.pool).await {
        Ok(v) => ([("Cache-Control", "no-store")], Json(v)).into_response(),
        Err(_) => error(
            &headers,
            StatusCode::SERVICE_UNAVAILABLE,
            "RECORDINGS_UNAVAILABLE",
        ),
    }
}
async fn ack(
    State(s): State<RecordingState>,
    Path(session): Path<String>,
    headers: HeaderMap,
) -> Response {
    if !internal_auth::valid_service_authorization(&headers, s.token.as_ref()) {
        return error(&headers, StatusCode::UNAUTHORIZED, "SERVICE_UNAUTHORIZED");
    }
    if crate::domain::ids::SessionId::parse(session.clone()).is_none()
        || headers
            .get("Idempotency-Key")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| uuid::Uuid::parse_str(v).ok())
            .is_none()
    {
        return error(&headers, StatusCode::BAD_REQUEST, "VALIDATION_ERROR");
    }
    match crate::adapters::outbound::postgres::recordings::acknowledge(&s.pool, &session).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(_) => error(
            &headers,
            StatusCode::SERVICE_UNAVAILABLE,
            "RECORDINGS_UNAVAILABLE",
        ),
    }
}

fn error(headers: &HeaderMap, status: StatusCode, code: &str) -> Response {
    let request_id = headers
        .get("X-Request-ID")
        .and_then(|v| v.to_str().ok())
        .filter(|v| {
            v.len() <= 128
                && v.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        })
        .map(str::to_owned)
        .unwrap_or_else(|| format!("req_{}", uuid::Uuid::new_v4()));
    (status,[("Cache-Control","no-store")],Json(serde_json::json!({"code":code,"message":"No se pudo completar la operación de grabación.","requestId":request_id}))).into_response()
}
