use std::{sync::Arc, time::Duration};

use crate::application::ports::worker_queue_metrics::{
    WorkerQueueMetricsError, WorkerQueueMetricsRepository,
};

const REPORT_INTERVAL: Duration = Duration::from_secs(15);

pub struct ReportWorkerQueueMetrics<R> {
    repository: Arc<R>,
}

impl<R> ReportWorkerQueueMetrics<R>
where
    R: WorkerQueueMetricsRepository + 'static,
{
    pub fn new(repository: Arc<R>) -> Self {
        Self { repository }
    }

    pub async fn run(&self) -> Result<(), WorkerQueueMetricsError> {
        let snapshot = self.repository.snapshot().await?;
        tracing::info!(
            callback_queue_depth = snapshot.callback_queue_depth,
            callback_oldest_age_millis = snapshot.callback_oldest_age_millis,
            callback_max_attempts = snapshot.callback_max_attempts,
            outbox_queue_depth = snapshot.outbox_queue_depth,
            outbox_oldest_age_millis = snapshot.outbox_oldest_age_millis,
            outbox_max_attempts = snapshot.outbox_max_attempts,
            open_dead_letter_count = snapshot.open_dead_letter_count,
            dead_letter_oldest_age_millis = snapshot.dead_letter_oldest_age_millis,
            dead_letter_max_attempts = snapshot.dead_letter_max_attempts,
            "streaming background work queue metrics"
        );
        Ok(())
    }

    pub async fn run_worker(&self) -> ! {
        let mut interval = tokio::time::interval(REPORT_INTERVAL);
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

        loop {
            interval.tick().await;
            if let Err(error) = self.run().await {
                tracing::warn!(error = %error, "could not report streaming background work queue metrics");
            }
        }
    }
}
