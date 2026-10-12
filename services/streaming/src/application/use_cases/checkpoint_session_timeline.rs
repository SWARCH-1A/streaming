use std::{sync::Arc, time::Duration};

use crate::application::ports::{
    session_clock::SessionMonotonicClock,
    session_timeline::{SessionTimelineError, SessionTimelineRepository},
};

const POLL_INTERVAL: Duration = Duration::from_millis(500);

pub struct CheckpointSessionTimeline<R> {
    repository: Arc<R>,
    session_clock: Arc<dyn SessionMonotonicClock>,
}

impl<R> CheckpointSessionTimeline<R>
where
    R: SessionTimelineRepository + 'static,
{
    pub fn new(repository: Arc<R>, session_clock: Arc<dyn SessionMonotonicClock>) -> Self {
        Self {
            repository,
            session_clock,
        }
    }

    pub async fn run(&self, owner_instance_id: String) -> Result<u64, SessionTimelineError> {
        self.repository
            .checkpoint_due_samples(owner_instance_id, self.session_clock.as_ref())
            .await
    }

    pub async fn run_worker(&self, owner_instance_id: String) {
        let mut interval = tokio::time::interval(POLL_INTERVAL);
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            interval.tick().await;
            if let Err(error) = self.run(owner_instance_id.clone()).await {
                tracing::warn!(error = %error, "could not checkpoint streaming session timeline");
            }
        }
    }
}
