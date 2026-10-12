use std::future::Future;

use crate::application::ports::session_clock::SessionMonotonicClock;

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum SessionDeadlineError {
    #[error("stored session data is inconsistent")]
    InvalidStoredData,
    #[error("streaming persistence is unavailable")]
    Unavailable,
}

pub trait SessionDeadlineRepository: Send + Sync {
    fn expire_due<'a>(
        &'a self,
        owner_instance_id: String,
        session_clock: &'a dyn SessionMonotonicClock,
    ) -> impl Future<Output = Result<u64, SessionDeadlineError>> + Send + 'a;
}
