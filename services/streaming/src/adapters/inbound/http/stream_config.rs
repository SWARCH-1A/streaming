mod auth;
mod dto;
mod errors;
mod handlers;

use std::sync::Arc;

use axum::{
    Router,
    extract::DefaultBodyLimit,
    routing::{get, post},
};

use crate::application::use_cases::{
    CreateStreamConfig, GetPublicStream, GetPublicStreamSession, GetStreamConfig,
    PatchStreamMetadata, RotateIngestKey, StopStreamSession,
};

const MAX_REQUEST_BYTES: usize = 8192;

struct StreamConfigHttpState<I, C, T, R, G, S> {
    create_stream_config: Arc<CreateStreamConfig<I, R, G>>,
    get_stream_config: Arc<GetStreamConfig<I, C, S>>,
    public_stream: Arc<GetPublicStream<S, T>>,
    get_public_stream_session: Arc<GetPublicStreamSession<S>>,
    patch_stream_metadata: Arc<PatchStreamMetadata<I, R, S>>,
    rotate_ingest_key: Arc<RotateIngestKey<I, R, S, G>>,
    stop_stream_session: Arc<StopStreamSession<I, S>>,
    session_cookie_name: Option<String>,
    web_origin: Option<String>,
}

pub(super) struct PublicStreamQueries<S, T> {
    pub stream: Arc<GetPublicStream<S, T>>,
    pub session: Arc<GetPublicStreamSession<S>>,
}

pub(super) struct StreamConfigSecurity {
    pub session_cookie_name: Option<String>,
    pub web_origin: Option<String>,
}

type SharedStreamConfigHttpState<I, C, T, R, G, S> = Arc<StreamConfigHttpState<I, C, T, R, G, S>>;

pub(super) fn router<I, C, T, R, G, S>(
    create_stream_config: Arc<CreateStreamConfig<I, R, G>>,
    get_stream_config: Arc<GetStreamConfig<I, C, S>>,
    public_stream_queries: PublicStreamQueries<S, T>,
    patch_stream_metadata: Arc<PatchStreamMetadata<I, R, S>>,
    rotate_ingest_key: Arc<RotateIngestKey<I, R, S, G>>,
    stop_stream_session: Arc<StopStreamSession<I, S>>,
    security: StreamConfigSecurity,
) -> Router
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
    let PublicStreamQueries {
        stream: public_stream,
        session: get_public_stream_session,
    } = public_stream_queries;
    let state = Arc::new(StreamConfigHttpState {
        create_stream_config,
        get_stream_config,
        public_stream,
        get_public_stream_session,
        patch_stream_metadata,
        rotate_ingest_key,
        stop_stream_session,
        session_cookie_name: security.session_cookie_name,
        web_origin: security.web_origin,
    });
    Router::new()
        .route(
            "/api/channels/{channel_id}/streams",
            get(handlers::get_config::<I, C, T, R, G, S>)
                .post(handlers::create::<I, C, T, R, G, S>),
        )
        .route(
            "/api/streams/{stream_id}",
            get(handlers::get_public_stream::<I, C, T, R, G, S>)
                .patch(handlers::patch::<I, C, T, R, G, S>),
        )
        .route(
            "/api/streams/{stream_id}/ingest-keys/rotate",
            post(handlers::rotate::<I, C, T, R, G, S>),
        )
        .route(
            "/api/streams/sessions/{session_id}",
            get(handlers::get_public_session::<I, C, T, R, G, S>)
                .delete(handlers::stop::<I, C, T, R, G, S>),
        )
        .layer(DefaultBodyLimit::max(MAX_REQUEST_BYTES))
        .with_state(state)
}
