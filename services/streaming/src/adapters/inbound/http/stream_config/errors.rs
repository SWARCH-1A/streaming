use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};

use crate::application::use_cases::{
    CreateStreamConfigError, GetPublicStreamError, GetPublicStreamSessionError,
    GetStreamConfigError, PatchStreamMetadataError, RotateIngestKeyError, StopStreamSessionError,
};

use super::dto::ApiError;

pub(super) fn invalid_patch_field(request_id: String) -> Response {
    api_error(
        StatusCode::UNPROCESSABLE_ENTITY,
        "INVALID_STREAM_METADATA",
        "Metadata fields cannot be null.",
        request_id,
    )
}

pub(super) fn map_error(error: CreateStreamConfigError, request_id: String) -> Response {
    let (status, code, message) = match error {
        CreateStreamConfigError::InvalidRequest => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "INVALID_STREAM_CONFIG",
            "The stream configuration fields are invalid.",
        ),
        CreateStreamConfigError::IdentityInactive => (
            StatusCode::UNAUTHORIZED,
            "SESSION_INVALID",
            "The session is invalid or has expired.",
        ),
        CreateStreamConfigError::IdentityUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "IDENTITY_UNAVAILABLE",
            "Identity could not validate the current session.",
        ),
        CreateStreamConfigError::ChannelNotFound => (
            StatusCode::NOT_FOUND,
            "CHANNEL_NOT_FOUND",
            "The channel could not be found.",
        ),
        CreateStreamConfigError::Forbidden => (
            StatusCode::FORBIDDEN,
            "CHANNEL_OWNER_REQUIRED",
            "Only the channel owner can create its stream configuration.",
        ),
        CreateStreamConfigError::TaxonomyValueInactiveOrUnknown => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "UNKNOWN_OR_INACTIVE_TAXONOMY_VALUE",
            "The category or one of the tags is unavailable.",
        ),
        CreateStreamConfigError::TaxonomyUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "TAXONOMY_UNAVAILABLE",
            "Taxonomy could not validate the selected values.",
        ),
        CreateStreamConfigError::IdempotencyKeyReused => (
            StatusCode::CONFLICT,
            "IDEMPOTENCY_KEY_REUSED",
            "The Idempotency-Key was already used with a different request.",
        ),
        CreateStreamConfigError::SecretGenerationUnavailable
        | CreateStreamConfigError::Unavailable
        | CreateStreamConfigError::InvalidStoredData => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming could not create the configuration.",
        ),
    };
    api_error(status, code, message, request_id)
}

pub(super) fn map_get_error(error: GetStreamConfigError, request_id: String) -> Response {
    let (status, code, message) = match error {
        GetStreamConfigError::IdentityInactive => (
            StatusCode::UNAUTHORIZED,
            "SESSION_INVALID",
            "The session is invalid or has expired.",
        ),
        GetStreamConfigError::IdentityUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "IDENTITY_UNAVAILABLE",
            "Identity could not validate the current session.",
        ),
        GetStreamConfigError::ChannelNotFound | GetStreamConfigError::NotFound => (
            StatusCode::NOT_FOUND,
            "STREAM_CONFIG_NOT_FOUND",
            "The channel stream configuration could not be found.",
        ),
        GetStreamConfigError::Forbidden => (
            StatusCode::FORBIDDEN,
            "CHANNEL_OWNER_REQUIRED",
            "Only the channel owner can read its stream configuration.",
        ),
        GetStreamConfigError::Unavailable | GetStreamConfigError::InvalidStoredData => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming could not read the configuration.",
        ),
    };
    api_error(status, code, message, request_id)
}

pub(super) fn map_public_session_error(
    error: GetPublicStreamSessionError,
    request_id: String,
) -> Response {
    let (status, code, message) = match error {
        GetPublicStreamSessionError::NotFound => (
            StatusCode::NOT_FOUND,
            "STREAM_SESSION_NOT_FOUND",
            "The streaming session could not be found.",
        ),
        GetPublicStreamSessionError::Unavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming is temporarily unavailable.",
        ),
        GetPublicStreamSessionError::InvalidStoredData => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming could not read the session.",
        ),
    };
    api_error(status, code, message, request_id)
}

pub(super) fn map_public_stream_error(error: GetPublicStreamError, request_id: String) -> Response {
    let (status, code, message) = match error {
        GetPublicStreamError::NotFound => (
            StatusCode::NOT_FOUND,
            "STREAM_NOT_FOUND",
            "The stream could not be found.",
        ),
        GetPublicStreamError::TaxonomyUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "TAXONOMY_UNAVAILABLE",
            "Streaming could not resolve stream metadata.",
        ),
        GetPublicStreamError::Unavailable | GetPublicStreamError::InvalidStoredData => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming could not read the stream state.",
        ),
    };
    api_error(status, code, message, request_id)
}

pub(super) fn map_patch_error(error: PatchStreamMetadataError, request_id: String) -> Response {
    let (status, code, message) = match error {
        PatchStreamMetadataError::EmptyPatch => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "EMPTY_PATCH",
            "At least one metadata field must be supplied.",
        ),
        PatchStreamMetadataError::InvalidRequest => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "INVALID_STREAM_METADATA",
            "The metadata fields are invalid.",
        ),
        PatchStreamMetadataError::IdentityInactive => (
            StatusCode::UNAUTHORIZED,
            "SESSION_INVALID",
            "The session is invalid or has expired.",
        ),
        PatchStreamMetadataError::IdentityUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "IDENTITY_UNAVAILABLE",
            "Identity could not validate the current session.",
        ),
        PatchStreamMetadataError::NotFound => (
            StatusCode::NOT_FOUND,
            "STREAM_NOT_FOUND",
            "The stream configuration could not be found.",
        ),
        PatchStreamMetadataError::Forbidden => (
            StatusCode::FORBIDDEN,
            "STREAM_OWNER_REQUIRED",
            "Only the stream owner can update its metadata.",
        ),
        PatchStreamMetadataError::TaxonomyValueInactiveOrUnknown => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "UNKNOWN_OR_INACTIVE_TAXONOMY_VALUE",
            "The category or one of the tags is unavailable.",
        ),
        PatchStreamMetadataError::TaxonomyUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "TAXONOMY_UNAVAILABLE",
            "Taxonomy could not validate the selected values.",
        ),
        PatchStreamMetadataError::StreamTransitionInProgress => (
            StatusCode::CONFLICT,
            "STREAM_METADATA_LOCKED",
            "Metadata cannot change while the stream is preparing or reconnecting.",
        ),
        PatchStreamMetadataError::Unavailable | PatchStreamMetadataError::InvalidStoredData => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming could not update the metadata.",
        ),
    };
    api_error(status, code, message, request_id)
}

pub(super) fn map_rotate_error(error: RotateIngestKeyError, request_id: String) -> Response {
    let (status, code, message) = match error {
        RotateIngestKeyError::IdentityInactive => (
            StatusCode::UNAUTHORIZED,
            "SESSION_INVALID",
            "The session is invalid or has expired.",
        ),
        RotateIngestKeyError::IdentityUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "IDENTITY_UNAVAILABLE",
            "Identity could not validate the current session.",
        ),
        RotateIngestKeyError::NotFound => (
            StatusCode::NOT_FOUND,
            "STREAM_NOT_FOUND",
            "The stream configuration could not be found.",
        ),
        RotateIngestKeyError::Forbidden => (
            StatusCode::FORBIDDEN,
            "STREAM_OWNER_REQUIRED",
            "Only the stream owner can rotate its ingest key.",
        ),
        RotateIngestKeyError::ActiveSession => (
            StatusCode::CONFLICT,
            "STREAM_ACTIVE",
            "The ingest key can only be rotated while the stream is offline.",
        ),
        RotateIngestKeyError::Unavailable | RotateIngestKeyError::InvalidStoredData => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming could not rotate the ingest key.",
        ),
    };
    api_error(status, code, message, request_id)
}

pub(super) fn map_stop_error(error: StopStreamSessionError, request_id: String) -> Response {
    let (status, code, message) = match error {
        StopStreamSessionError::IdentityInactive => (
            StatusCode::UNAUTHORIZED,
            "SESSION_INVALID",
            "The session is invalid or has expired.",
        ),
        StopStreamSessionError::IdentityUnavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "IDENTITY_UNAVAILABLE",
            "Identity could not validate the current session.",
        ),
        StopStreamSessionError::NotFound => (
            StatusCode::NOT_FOUND,
            "STREAM_SESSION_NOT_FOUND",
            "The stream session could not be found.",
        ),
        StopStreamSessionError::Forbidden => (
            StatusCode::FORBIDDEN,
            "STREAM_OWNER_REQUIRED",
            "Only the stream owner can stop its session.",
        ),
        StopStreamSessionError::Unavailable | StopStreamSessionError::InvalidStoredData => (
            StatusCode::SERVICE_UNAVAILABLE,
            "STREAMING_UNAVAILABLE",
            "Streaming could not stop the session.",
        ),
    };
    api_error(status, code, message, request_id)
}

pub(super) fn api_error(
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
