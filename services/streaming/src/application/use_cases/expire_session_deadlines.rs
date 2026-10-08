use std::{sync::Arc, time::Duration};

use crate::application::ports::{
    session_clock::SessionMonotonicClock, session_deadlines::SessionDeadlineRepository,
};

const DEADLINE_POLL_INTERVAL: Duration = Duration::from_millis(100);

pub struct ExpireSessionDeadlines<R> {
    repository: Arc<R>,
    session_clock: Arc<dyn SessionMonotonicClock>,
}

impl<R> ExpireSessionDeadlines<R>
where
    R: SessionDeadlineRepository + 'static,
{
    pub fn new(repository: Arc<R>, session_clock: Arc<dyn SessionMonotonicClock>) -> Self {
        Self {
            repository,
            session_clock,
        }
    }

    pub async fn run(&self, owner_instance_id: String) {
        let mut interval = tokio::time::interval(DEADLINE_POLL_INTERVAL);
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            interval.tick().await;
            if let Err(error) = self
                .repository
                .expire_due(owner_instance_id.clone(), self.session_clock.as_ref())
                .await
            {
                tracing::warn!(error = %error, "could not process streaming session deadlines");
            }
        }
    }
}
