mod health;
mod ingest_authorization;
mod internal_auth;
mod media_callbacks;
mod request_id;
mod stream_config;
mod viewer_leases;

use std::sync::Arc;

use axum::{Router, middleware};

use crate::application::{
    ports::{
        ingest_authorization::IngestAuthorizationRepository,
        media_callbacks::MediaCallbackRepository,
        viewer_leases::{ViewerLeaseCredentialIssuer, ViewerLeaseRepository},
    },
    use_cases::{
        AuthorizeIngest, CheckReadiness, CloseViewerLease, CreateViewerLease, GetPublicStream,
        GetPublicStreamSession, HeartbeatViewerLease, RecordMediaCallback, RotateIngestKey,
        StopStreamSession,
    },
};

pub struct RouterDependencies<P, IR, MC, I, C, T, SC, G, SR, VL, VCI> {
    pub readiness: Arc<CheckReadiness<P>>,
    pub authorize_ingest: Arc<AuthorizeIngest<IR>>,
    pub record_media_callback: Arc<RecordMediaCallback<MC>>,
    pub create_stream_config: Arc<crate::application::use_cases::CreateStreamConfig<I, SC, G>>,
    pub get_stream_config: Arc<crate::application::use_cases::GetStreamConfig<I, C, SR>>,
    pub get_public_stream: Arc<GetPublicStream<SR, T>>,
    pub get_public_stream_session: Arc<GetPublicStreamSession<SR>>,
    pub patch_stream_metadata: Arc<crate::application::use_cases::PatchStreamMetadata<I, SC, SR>>,
    pub rotate_ingest_key: Arc<RotateIngestKey<I, SC, SR, G>>,
    pub stop_stream_session: Arc<StopStreamSession<I, SR>>,
    pub create_viewer_lease: Arc<CreateViewerLease<VCI, VL>>,
    pub heartbeat_viewer_lease: Arc<HeartbeatViewerLease<VL>>,
    pub close_viewer_lease: Arc<CloseViewerLease<VL>>,
    pub media_adapter_service_token: Option<String>,
    pub session_cookie_name: Option<String>,
    pub web_origin: Option<String>,
}

pub struct HttpRouters {
    pub public: Router,
    pub private: Router,
}

pub fn router<P, IR, MC, I, C, T, SC, G, SR, VL, VCI>(
    dependencies: RouterDependencies<P, IR, MC, I, C, T, SC, G, SR, VL, VCI>,
) -> HttpRouters
where
    P: crate::application::ports::readiness_probe::ReadinessProbe + 'static,
    IR: IngestAuthorizationRepository + 'static,
    MC: MediaCallbackRepository + 'static,
    I: crate::application::ports::identity::IdentityGateway
        + crate::application::ports::owner_context::OwnerContextGateway
        + 'static,
    C: crate::application::ports::channels::ChannelsGateway + 'static,
    T: crate::application::ports::taxonomy::TaxonomyGateway + 'static,
    SC: crate::application::ports::stream_config::StreamConfigRepository + 'static,
    G: crate::application::ports::stream_config::SecretGenerator + 'static,
    SR: crate::application::ports::streaming_repository::StreamingRepository + 'static,
    VL: ViewerLeaseRepository + 'static,
    VCI: ViewerLeaseCredentialIssuer + 'static,
{
    let RouterDependencies {
        readiness,
        authorize_ingest,
        record_media_callback,
        create_stream_config,
        get_stream_config,
        get_public_stream,
        get_public_stream_session,
        patch_stream_metadata,
        rotate_ingest_key,
        stop_stream_session,
        create_viewer_lease,
        heartbeat_viewer_lease,
        close_viewer_lease,
        media_adapter_service_token,
        session_cookie_name,
        web_origin,
    } = dependencies;
    let private = Router::new()
        .merge(ingest_authorization::router(
            authorize_ingest,
            media_adapter_service_token.as_deref(),
        ))
        .merge(media_callbacks::router(
            record_media_callback,
            media_adapter_service_token.as_deref(),
        ))
        .layer(middleware::from_fn(request_id::attach_request_id));
    let public = Router::new()
        .merge(health::router(readiness))
        .merge(stream_config::router(
            create_stream_config,
            get_stream_config,
            stream_config::PublicStreamQueries {
                stream: get_public_stream,
                session: get_public_stream_session,
            },
            patch_stream_metadata,
            rotate_ingest_key,
            stop_stream_session,
            stream_config::StreamConfigSecurity {
                session_cookie_name,
                web_origin,
            },
        ))
        .merge(viewer_leases::router(
            create_viewer_lease,
            heartbeat_viewer_lease,
            close_viewer_lease,
        ))
        .layer(middleware::from_fn(request_id::attach_request_id));
    HttpRouters { public, private }
}

pub(crate) mod core_context;
