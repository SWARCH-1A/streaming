use crate::application::ports::domain_event_outbox::{
    DomainEventEnvelope, DomainEventPublishError, DomainEventPublisher,
};
use reqwest::{Client, StatusCode, header::HeaderValue};
use std::{future::Future, time::Duration};

pub struct HttpDomainEventPublisher {
    client: Client,
    url: Option<String>,
    token: Option<String>,
}
impl HttpDomainEventPublisher {
    pub fn new(client: Client, url: Option<String>, token: Option<String>) -> Self {
        Self { client, url, token }
    }
}
impl DomainEventPublisher for HttpDomainEventPublisher {
    fn publish(
        &self,
        event: DomainEventEnvelope,
    ) -> impl Future<Output = Result<(), DomainEventPublishError>> + Send {
        async move {
            let retry = |code| DomainEventPublishError {
                error_code: code,
                permanent: false,
                retry_after: None,
            };
            let url = self
                .url
                .as_deref()
                .ok_or_else(|| retry("CONSUMER_UNCONFIGURED"))?;
            let mut token = HeaderValue::from_str(
                self.token
                    .as_deref()
                    .ok_or_else(|| retry("CONSUMER_UNCONFIGURED"))?,
            )
            .map_err(|_| retry("CONSUMER_UNCONFIGURED"))?;
            token.set_sensitive(true);
            let request = self
                .client
                .post(url)
                .timeout(Duration::from_secs(1))
                .json(&event);
            let request = request
                .header("X-Service-Name", "streaming")
                .header("X-Service-Token", token);
            let response = request
                .send()
                .await
                .map_err(|_| retry("TRANSPORT_UNAVAILABLE"))?;
            if response.status().is_success() {
                return Ok(());
            }
            let permanent = response.status().is_client_error()
                && !matches!(
                    response.status(),
                    StatusCode::REQUEST_TIMEOUT | StatusCode::TOO_MANY_REQUESTS
                );
            Err(DomainEventPublishError {
                error_code: if permanent {
                    "PERMANENT_HTTP_ERROR"
                } else {
                    "TRANSIENT_HTTP_ERROR"
                },
                permanent,
                retry_after: crate::adapters::outbound::http_retry_after::retry_after(
                    response.headers(),
                ),
            })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Json, Router, http::HeaderMap, routing::post};
    use serde_json::{Value, json};
    use std::sync::{Arc, Mutex};
    #[tokio::test]
    async fn transport_preserves_envelope_and_classifies_ack_retry_and_permanent_failure()
    -> Result<(), Box<dyn std::error::Error>> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let observed = Arc::new(Mutex::new(Vec::<Value>::new()));
        let capture = Arc::clone(&observed);
        let app = Router::new()
            .route(
                "/ack",
                post(move |headers: HeaderMap, Json(body): Json<Value>| {
                    let capture = Arc::clone(&capture);
                    async move {
                        assert_eq!(
                            headers.get("X-Service-Name").and_then(|h| h.to_str().ok()),
                            Some("streaming")
                        );
                        assert_eq!(
                            headers.get("X-Service-Token").and_then(|h| h.to_str().ok()),
                            Some("fixture-only")
                        );
                        if let Ok(mut values) = capture.lock() {
                            values.push(body);
                        }
                        StatusCode::ACCEPTED
                    }
                }),
            )
            .route(
                "/retry",
                post(|| async { (StatusCode::TOO_MANY_REQUESTS, [("Retry-After", "7")]) }),
            )
            .route(
                "/permanent",
                post(|| async { StatusCode::UNPROCESSABLE_ENTITY }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let task = tokio::spawn(async move { axum::serve(listener, app).await });
        let event = DomainEventEnvelope {
            event_id: uuid::Uuid::new_v4(),
            event_type: "StreamDiscoverySnapshot".into(),
            schema_version: 1,
            aggregate_id: "stream:fixture".into(),
            sequence: 3,
            occurred_at_utc: "2026-10-03T00:00:00Z".into(),
            producer: "streaming",
            payload: json!({"projectionVersion":3,"title":"Fixture"}),
        };
        let publisher = |path| {
            HttpDomainEventPublisher::new(
                Client::new(),
                Some(format!("http://{address}/{path}")),
                Some("fixture-only".into()),
            )
        };
        publisher("ack").publish(event.clone()).await?;
        publisher("ack").publish(event.clone()).await?;
        {
            let received = observed.lock().map_err(|_| "fixture lock")?;
            assert_eq!(received.len(), 2);
            assert_eq!(received[0], serde_json::to_value(&event)?);
            assert_eq!(received[0], received[1]);
        }
        let retry = publisher("retry")
            .publish(event.clone())
            .await
            .err()
            .ok_or("expected retry")?;
        assert!(!retry.permanent);
        assert_eq!(retry.retry_after, Some(Duration::from_secs(7)));
        let permanent = publisher("permanent")
            .publish(event)
            .await
            .err()
            .ok_or("expected permanent failure")?;
        assert!(permanent.permanent);
        task.abort();
        Ok(())
    }
}
