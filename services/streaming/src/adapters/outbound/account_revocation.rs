//! Reconciliation also fences suspensions missed during broker downtime.
use super::postgres::PostgresStreamingRepository;
use crate::{
    application::ports::{
        channel_access::ChannelAccessGateway, session_clock::SessionMonotonicClock,
        streaming_repository::StreamingRepository,
    },
    domain::ids::SessionId,
};
use sqlx::PgPool;
use std::{sync::Arc, time::Duration};

pub async fn reconcile(
    pool: &PgPool,
    gateway: &dyn ChannelAccessGateway,
    repository: &PostgresStreamingRepository,
    clock: &dyn SessionMonotonicClock,
) -> Result<(), &'static str> {
    let sessions: Vec<(String, String, i64)> = sqlx::query_as(
        "SELECT session_id,channel_id,owner_account_version FROM stream_sessions WHERE status <> 'ENDED' ORDER BY session_id",
    ).fetch_all(pool).await.map_err(|_| "STORE_UNAVAILABLE")?;
    // At most five active sessions; independent authorities are read concurrently.
    use futures_util::{StreamExt, stream};
    let mut reads = stream::iter(sessions)
        .map(|(id, channel, version)| async move {
            (id, version, gateway.channel_access(channel).await)
        })
        .buffer_unordered(5);
    while let Some((id, version, access)) = reads.next().await {
        let Ok(access) = access else { continue }; // delivery itself fails closed; no invented terminal state
        if access.active && access.account_version == version {
            continue;
        }
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
    Ok(())
}

pub async fn run(
    pool: PgPool,
    gateway: Arc<dyn ChannelAccessGateway>,
    repository: Arc<PostgresStreamingRepository>,
    clock: Arc<dyn SessionMonotonicClock>,
) {
    let mut interval = tokio::time::interval(Duration::from_millis(500));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        interval.tick().await;
        if reconcile(&pool, gateway.as_ref(), &repository, clock.as_ref())
            .await
            .is_err()
        {
            tracing::warn!("account revocation reconciliation unavailable");
        }
    }
}
