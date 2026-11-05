//! AMQP transport for the immutable P2 envelope owned by Streaming's outbox.
use crate::application::ports::domain_event_outbox::{
    DomainEventEnvelope, DomainEventPublishError, DomainEventPublisher,
};
use lapin::{
    BasicProperties, Channel, Confirmation, Connection, DefaultConnectionBuilder,
    options::{BasicPublishOptions, ConfirmSelectOptions},
    tcp::OwnedTLSConfig,
};
use sqlx::PgPool;
use std::{future::Future, time::Duration};
use tokio::sync::Mutex;

pub struct RabbitDomainEventPublisher {
    pool: PgPool,
    url: String,
    ca: String,
    connection: Mutex<Option<(Connection, Channel)>>,
}

impl RabbitDomainEventPublisher {
    pub fn new(pool: PgPool, url: String, ca: String) -> Self {
        Self {
            pool,
            url,
            ca,
            connection: Mutex::new(None),
        }
    }

    async fn send(&self, event: DomainEventEnvelope) -> Result<(), DomainEventPublishError> {
        let body = sqlx::query_scalar::<_, Option<serde_json::Value>>(
            "SELECT broker_envelope FROM streaming_outbox WHERE event_id=$1",
        )
        .bind(event.event_id)
        .fetch_one(&self.pool)
        .await
        .map_err(|_| retry("OUTBOX_UNAVAILABLE"))?
        .ok_or(DomainEventPublishError {
            error_code: "INVALID_BROKER_ENVELOPE",
            permanent: true,
            retry_after: None,
        })?;
        let kind = body["eventType"]
            .as_str()
            .ok_or_else(|| retry("INVALID_BROKER_ENVELOPE"))?;
        let route = if kind == "stream.session.changed.v1" {
            format!(
                "{kind}.{}",
                if body["payload"]["initialLive"] == true {
                    "initial"
                } else {
                    "update"
                }
            )
        } else {
            kind.to_owned()
        };
        let bytes = serde_json::to_vec(&body).map_err(|_| retry("INVALID_BROKER_ENVELOPE"))?;
        if bytes.len() > 65536 {
            return Err(DomainEventPublishError {
                error_code: "EVENT_TOO_LARGE",
                permanent: true,
                retry_after: None,
            });
        }
        let mut connection = self.connection.lock().await;
        if connection
            .as_ref()
            .is_none_or(|(_, channel)| !channel.status().connected())
        {
            let broker = DefaultConnectionBuilder::new()
                .map_err(|_| retry("BROKER_UNAVAILABLE"))?
                .with_uri_str(self.url.clone())
                .with_tls_config(OwnedTLSConfig {
                    cert_chain: Some(self.ca.clone()),
                    ..Default::default()
                })
                .connect()
                .await
                .map_err(|_| retry("BROKER_UNAVAILABLE"))?;
            let channel = broker
                .create_channel()
                .await
                .map_err(|_| retry("BROKER_UNAVAILABLE"))?;
            channel
                .confirm_select(ConfirmSelectOptions::default())
                .await
                .map_err(|_| retry("BROKER_UNAVAILABLE"))?;
            *connection = Some((broker, channel));
        }
        let Some((_, channel)) = connection.as_ref() else {
            return Err(retry("BROKER_UNAVAILABLE"));
        };
        let confirmation = channel
            .basic_publish(
                "p2.events".into(),
                route.into(),
                BasicPublishOptions {
                    mandatory: true,
                    ..Default::default()
                },
                &bytes,
                BasicProperties::default()
                    .with_delivery_mode(2)
                    .with_content_type("application/json".into())
                    .with_message_id(event.event_id.to_string().into())
                    .with_type(kind.into())
                    .with_timestamp(
                        time::OffsetDateTime::parse(
                            &event.occurred_at_utc,
                            &time::format_description::well_known::Rfc3339,
                        )
                        .map(|value| value.unix_timestamp().max(0) as u64)
                        .unwrap_or_default(),
                    )
                    .with_correlation_id(body["correlationId"].as_str().unwrap_or_default().into()),
            )
            .await;
        let result = match confirmation {
            Ok(confirm) => match confirm.await {
                Ok(Confirmation::Ack(None)) => Ok(()),
                Ok(_) => Err(retry("BROKER_UNROUTABLE_OR_NACK")),
                Err(_) => Err(retry("BROKER_CONFIRM_UNKNOWN")),
            },
            Err(_) => Err(retry("BROKER_UNAVAILABLE")),
        };
        if result.is_err() {
            *connection = None;
        }
        result
    }
}

impl DomainEventPublisher for RabbitDomainEventPublisher {
    fn publish(
        &self,
        event: DomainEventEnvelope,
    ) -> impl Future<Output = Result<(), DomainEventPublishError>> + Send {
        async move {
            // Bound connection + confirm to less than the outbox claim lease; cancellation
            // leaves the original event pending, including an ambiguous publish result.
            match tokio::time::timeout(Duration::from_secs(3), self.send(event)).await {
                Ok(result) => result,
                Err(_) => {
                    *self.connection.lock().await = None;
                    Err(retry("BROKER_TIMEOUT"))
                }
            }
        }
    }
}

fn retry(code: &'static str) -> DomainEventPublishError {
    DomainEventPublishError {
        error_code: code,
        permanent: false,
        retry_after: None,
    }
}
