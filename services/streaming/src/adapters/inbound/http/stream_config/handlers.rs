use axum::{
    Json,
    extract::{Path, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode, header::CACHE_CONTROL},
    response::{IntoResponse, Response},
};
use uuid::Uuid;

use crate::{
    adapters::inbound::http::request_id::RequestId,
    application::use_cases::{CreateStreamConfigRequest, PatchStreamMetadataRequest},
};

use super::{
    SharedStreamConfigHttpState,
    auth::{matches_web_origin, session_credential},
    dto::{
        CreateStreamConfigBody, CreateStreamConfigResponse, GetStreamConfigResponse,
        PatchStreamMetadataBody, PatchStreamMetadataResponse, PublicStreamResponse,
        PublicStreamSessionResponse, PublicTaxonomyValueResponse, RotateIngestKeyResponse,
    },
    errors::{
        api_error, invalid_patch_field, map_error, map_get_error, map_patch_error,
        map_public_session_error, map_public_stream_error, map_rotate_error, map_stop_error,
    },
};

#[derive(Clone, Copy)]
enum WebOriginError {
    NotConfigured,
    Rejected,
}

fn check_web_origin(
    headers: &HeaderMap,
    allowed_origin: Option<&str>,
) -> Result<(), WebOriginError> {
    let Some(allowed_origin) = allowed_origin else {
        return Err(WebOriginError::NotConfigured);
    };
    if matches_web_origin(headers, allowed_origin) {
        Ok(())
    } else {
        Err(WebOriginError::Rejected)
    }
}

fn web_origin_error_response(error: WebOriginError, request_id: String) -> Response {
    match error {
        WebOriginError::NotConfigured => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_CONFIGURATION_INCOMPLETE",
            "Streaming browser origin is not configured.",
            request_id,
        ),
        WebOriginError::Rejected => api_error(
            StatusCode::FORBIDDEN,
            "CSRF_ORIGIN_REJECTED",
            "The request origin is not permitted.",
            request_id,
        ),
    }
}

pub(super) async fn create<I, C, T, R, G, S>(
    State(state): State<SharedStreamConfigHttpState<I, C, T, R, G, S>>,
    Path(channel_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
    request: Result<Json<CreateStreamConfigBody>, JsonRejection>,
) -> Response
where
    I: crate::application::ports::identity::IdentityGateway
        + crate::application::ports::owner_context::OwnerContextGateway
        + 'static,
    C: crate::application::ports::channels::ChannelsGateway + 'static,
    T: crate::application::ports::taxonomy::TaxonomyGateway + 'static,
    R: crate::application::ports::stream_config::StreamConfigRepository + 'static,
    G: crate::application::ports::stream_config::SecretGenerator + 'static,
    S: crate::application::ports::streaming_repository::StreamingRepository + 'static,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    let request = match request {
        Ok(Json(request)) => request,
        Err(rejection) => {
            return api_error(
                rejection.status(),
                "INVALID_STREAM_CONFIG",
                "The stream configuration body is invalid.",
                request_id,
            );
        }
    };
    let Some(cookie_name) = state.session_cookie_name.as_deref() else {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_CONFIGURATION_INCOMPLETE",
            "Streaming authentication is not configured.",
            request_id,
        );
    };
    if let Err(error) = check_web_origin(&headers, state.web_origin.as_deref()) {
        return web_origin_error_response(error, request_id);
    }
    let Some(credential) = session_credential(&headers, cookie_name) else {
        return api_error(
            StatusCode::UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "A valid session credential is required.",
            request_id,
        );
    };
    let idempotency_key = match headers
        .get("Idempotency-Key")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| Uuid::parse_str(value).ok())
    {
        Some(value) => value,
        None => {
            return api_error(
                StatusCode::BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED",
                "A UUID Idempotency-Key header is required.",
                request_id,
            );
        }
    };

    match state
        .create_stream_config
        .execute(
            credential,
            CreateStreamConfigRequest {
                channel_id,
                title: request.title,
                category_id: request.category_id,
                tag_ids: request.tag_ids,
                idempotency_key,
            },
        )
        .await
    {
        Ok(created) => {
            let status = if created.created {
                StatusCode::CREATED
            } else {
                StatusCode::OK
            };
            (
                status,
                Json(CreateStreamConfigResponse {
                    stream_id: created.stream_id.as_str().to_owned(),
                    channel_id: created.channel_id,
                    title: created.title,
                    category_id: created.category_id,
                    tag_ids: created.tag_ids,
                    metadata_version: created.metadata_version,
                    rtmp_url: created.rtmp_url,
                    stream_key: created.stream_key,
                }),
            )
                .into_response()
        }
        Err(error) => map_error(error, request_id),
    }
}

pub(super) async fn get_config<I, C, T, R, G, S>(
    State(state): State<SharedStreamConfigHttpState<I, C, T, R, G, S>>,
    Path(channel_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
) -> Response
where
    I: crate::application::ports::identity::IdentityGateway
        + crate::application::ports::owner_context::OwnerContextGateway
        + 'static,
    C: crate::application::ports::channels::ChannelsGateway + 'static,
    T: crate::application::ports::taxonomy::TaxonomyGateway + 'static,
    R: crate::application::ports::stream_config::StreamConfigRepository + 'static,
    G: crate::application::ports::stream_config::SecretGenerator + 'static,
    S: crate::application::ports::streaming_repository::StreamingRepository + 'static,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    let Some(cookie_name) = state.session_cookie_name.as_deref() else {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_CONFIGURATION_INCOMPLETE",
            "Streaming authentication is not configured.",
            request_id,
        );
    };
    let Some(credential) = session_credential(&headers, cookie_name) else {
        return api_error(
            StatusCode::UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "A valid session credential is required.",
            request_id,
        );
    };

    match state
        .get_stream_config
        .execute(credential, channel_id)
        .await
    {
        Ok(view) => {
            let (status, availability, session_id, session_version) =
                if let Some(session) = view.active_session {
                    (
                        session.status.as_public_str(),
                        session.availability.as_db_str(),
                        Some(session.session_id.as_str().to_owned()),
                        Some(session.session_version),
                    )
                } else {
                    ("OFFLINE", "OFFLINE", None, None)
                };
            (
                StatusCode::OK,
                Json(GetStreamConfigResponse {
                    stream_id: view.config.stream_id.as_str().to_owned(),
                    channel_id: view.config.channel_id,
                    title: view.config.title,
                    category_id: view.config.category_id,
                    tag_ids: view.config.tag_ids,
                    metadata_version: view.config.metadata_version,
                    stream_generation: view.config.stream_generation,
                    rtmp_url: view.rtmp_url,
                    status,
                    availability,
                    status_fresh: true,
                    session_id,
                    session_version,
                }),
            )
                .into_response()
        }
        Err(error) => map_get_error(error, request_id),
    }
}

pub(super) async fn get_public_stream<I, C, T, R, G, S>(
    State(state): State<SharedStreamConfigHttpState<I, C, T, R, G, S>>,
    Path(stream_id): Path<String>,
    request_id: Option<axum::Extension<RequestId>>,
) -> Response
where
    I: crate::application::ports::identity::IdentityGateway
        + crate::application::ports::owner_context::OwnerContextGateway
        + 'static,
    C: crate::application::ports::channels::ChannelsGateway + 'static,
    T: crate::application::ports::taxonomy::TaxonomyGateway + 'static,
    R: crate::application::ports::stream_config::StreamConfigRepository + 'static,
    G: crate::application::ports::stream_config::SecretGenerator + 'static,
    S: crate::application::ports::streaming_repository::StreamingRepository + 'static,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    match state.public_stream.execute(stream_id).await {
        Ok(view) => {
            let viewer_count_observed_at_utc = match view
                .snapshot
                .session
                .as_ref()
                .and_then(|session| session.viewer_count_observed_at)
                .map(|value| value.format(&time::format_description::well_known::Rfc3339))
                .transpose()
            {
                Ok(value) => value,
                Err(_) => {
                    return api_error(
                        StatusCode::SERVICE_UNAVAILABLE,
                        "STREAMING_UNAVAILABLE",
                        "Streaming could not read the stream state.",
                        request_id,
                    );
                }
            };
            let (session_id, session_version, viewer_count, count_version, status, availability) =
                if let Some(session) = view.snapshot.session.as_ref() {
                    (
                        Some(session.session_id.as_str().to_owned()),
                        Some(session.session_version),
                        Some(session.viewer_count),
                        Some(session.count_version),
                        session.status.as_public_str(),
                        session.availability.as_db_str(),
                    )
                } else {
                    (None, None, None, None, "OFFLINE", "OFFLINE")
                };
            (
                StatusCode::OK,
                [(CACHE_CONTROL, "no-store")],
                Json(PublicStreamResponse {
                    stream_id: view.snapshot.stream_id.as_str().to_owned(),
                    channel_id: view.snapshot.channel_id,
                    session_id,
                    stream_generation: view.snapshot.stream_generation,
                    title: view.snapshot.title,
                    category: PublicTaxonomyValueResponse {
                        id: view.category.id,
                        name: view.category.name,
                    },
                    tags: view
                        .tags
                        .into_iter()
                        .map(|tag| PublicTaxonomyValueResponse {
                            id: tag.id,
                            name: tag.name,
                        })
                        .collect(),
                    status,
                    availability,
                    status_fresh: true,
                    viewer_count,
                    count_version,
                    viewer_count_observed_at_utc,
                    metadata_version: view.snapshot.metadata_version,
                    session_version,
                }),
            )
                .into_response()
        }
        Err(error) => map_public_stream_error(error, request_id),
    }
}

pub(super) async fn get_public_session<I, C, T, R, G, S>(
    State(state): State<SharedStreamConfigHttpState<I, C, T, R, G, S>>,
    Path(session_id): Path<String>,
    request_id: Option<axum::Extension<RequestId>>,
) -> Response
where
    I: crate::application::ports::identity::IdentityGateway
        + crate::application::ports::owner_context::OwnerContextGateway
        + 'static,
    C: crate::application::ports::channels::ChannelsGateway + 'static,
    T: crate::application::ports::taxonomy::TaxonomyGateway + 'static,
    R: crate::application::ports::stream_config::StreamConfigRepository + 'static,
    G: crate::application::ports::stream_config::SecretGenerator + 'static,
    S: crate::application::ports::streaming_repository::StreamingRepository + 'static,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    match state.get_public_stream_session.execute(session_id).await {
        Ok(view) => {
            let timeline_sampled_at_utc = match view
                .snapshot
                .timeline_sampled_at
                .map(|value| value.format(&time::format_description::well_known::Rfc3339))
                .transpose()
            {
                Ok(value) => value,
                Err(_) => {
                    return api_error(
                        StatusCode::SERVICE_UNAVAILABLE,
                        "STREAMING_UNAVAILABLE",
                        "Streaming could not read the session.",
                        request_id,
                    );
                }
            };
            let viewer_count_observed_at_utc = match view
                .snapshot
                .viewer_count_observed_at
                .map(|value| value.format(&time::format_description::well_known::Rfc3339))
                .transpose()
            {
                Ok(value) => value,
                Err(_) => {
                    return api_error(
                        StatusCode::SERVICE_UNAVAILABLE,
                        "STREAMING_UNAVAILABLE",
                        "Streaming could not read the session.",
                        request_id,
                    );
                }
            };
            (
                StatusCode::OK,
                [(CACHE_CONTROL, "no-store")],
                Json(PublicStreamSessionResponse {
                    session_id: view.snapshot.session_id.as_str().to_owned(),
                    stream_id: view.snapshot.stream_id.as_str().to_owned(),
                    channel_id: view.snapshot.channel_id,
                    stream_generation: view.snapshot.stream_generation,
                    status: view.snapshot.status.as_public_str(),
                    availability: view.snapshot.availability.as_db_str(),
                    playback_url: view.playback_url,
                    timeline_position_ms: view.snapshot.timeline_position_ms,
                    timeline_sampled_at_utc,
                    metadata_version: view.snapshot.metadata_version,
                    session_version: view.snapshot.session_version,
                    viewer_count: view.snapshot.viewer_count,
                    count_version: view.snapshot.count_version,
                    viewer_count_observed_at_utc,
                }),
            )
                .into_response()
        }
        Err(error) => map_public_session_error(error, request_id),
    }
}

pub(super) async fn patch<I, C, T, R, G, S>(
    State(state): State<SharedStreamConfigHttpState<I, C, T, R, G, S>>,
    Path(stream_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
    request: Result<Json<PatchStreamMetadataBody>, JsonRejection>,
) -> Response
where
    I: crate::application::ports::identity::IdentityGateway
        + crate::application::ports::owner_context::OwnerContextGateway
        + 'static,
    C: crate::application::ports::channels::ChannelsGateway + 'static,
    T: crate::application::ports::taxonomy::TaxonomyGateway + 'static,
    R: crate::application::ports::stream_config::StreamConfigRepository + 'static,
    G: crate::application::ports::stream_config::SecretGenerator + 'static,
    S: crate::application::ports::streaming_repository::StreamingRepository + 'static,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    let request = match request {
        Ok(Json(request)) => request,
        Err(rejection) => {
            return api_error(
                rejection.status(),
                "INVALID_STREAM_METADATA",
                "The stream metadata body is invalid.",
                request_id,
            );
        }
    };
    let Some(cookie_name) = state.session_cookie_name.as_deref() else {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_CONFIGURATION_INCOMPLETE",
            "Streaming authentication is not configured.",
            request_id,
        );
    };
    if let Err(error) = check_web_origin(&headers, state.web_origin.as_deref()) {
        return web_origin_error_response(error, request_id);
    }
    let Some(credential) = session_credential(&headers, cookie_name) else {
        return api_error(
            StatusCode::UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "A valid session credential is required.",
            request_id,
        );
    };
    let title = match request.title.into_value() {
        Ok(value) => value,
        Err(()) => return invalid_patch_field(request_id),
    };
    let category_id = match request.category_id.into_value() {
        Ok(value) => value,
        Err(()) => return invalid_patch_field(request_id),
    };
    let tag_ids = match request.tag_ids.into_value() {
        Ok(value) => value,
        Err(()) => return invalid_patch_field(request_id),
    };

    match state
        .patch_stream_metadata
        .execute(
            credential,
            PatchStreamMetadataRequest {
                stream_id,
                title,
                category_id,
                tag_ids,
            },
        )
        .await
    {
        Ok(updated) => (
            StatusCode::OK,
            Json(PatchStreamMetadataResponse {
                stream_id: updated.stream_id.as_str().to_owned(),
                channel_id: updated.channel_id,
                title: updated.title,
                category_id: updated.category_id,
                tag_ids: updated.tag_ids,
                metadata_version: updated.metadata_version,
                changed_fields: updated.changed_fields,
            }),
        )
            .into_response(),
        Err(error) => map_patch_error(error, request_id),
    }
}

pub(super) async fn rotate<I, C, T, R, G, S>(
    State(state): State<SharedStreamConfigHttpState<I, C, T, R, G, S>>,
    Path(stream_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
) -> Response
where
    I: crate::application::ports::identity::IdentityGateway
        + crate::application::ports::owner_context::OwnerContextGateway
        + 'static,
    C: crate::application::ports::channels::ChannelsGateway + 'static,
    T: crate::application::ports::taxonomy::TaxonomyGateway + 'static,
    R: crate::application::ports::stream_config::StreamConfigRepository + 'static,
    G: crate::application::ports::stream_config::SecretGenerator + 'static,
    S: crate::application::ports::streaming_repository::StreamingRepository + 'static,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    let Some(cookie_name) = state.session_cookie_name.as_deref() else {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_CONFIGURATION_INCOMPLETE",
            "Streaming authentication is not configured.",
            request_id,
        );
    };
    if let Err(error) = check_web_origin(&headers, state.web_origin.as_deref()) {
        return web_origin_error_response(error, request_id);
    }
    let Some(credential) = session_credential(&headers, cookie_name) else {
        return api_error(
            StatusCode::UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "A valid session credential is required.",
            request_id,
        );
    };
    match state.rotate_ingest_key.execute(credential, stream_id).await {
        Ok(rotated) => (
            StatusCode::OK,
            Json(RotateIngestKeyResponse {
                stream_id: rotated.stream_id.as_str().to_owned(),
                channel_id: rotated.channel_id,
                rtmp_url: rotated.rtmp_url,
                stream_key: rotated.stream_key,
                ingest_key_version: rotated.ingest_key_version,
            }),
        )
            .into_response(),
        Err(error) => map_rotate_error(error, request_id),
    }
}

pub(super) async fn stop<I, C, T, R, G, S>(
    State(state): State<SharedStreamConfigHttpState<I, C, T, R, G, S>>,
    Path(session_id): Path<String>,
    headers: HeaderMap,
    request_id: Option<axum::Extension<RequestId>>,
) -> Response
where
    I: crate::application::ports::identity::IdentityGateway
        + crate::application::ports::owner_context::OwnerContextGateway
        + 'static,
    C: crate::application::ports::channels::ChannelsGateway + 'static,
    T: crate::application::ports::taxonomy::TaxonomyGateway + 'static,
    R: crate::application::ports::stream_config::StreamConfigRepository + 'static,
    G: crate::application::ports::stream_config::SecretGenerator + 'static,
    S: crate::application::ports::streaming_repository::StreamingRepository + 'static,
{
    let request_id = request_id
        .map(|axum::Extension(id)| id.0)
        .unwrap_or_else(|| "req_unavailable".to_owned());
    let Some(cookie_name) = state.session_cookie_name.as_deref() else {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_CONFIGURATION_INCOMPLETE",
            "Streaming authentication is not configured.",
            request_id,
        );
    };
    if let Err(error) = check_web_origin(&headers, state.web_origin.as_deref()) {
        return web_origin_error_response(error, request_id);
    }
    let Some(credential) = session_credential(&headers, cookie_name) else {
        return api_error(
            StatusCode::UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "A valid session credential is required.",
            request_id,
        );
    };
    match state
        .stop_stream_session
        .execute(credential, session_id)
        .await
    {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => map_stop_error(error, request_id),
    }
}
