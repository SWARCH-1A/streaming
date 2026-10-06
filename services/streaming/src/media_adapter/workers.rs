use std::{sync::Arc, time::Duration};

use futures_util::StreamExt;
use reqwest::StatusCode;
use tokio::sync::watch;

use super::MediaState;

pub(super) async fn run(state: Arc<MediaState>, shutdown: watch::Receiver<bool>) {
    let observations = observation_loop(Arc::clone(&state), shutdown.clone());
    let metrics = metrics_loop(Arc::clone(&state), shutdown.clone());
    let deliveries = delivery_loop(state, shutdown);
    tokio::join!(observations, deliveries, metrics);
}
async fn observation_loop(state: Arc<MediaState>, mut shutdown: watch::Receiver<bool>) {
    let mut interval = tokio::time::interval(Duration::from_millis(500));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! { _ = shutdown.changed() => break, _ = interval.tick() => {} }
        if observe_sources(&state).await.is_err() {
            tracing::warn!("media source reconciliation unavailable");
        }
    }
}
async fn delivery_loop(state: Arc<MediaState>, mut shutdown: watch::Receiver<bool>) {
    let mut interval = tokio::time::interval(Duration::from_millis(50));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! { _ = shutdown.changed() => break, _ = interval.tick() => {} }
        let deliveries = futures_util::stream::iter(0..8)
            .map(|_| deliver_one(&state))
            .buffer_unordered(8);
        tokio::pin!(deliveries);
        while let Some(result) = deliveries.next().await {
            if result.is_err() {
                tracing::warn!("media callback queue unavailable");
            }
        }
    }
}

pub(super) async fn observe_sources(state: &MediaState) -> Result<(), ()> {
    let sources = state.repository.active_sources().await.map_err(|_| ())?;
    let observations = futures_util::stream::iter(sources)
        .map(|source| async move {
            match state.session_status(&source.session_id).await {
                Ok(status) if status == "ENDED" => {
                    if source.connected_at.is_some() && source.lost_at.is_none() {
                        state
                            .repository
                            .observe(&source, "source-lost")
                            .await
                            .map_err(|_| ())?;
                    }
                    state.kick(source.publisher_id).await?;
                    state
                        .repository
                        .retire(source.publisher_id)
                        .await
                        .map_err(|_| ())?;
                    return Ok::<(), ()>(());
                }
                Err(()) => {
                    // Unverifiable business owner fails closed, including cached HLS aliases.
                    if source.connected_at.is_some() && source.lost_at.is_none() {
                        state
                            .repository
                            .observe(&source, "source-lost")
                            .await
                            .map_err(|_| ())?;
                    }
                    state.kick(source.publisher_id).await?;
                    state
                        .repository
                        .retire(source.publisher_id)
                        .await
                        .map_err(|_| ())?;
                    return Ok::<(), ()>(());
                }
                _ => {}
            }
            if source.lost_at.is_some() {
                state.kick(source.publisher_id).await?;
                state
                    .repository
                    .retire(source.publisher_id)
                    .await
                    .map_err(|_| ())?;
                return Ok::<(), ()>(());
            }
            match state.matches_source(&source).await {
                Ok(true) => {
                    if source.connected_at.is_none() {
                        state
                            .repository
                            .observe(&source, "source-connected")
                            .await
                            .map_err(|_| ())?;
                    }
                    if source.playback_at.is_none() {
                        let response = state
                            .client
                            .get(format!(
                                "{}/{}/index.m3u8",
                                state.config.hls_url, source.ingest_path
                            ))
                            .bearer_auth(&state.config.hls_secret)
                            .send()
                            .await;
                        if let Ok(response) = response
                            && response.status() == StatusCode::OK
                            && response.content_length().is_none_or(|n| n <= 131072)
                        {
                            // Streaming independently decodes a real segment before LIVE.
                            let bytes =
                                crate::adapters::outbound::http_body::limited(response, 131072)
                                    .await?;
                            if bytes.len() <= 131072 && bytes.starts_with(b"#EXTM3U") {
                                state
                                    .repository
                                    .observe(&source, "playback-ready")
                                    .await
                                    .map_err(|_| ())?;
                            }
                        }
                    }
                }
                Ok(false) if source.connected_at.is_some() => {
                    state
                        .repository
                        .observe(&source, "source-lost")
                        .await
                        .map_err(|_| ())?;
                }
                Ok(false)
                    if time::OffsetDateTime::now_utc() - source.created_at
                        > time::Duration::seconds(2)
                        && !state.publisher_exists(source.publisher_id).await? =>
                {
                    // Authentication reserves a source before the engine has a playable path.
                    // A disconnected publisher must release that reservation even without Ready.
                    state
                        .repository
                        .observe(&source, "source-lost")
                        .await
                        .map_err(|_| ())?;
                }
                // A failed Control API read is not evidence of source loss.
                // Report degraded reconciliation and retry on the next pass.
                Err(()) => return Err(()),
                _ => {}
            }
            Ok::<(), ()>(())
        })
        .buffer_unordered(8);
    tokio::pin!(observations);
    let mut failed = false;
    while let Some(result) = observations.next().await {
        failed |= result.is_err();
    }
    if failed { Err(()) } else { Ok(()) }
}

async fn deliver_one(state: &MediaState) -> Result<bool, ()> {
    let Some(callback) = state.repository.claim().await.map_err(|_| ())? else {
        return Ok(false);
    };
    let age = callback.age_ms;
    let Some(session) = callback
        .payload
        .get("sessionId")
        .and_then(|v| v.as_str())
        .filter(|v| crate::domain::ids::SessionId::parse((*v).to_owned()).is_some())
    else {
        state
            .repository
            .finish(&callback, "PERMANENT", 0, true)
            .await
            .map_err(|_| ())?;
        tracing::error!(event_id=%callback.event_id,"invalid media callback retained in dead letter");
        return Ok(true);
    };
    let alert = age >= 30000 && callback.alerted_at.is_none();
    if alert
        && state
            .repository
            .alert_once(callback.publisher_id)
            .await
            .map_err(|_| ())?
    {
        tracing::warn!(event_id=%callback.event_id,session_id=session,"media callback has waited 30 seconds without terminal ACK");
    }
    if age >= 900000 {
        state
            .repository
            .finish(&callback, "EXPIRED", 0, true)
            .await
            .map_err(|_| ())?;
        tracing::error!(event_id=%callback.event_id,"media callback moved to durable dead letter");
        return Ok(true);
    }
    let response = state
        .client
        .post(format!(
            "{}/internal/streaming/sessions/{}/{}",
            state.config.streaming_url, session, callback.kind
        ))
        .bearer_auth(&state.config.streaming_token)
        .json(&callback.payload)
        .send()
        .await;
    let (result, delay) = match response {
        Ok(response) if response.status().is_success() => ("ACK", 0),
        Ok(response) if response.status() == StatusCode::GONE => ("OBSOLETE", 0),
        Ok(response)
            if response.status().is_client_error()
                && !matches!(
                    response.status(),
                    StatusCode::REQUEST_TIMEOUT | StatusCode::TOO_MANY_REQUESTS
                ) =>
        {
            ("PERMANENT", 0)
        }
        Ok(response) => {
            let retry_after =
                crate::adapters::outbound::http_retry_after::retry_after(response.headers())
                    .map(|duration| i64::try_from(duration.as_millis()).unwrap_or(i64::MAX));
            (
                "RETRY",
                retry_after
                    .unwrap_or_else(|| retry_delay_ms(callback.attempts))
                    .min(900000),
            )
        }
        Err(_) => ("RETRY", retry_delay_ms(callback.attempts)),
    };
    if result == "PERMANENT" {
        tracing::error!(event_id=%callback.event_id,"permanent media callback failure; dead letter retained");
    }
    state
        .repository
        .finish(&callback, result, delay, alert || result == "PERMANENT")
        .await
        .map_err(|_| ())?;
    Ok(true)
}
fn retry_delay_ms(attempt: i32) -> i64 {
    match attempt {
        i32::MIN..=1 => 100,
        2 => 250,
        3 => 500,
        4 => 1000,
        _ => 2000,
    }
}

async fn metrics_loop(state: Arc<MediaState>, mut shutdown: watch::Receiver<bool>) {
    let mut interval = tokio::time::interval(Duration::from_secs(15));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! {_=shutdown.changed()=>break,_=interval.tick()=>{}}
        let metrics=sqlx::query_as::<_,(i64,Option<i64>,i32,i64)>("SELECT count(*) FILTER(WHERE delivered_at IS NULL AND dead_letter_at IS NULL AND closed_at IS NULL), (EXTRACT(EPOCH FROM clock_timestamp()-min(created_at) FILTER(WHERE delivered_at IS NULL AND dead_letter_at IS NULL AND closed_at IS NULL))*1000)::BIGINT, COALESCE(max(attempts),0), count(*) FILTER(WHERE dead_letter_at IS NOT NULL AND closed_at IS NULL) FROM media_callback_outbox")
            .fetch_one(&state.repository.pool).await;
        match metrics {
            Ok((queue, age, attempts, dead_letters)) => tracing::info!(
                callback_queue_depth = queue,
                callback_oldest_age_ms = age,
                callback_max_attempts = attempts,
                open_dead_letters = dead_letters,
                "media delivery queue metrics"
            ),
            Err(_) => tracing::warn!("media delivery metrics unavailable"),
        }
    }
}
