use std::future::Future;

#[derive(Clone, Debug)]
pub struct PlaybackEvidence {
    pub manifest_path: String,
    pub has_reproducible_segment: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MediaServerError {
    NodeUnavailable,
    ControlApiUnavailable,
    ManifestUnavailable,
    SegmentUnavailable,
    InvalidResponse,
}

pub trait MediaServerGateway: Send + Sync {
    fn check_control_api(&self) -> impl Future<Output = Result<(), MediaServerError>> + Send;

    fn verify_playback(
        &self,
        media_node_id: String,
        playback_path: String,
    ) -> impl Future<Output = Result<PlaybackEvidence, MediaServerError>> + Send;
}
