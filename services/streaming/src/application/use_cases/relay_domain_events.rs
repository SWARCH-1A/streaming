use std::{sync::Arc, time::Duration};

use crate::application::ports::domain_event_outbox::{
    ClaimedDomainEvent, DomainEventOutboxError, DomainEventOutboxRepository, DomainEventPublisher,
};

const CLAIM_LEASE: Duration = Duration::from_secs(15);
const POLL_INTERVAL: Duration = Duration::from_millis(100);
const ERROR_BACKOFF: Duration = Duration::from_millis(500);

pub struct RelayDomainEvents<R, P> {
    repository: Arc<R>,
    publisher: Arc<P>,
}

impl<R, P> RelayDomainEvents<R, P>
where
    R: DomainEventOutboxRepository + 'static,
    P: DomainEventPublisher + 'static,
{
    pub fn new(repository: Arc<R>, publisher: Arc<P>) -> Self {
        Self {
            repository,
            publisher,
        }
    }

    pub async fn run(&self, owner_instance_id: String) -> ! {
        loop {
            match self.relay_one(&owner_instance_id).await {
                Ok(true) => {}
                Ok(false) => tokio::time::sleep(POLL_INTERVAL).await,
                Err(error) => {
                    tracing::warn!(error = %error, "could not relay a streaming domain event");
                    tokio::time::sleep(ERROR_BACKOFF).await;
                }
            }
        }
    }

    async fn relay_one(&self, owner_instance_id: &str) -> Result<bool, DomainEventOutboxError> {
        let Some(claim) = self
            .repository
            .claim_next(owner_instance_id.to_owned(), CLAIM_LEASE)
            .await?
        else {
            return Ok(false);
        };

        if claim.queue_age_ms > 5000
            && !claim.alerted
            && self
                .repository
                .alert_once(
                    claim.envelope.event_id,
                    owner_instance_id.to_owned(),
                    claim.claim_token,
                )
                .await?
        {
            tracing::warn!(event_id=%claim.envelope.event_id, aggregate_id=%claim.envelope.aggregate_id, queue_age_ms=claim.queue_age_ms, "streaming delivery exceeds freshness budget");
        }
        if claim.retry_age_ms >= 900000 {
            self.repository
                .dead_letter(
                    claim.envelope.event_id,
                    owner_instance_id.to_owned(),
                    claim.claim_token,
                    "RETRY_WINDOW_EXHAUSTED",
                )
                .await?;
            tracing::error!(event_id=%claim.envelope.event_id,"streaming delivery retained in dead letter");
            return Ok(true);
        }
        match self.publisher.publish(claim.envelope.clone()).await {
            Ok(()) => {
                self.repository
                    .acknowledge(
                        claim.envelope.event_id,
                        owner_instance_id.to_owned(),
                        claim.claim_token,
                    )
                    .await?;
            }
            Err(error) => {
                tracing::warn!(
                    event_id = %claim.envelope.event_id,
                    aggregate_id = %claim.envelope.aggregate_id,
                    attempt = claim.attempts,
                    error_code = error.error_code,
                    "streaming domain event publish will be retried"
                );
                if error.permanent {
                    self.repository
                        .dead_letter(
                            claim.envelope.event_id,
                            owner_instance_id.to_owned(),
                            claim.claim_token,
                            error.error_code,
                        )
                        .await?;
                    tracing::error!(event_id=%claim.envelope.event_id,"permanent streaming delivery failure; dead letter retained");
                } else {
                    self.retry(
                        &claim,
                        owner_instance_id,
                        error.error_code,
                        error.retry_after,
                    )
                    .await?;
                }
            }
        }
        Ok(true)
    }

    async fn retry(
        &self,
        claim: &ClaimedDomainEvent,
        owner_instance_id: &str,
        error_code: &'static str,
        retry_after: Option<Duration>,
    ) -> Result<(), DomainEventOutboxError> {
        self.repository
            .schedule_retry(
                claim.envelope.event_id,
                owner_instance_id.to_owned(),
                claim.claim_token,
                error_code,
                retry_after
                    .unwrap_or_else(|| retry_delay(claim.attempts))
                    .min(Duration::from_secs(900)),
            )
            .await
    }
}

fn retry_delay(attempt: i32) -> Duration {
    Duration::from_secs(match attempt {
        i32::MIN..=1 => 1,
        2 => 2,
        3 => 5,
        _ => 10,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn chat_lifecycle_backoff_is_one_two_five_ten_seconds() {
        assert_eq!(
            (1..=6)
                .map(|attempt| retry_delay(attempt).as_secs())
                .collect::<Vec<_>>(),
            [1, 2, 5, 10, 10, 10]
        );
    }
}
