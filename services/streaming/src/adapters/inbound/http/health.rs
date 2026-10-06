use std::sync::Arc;

use axum::{Json, Router, extract::State, http::StatusCode, response::IntoResponse, routing::get};
use serde::Serialize;

use crate::{
    adapters::inbound::http::request_id::RequestId,
    application::{ports::readiness_probe::ReadinessProbe, use_cases::CheckReadiness},
};

pub(super) fn router<P>(readiness: Arc<CheckReadiness<P>>) -> Router
where
    P: ReadinessProbe + 'static,
{
    Router::new()
        .route("/health/live", get(liveness))
        .route("/health/ready", get(readiness_check::<P>))
        .with_state(readiness)
}

async fn liveness() -> impl IntoResponse {
    (StatusCode::OK, Json(HealthResponse { status: "ok" }))
}

async fn readiness_check<P>(
    State(readiness): State<Arc<CheckReadiness<P>>>,
    request_id: Option<axum::Extension<RequestId>>,
) -> impl IntoResponse
where
    P: ReadinessProbe + 'static,
{
    match readiness.execute().await {
        Ok(()) => (StatusCode::OK, Json(HealthResponse { status: "ready" })).into_response(),
        Err(_) => {
            let request_id = request_id
                .map(|axum::Extension(id)| id.0)
                .unwrap_or_else(|| "req_unavailable".to_owned());
            (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(ReadinessError {
                    code: "DEPENDENCY_UNAVAILABLE",
                    message: "Streaming dependencies are not ready.",
                    request_id,
                }),
            )
                .into_response()
        }
    }
}

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ReadinessError {
    code: &'static str,
    message: &'static str,
    request_id: String,
}
