//! Durable account revocation; the HTTP authority remains the gate for every delivery.
use super::postgres::PostgresStreamingRepository;
use crate::{
    application::ports::{
        session_clock::SessionMonotonicClock, streaming_repository::StreamingRepository,
    },
    domain::ids::SessionId,
};
use futures_util::StreamExt;
use lapin::{
    Confirmation, DefaultConnectionBuilder,
    options::*,
    tcp::OwnedTLSConfig,
    types::{AMQPValue, FieldTable},
};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use sqlx::PgPool;
use std::{sync::Arc, time::Duration};
use uuid::Uuid;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Event {
    event_id: Uuid,
    event_type: String,
    schema_version: i64,
    aggregate_id: String,
    sequence: i64,
    occurred_at_utc: String,
    producer: String,
    correlation_id: String,
    payload: Payload,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Payload {
    user_id: String,
    account_version: i64,
    status: String,
}

fn decode(bytes: &[u8]) -> Result<Event, &'static str> {
    if bytes.len() > 65536 {
        return Err("INVALID_EVENT");
    }
    let e: Event = serde_json::from_slice(bytes).map_err(|_| "INVALID_EVENT")?;
    let valid_id = |s: &str, max| {
        !s.is_empty()
            && s.len() <= max
            && s.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    };
    if e.event_type != "account.status.changed.v1"
        || e.schema_version != 1
        || e.producer != "core"
        || !valid_id(&e.payload.user_id, 64)
        || !valid_id(&e.correlation_id, 128)
        || e.aggregate_id != format!("account:{}", e.payload.user_id)
        || e.sequence < 1
        || e.sequence > 9_007_199_254_740_991
        || e.sequence != e.payload.account_version
        || !matches!(e.payload.status.as_str(), "ACTIVE" | "SUSPENDED")
        || !(e.occurred_at_utc.ends_with('Z') || e.occurred_at_utc.ends_with("+00:00"))
        || time::OffsetDateTime::parse(
            &e.occurred_at_utc,
            &time::format_description::well_known::Rfc3339,
        )
        .is_err()
    {
        return Err("INVALID_EVENT");
    }
    Ok(e)
}

pub async fn apply(
    pool: &PgPool,
    repository: &PostgresStreamingRepository,
    clock: &dyn SessionMonotonicClock,
    bytes: &[u8],
) -> Result<(), &'static str> {
    let event = decode(bytes)?;
    let hash = Sha256::digest(bytes).to_vec();
    if let Some(existing) = sqlx::query_scalar::<_, Vec<u8>>(
        "SELECT body_hash FROM streaming_account_inbox WHERE event_id=$1",
    )
    .bind(event.event_id)
    .fetch_optional(pool)
    .await
    .map_err(|_| "STORE_UNAVAILABLE")?
    {
        return if existing == hash {
            Ok(())
        } else {
            Err("EVENT_ID_CONFLICT")
        };
    }
    let sessions: Vec<String>=sqlx::query_scalar("SELECT s.session_id FROM stream_sessions s JOIN stream_configs c ON c.stream_id=s.stream_id WHERE c.owner_user_id=$1 AND s.status <> 'ENDED' AND (s.owner_account_version<$2 OR (s.owner_account_version=$2 AND $3='SUSPENDED'))")
        .bind(&event.payload.user_id).bind(event.payload.account_version).bind(&event.payload.status)
        .fetch_all(pool).await.map_err(|_| "STORE_UNAVAILABLE")?;
    for id in sessions {
        let id = SessionId::parse(id).ok_or("INVALID_STORED_DATA")?;
        let position = clock
            .timeline_elapsed(&id)
            .and_then(|v| i64::try_from(v.as_millis()).ok());
        repository
            .stop_session(id.clone(), position, None)
            .await
            .map_err(|_| "STORE_UNAVAILABLE")?;
        clock.forget(&id);
    }
    // A crash before this receipt replays idempotent terminal transitions before ACK.
    sqlx::query("INSERT INTO streaming_account_inbox(event_id,body_hash) VALUES($1,$2) ON CONFLICT DO NOTHING")
        .bind(event.event_id).bind(hash).execute(pool).await.map_err(|_| "STORE_UNAVAILABLE")?;
    Ok(())
}

async fn consume(
    pool: &PgPool,
    repository: &PostgresStreamingRepository,
    clock: &dyn SessionMonotonicClock,
    url: &str,
    ca: &str,
) -> Result<(), ()> {
    let connection = tokio::time::timeout(
        Duration::from_secs(3),
        DefaultConnectionBuilder::new()
            .map_err(|_| ())?
            .with_uri_str(url.to_owned())
            .with_tls_config(OwnedTLSConfig {
                cert_chain: Some(ca.to_owned()),
                ..Default::default()
            })
            .connect(),
    )
    .await
    .map_err(|_| ())?
    .map_err(|_| ())?;
    let channel = connection.create_channel().await.map_err(|_| ())?;
    channel
        .basic_qos(16, BasicQosOptions::default())
        .await
        .map_err(|_| ())?;
    channel
        .confirm_select(ConfirmSelectOptions::default())
        .await
        .map_err(|_| ())?;
    let mut messages = channel
        .basic_consume(
            "streaming.account".into(),
            "".into(),
            BasicConsumeOptions::default(),
            FieldTable::default(),
        )
        .await
        .map_err(|_| ())?;
    while let Some(message) = messages.next().await {
        let message = message.map_err(|_| ())?;
        let result = if message
            .properties
            .content_type()
            .as_ref()
            .is_some_and(|v| v.as_str() == "application/json")
        {
            tokio::time::timeout(
                Duration::from_secs(3),
                apply(pool, repository, clock, &message.data),
            )
            .await
            .unwrap_or(Err("STORE_UNAVAILABLE"))
        } else {
            Err("INVALID_EVENT")
        };
        if let Err(cause) = result {
            let attempt = message
                .properties
                .headers()
                .as_ref()
                .and_then(|h| h.inner().get("x-p2-attempt"))
                .and_then(|v| {
                    if let AMQPValue::LongInt(n) = v {
                        Some(*n)
                    } else {
                        None
                    }
                })
                .unwrap_or(0)
                .max(0);
            let permanent = cause != "STORE_UNAVAILABLE" || attempt >= 3;
            let (exchange, key) = if permanent {
                ("streaming.account.dead", "failed")
            } else {
                (
                    "streaming.account.retry",
                    ["1", "5", "30"][attempt as usize],
                )
            };
            let mut headers = message.properties.headers().clone().unwrap_or_default();
            headers.insert(
                "x-p2-attempt".into(),
                AMQPValue::LongInt(attempt.saturating_add(1)),
            );
            headers.insert("x-p2-cause".into(), AMQPValue::LongString(cause.into()));
            headers.insert(
                "x-p2-consumer".into(),
                AMQPValue::LongString("streaming.account".into()),
            );
            let confirmation = tokio::time::timeout(Duration::from_secs(3), async {
                channel
                    .basic_publish(
                        exchange.into(),
                        key.into(),
                        BasicPublishOptions {
                            mandatory: true,
                            ..Default::default()
                        },
                        &message.data,
                        message
                            .properties
                            .clone()
                            .with_delivery_mode(2)
                            .with_content_type("application/json".into())
                            .with_headers(headers),
                    )
                    .await
                    .map_err(|_| ())?
                    .await
                    .map_err(|_| ())
            })
            .await
            .map_err(|_| ())??;
            if !matches!(confirmation, Confirmation::Ack(None)) {
                return Err(());
            }
        }
        message
            .ack(BasicAckOptions::default())
            .await
            .map_err(|_| ())?;
    }
    Err(())
}

pub async fn run(
    pool: PgPool,
    repository: Arc<PostgresStreamingRepository>,
    clock: Arc<dyn SessionMonotonicClock>,
    url: String,
    ca: String,
) {
    loop {
        if consume(&pool, &repository, clock.as_ref(), &url, &ca)
            .await
            .is_err()
        {
            tracing::warn!("account event consumer reconnecting");
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_forged_revisions_and_envelopes() {
        let mut value = serde_json::json!({"eventId":Uuid::new_v4(),"eventType":"account.status.changed.v1","schemaVersion":1,"producer":"core","aggregateId":"account:usr_fixture","sequence":2,"occurredAtUtc":"2026-11-02T12:00:00Z","correlationId":"fixture","payload":{"userId":"usr_fixture","accountVersion":2,"status":"SUSPENDED"}});
        assert!(decode(&serde_json::to_vec(&value).unwrap_or_default()).is_ok());
        value["sequence"] = 1.into();
        assert!(decode(&serde_json::to_vec(&value).unwrap_or_default()).is_err());
        value["sequence"] = 2.into();
        value["payload"]["status"] = "UNKNOWN".into();
        assert!(decode(&serde_json::to_vec(&value).unwrap_or_default()).is_err());
    }
}
