use std::sync::Arc;

use crate::application::ports::readiness_probe::{ReadinessProbe, ReadinessProbeError};

pub struct CheckReadiness<P> {
    readiness_probe: Arc<P>,
}

impl<P> CheckReadiness<P>
where
    P: ReadinessProbe + 'static,
{
    pub fn new(readiness_probe: Arc<P>) -> Self {
        Self { readiness_probe }
    }

    pub async fn execute(&self) -> Result<(), ReadinessProbeError> {
        self.readiness_probe.check().await
    }
}
