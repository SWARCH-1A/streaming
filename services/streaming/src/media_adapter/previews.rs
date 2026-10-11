use super::{MediaState, repository::Source};
use crate::adapters::outbound::{
    http_body, media_frame_probe,
    mediamtx::{
        find_segment_uri, find_variant_uri, resolve_reference, resolve_segment_url, uri_attribute,
    },
};
use reqwest::Url;
use std::{
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::watch;

pub(super) struct Preview {
    pub bytes: Arc<[u8]>,
    pub captured: Instant,
    pub publisher: uuid::Uuid,
}

// At most five active P1 streams. HTTP reads never start an encoder or create viewer leases.
pub(super) async fn run(state: Arc<MediaState>, mut shutdown: watch::Receiver<bool>) {
    let mut interval = tokio::time::interval(Duration::from_secs(5));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! { _ = shutdown.changed() => break, _ = interval.tick() => {} }
        let Ok(sources) = state.repository.active_sources().await else {
            continue;
        };
        for source in sources
            .iter()
            .filter(|source| source.playback_at.is_some() && source.lost_at.is_none())
            .take(5)
        {
            if *shutdown.borrow() {
                return;
            }
            let fresh = state.previews.read().ok().is_some_and(|cache| {
                cache.get(&source.session_id).is_some_and(|preview| {
                    preview.publisher == source.publisher_id
                        && preview.captured.elapsed() < Duration::from_secs(15)
                })
            });
            if fresh {
                continue;
            }
            if let Ok(preview) = capture(&state, source).await
                && let Ok(mut cache) = state.previews.write()
            {
                cache.retain(|_, value| value.captured.elapsed() < Duration::from_secs(45));
                if cache.len() < 5 || cache.contains_key(&source.session_id) {
                    cache.insert(source.session_id.clone(), preview);
                }
            }
        }
        if let Ok(mut cache) = state.previews.write() {
            cache.retain(|session, value| {
                value.captured.elapsed() < Duration::from_secs(45)
                    && sources.iter().any(|source| {
                        &source.session_id == session
                            && source.publisher_id == value.publisher
                            && source.lost_at.is_none()
                    })
            });
        }
    }
}

async fn capture(state: &MediaState, source: &Source) -> Result<Preview, ()> {
    let status = state.session_state(&source.session_id).await?;
    if status.status != "LIVE" || status.availability != "PLAYABLE" {
        return Err(());
    }
    let path = state.source_path(source).await?.ok_or(())?;
    let mut url =
        Url::parse(&format!("{}/{}/index.m3u8", state.config.hls_url, path)).map_err(|_| ())?;
    let mut playlist = manifest(state, url.clone()).await?;
    if let Some(variant) = find_variant_uri(&playlist) {
        url = resolve_reference(&url, variant, ".m3u8").ok_or(())?;
        playlist = manifest(state, url.clone()).await?;
    }
    let segment_url = playlist
        .lines()
        .rev()
        .find_map(|line| resolve_segment_url(&url, line.trim()))
        .or_else(|| find_segment_uri(&playlist).and_then(|uri| resolve_segment_url(&url, uri)))
        .ok_or(())?;
    let initialization = if segment_url.path().ends_with(".ts") {
        Vec::new()
    } else {
        let uri = playlist
            .lines()
            .filter_map(|line| line.trim().strip_prefix("#EXT-X-MAP:"))
            .find_map(uri_attribute)
            .ok_or(())?;
        fetch(
            state,
            resolve_reference(&url, uri, ".mp4").ok_or(())?,
            8 * 1024 * 1024,
        )
        .await?
    };
    let segment = fetch(state, segment_url, 8 * 1024 * 1024).await?;
    let bytes = media_frame_probe::preview_frame(initialization, segment)
        .await
        .ok_or(())?;
    if !state.matches_source(source).await? {
        return Err(());
    }
    Ok(Preview {
        bytes: bytes.into(),
        captured: Instant::now(),
        publisher: source.publisher_id,
    })
}
async fn manifest(state: &MediaState, url: Url) -> Result<String, ()> {
    let text = String::from_utf8(fetch(state, url, 128 * 1024).await?).map_err(|_| ())?;
    if text.lines().next().map(str::trim) != Some("#EXTM3U") {
        return Err(());
    }
    Ok(text)
}
async fn fetch(state: &MediaState, url: Url, limit: usize) -> Result<Vec<u8>, ()> {
    let response = state
        .client
        .get(url)
        .bearer_auth(&state.config.hls_secret)
        .send()
        .await
        .map_err(|_| ())?;
    if !response.status().is_success() {
        return Err(());
    }
    http_body::limited(response, limit).await
}
