use std::future::Future;

use crate::domain::rendition::Rendition;

use super::media_server::MediaServerError;

pub trait RenditionsGateway: Send + Sync {
    fn available_renditions(
        &self,
        master_playlist_path: String,
    ) -> impl Future<Output = Result<Vec<Rendition>, MediaServerError>> + Send;
}
