use std::future::Future;

pub trait ReadinessProbe: Send + Sync {
    fn check(&self) -> impl Future<Output = Result<(), ReadinessProbeError>> + Send;
}

#[derive(Debug, thiserror::Error)]
#[error("streaming dependency probe failed")]
pub struct ReadinessProbeError;
