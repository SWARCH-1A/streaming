use std::future::Future;

use crate::application::ports::session_clock::SessionMonotonicClock;

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum SessionTimelineError {
    #[error("stored timeline data is inconsistent")]
    InvalidStoredData,
    #[error("streaming persistence is unavailable")]
    Unavailable,
}

pub trait SessionTimelineRepository: Send + Sync {
    fn checkpoint_due_samples<'a>(
        &'a self,
        owner_instance_id: String,
        session_clock: &'a dyn SessionMonotonicClock,
    ) -> impl Future<Output = Result<u64, SessionTimelineError>> + Send + 'a;
}
