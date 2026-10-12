use std::{future::Future, time::Duration};

use serde::Serialize;
use serde_json::Value;
use thiserror::Error;
use uuid::Uuid;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DomainEventEnvelope {
    pub event_id: Uuid,
    pub event_type: String,
    pub schema_version: i32,
    pub aggregate_id: String,
    pub sequence: i64,
    pub occurred_at_utc: String,
    pub producer: &'static str,
    pub payload: Value,
}

#[derive(Clone, Debug)]
pub struct ClaimedDomainEvent {
    pub envelope: DomainEventEnvelope,
    pub claim_token: Uuid,
    pub attempts: i32,
    pub retry_age_ms: i64,
    pub queue_age_ms: i64,
    pub alerted: bool,
}

#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub enum DomainEventOutboxError {
    #[error("domain event claim is no longer owned by this relay")]
    LeaseLost,
    #[error("domain event claim parameters are invalid")]
    InvalidClaim,
    #[error("stored domain event is invalid")]
    InvalidStoredEvent,
    #[error("domain event outbox is unavailable")]
    Unavailable,
}

#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
#[error("domain event publisher is unavailable ({error_code})")]
pub struct DomainEventPublishError {
    pub error_code: &'static str,
    pub permanent: bool,
    pub retry_after: Option<Duration>,
}

pub trait DomainEventOutboxRepository: Send + Sync {
    fn claim_next(
        &self,
        owner_instance_id: String,
        lease_duration: Duration,
    ) -> impl Future<Output = Result<Option<ClaimedDomainEvent>, DomainEventOutboxError>> + Send;

    fn acknowledge(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        claim_token: Uuid,
    ) -> impl Future<Output = Result<(), DomainEventOutboxError>> + Send;

    fn dead_letter(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        claim_token: Uuid,
        reason: &'static str,
    ) -> impl Future<Output = Result<(), DomainEventOutboxError>> + Send;
    fn alert_once(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        claim_token: Uuid,
    ) -> impl Future<Output = Result<bool, DomainEventOutboxError>> + Send;

    fn schedule_retry(
        &self,
        event_id: Uuid,
        owner_instance_id: String,
        claim_token: Uuid,
        error_code: &'static str,
        delay: Duration,
    ) -> impl Future<Output = Result<(), DomainEventOutboxError>> + Send;
}

pub trait DomainEventPublisher: Send + Sync {
    fn publish(
        &self,
        event: DomainEventEnvelope,
    ) -> impl Future<Output = Result<(), DomainEventPublishError>> + Send;
}
