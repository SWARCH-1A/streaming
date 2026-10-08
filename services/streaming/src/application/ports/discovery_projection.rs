use serde_json::Value;
use std::future::Future;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProjectionError {
    InvalidRequest,
    Expired,
    Unavailable,
}

pub trait DiscoveryProjectionRepository: Send + Sync {
    fn page(
        &self,
        limit: i32,
        cursor: Option<String>,
    ) -> impl Future<Output = Result<Value, ProjectionError>> + Send;
    fn refresh_due(&self) -> impl Future<Output = Result<(), ProjectionError>> + Send;
}
