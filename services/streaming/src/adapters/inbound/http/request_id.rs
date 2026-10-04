use std::time::Instant;

use axum::{
    extract::Request,
    http::{HeaderName, HeaderValue},
    middleware::Next,
    response::Response,
};
use uuid::Uuid;

const REQUEST_ID_HEADER: HeaderName = HeaderName::from_static("x-request-id");

pub async fn attach_request_id(mut request: Request, next: Next) -> Response {
    let request_id = request
        .headers()
        .get(&REQUEST_ID_HEADER)
        .and_then(|value| value.to_str().ok())
        .filter(|value| {
            !value.is_empty()
                && value.len() <= 128
                && value.bytes().all(|byte| byte.is_ascii_graphic())
        })
        .map(str::to_owned)
        .unwrap_or_else(|| format!("req_{}", Uuid::now_v7()));

    let method = request.method().clone();
    let path = request.uri().path().to_owned();
    let started_at = Instant::now();
    request
        .extensions_mut()
        .insert(RequestId(request_id.clone()));
    let mut response = next.run(request).await;
    tracing::info!(
        request_id = %request_id,
        method = %method,
        path = %path,
        status = response.status().as_u16(),
        latency_ms = started_at.elapsed().as_millis(),
        "http request completed"
    );
    if let Ok(value) = HeaderValue::from_str(&request_id) {
        response.headers_mut().insert(REQUEST_ID_HEADER, value);
    }
    response
}

#[derive(Clone, Debug)]
pub struct RequestId(pub String);
