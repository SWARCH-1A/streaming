use std::{sync::Arc, time::Duration};

use axum::{
    Json, Router,
    extract::{Path, State},
    http::{StatusCode, header::CACHE_CONTROL},
    middleware,
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Serialize;

use crate::application::{
    ports::{renditions::RenditionsGateway, streaming_repository::StreamingRepository},
    use_cases::{GetPublicStreamSession, GetPublicStreamSessionError},
};

use super::request_id::{self, RequestId};

type SharedState<R, M> = Arc<(Arc<GetPublicStreamSession<R>>, Arc<M>)>;

pub(crate) fn router<R, M>(session: Arc<GetPublicStreamSession<R>>, media: Arc<M>) -> Router
where
    R: StreamingRepository + 'static,
    M: RenditionsGateway + 'static,
{
    Router::new()
        .route(
            "/api/streams/sessions/{session_id}/renditions",
            get(get_renditions::<R, M>),
        )
        .with_state(Arc::new((session, media)))
        .layer(middleware::from_fn(request_id::attach_request_id))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RenditionResponse {
    rendition_id: String,
    width: u32,
    height: u32,
    bitrate_bps: u64,
    video_codec: String,
    audio_codec: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RenditionsResponse {
    session_id: String,
    renditions: Vec<RenditionResponse>,
    master_playlist_uri: String,
}

async fn get_renditions<R, M>(
    State(state): State<SharedState<R, M>>,
    Path(session_id): Path<String>,
    axum::Extension(RequestId(request_id)): axum::Extension<RequestId>,
) -> Response
where
    R: StreamingRepository + 'static,
    M: RenditionsGateway + 'static,
{
    let response = match tokio::time::timeout(
        Duration::from_secs(2),
        state.0.renditions(session_id, state.1.as_ref()),
    )
    .await
    {
        Ok(Ok(view)) => Json(RenditionsResponse {
            session_id: view.session_id.as_str().to_owned(),
            master_playlist_uri: view.master_playlist_uri,
            renditions: view
                .renditions
                .into_iter()
                .map(|rendition| RenditionResponse {
                    rendition_id: rendition.rendition_id,
                    width: rendition.width,
                    height: rendition.height,
                    bitrate_bps: rendition.bitrate_bps,
                    video_codec: rendition.video_codec,
                    audio_codec: rendition.audio_codec,
                })
                .collect(),
        })
        .into_response(),
        Ok(Err(GetPublicStreamSessionError::NotFound)) => error(
            StatusCode::NOT_FOUND,
            "STREAM_SESSION_NOT_FOUND",
            request_id,
        ),
        Ok(Err(_)) => error(
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAM_RENDITIONS_UNAVAILABLE",
            request_id,
        ),
        Err(_) => error(
            StatusCode::GATEWAY_TIMEOUT,
            "STREAM_RENDITIONS_TIMEOUT",
            request_id,
        ),
    };
    ([(CACHE_CONTROL, "no-store")], response).into_response()
}

fn error(status: StatusCode, code: &str, request_id: String) -> Response {
    (
        status,
        Json(serde_json::json!({
            "code": code,
            "message": "The stream renditions could not be read.",
            "requestId": request_id,
        })),
    )
        .into_response()
}
