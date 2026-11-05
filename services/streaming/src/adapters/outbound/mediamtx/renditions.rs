use std::collections::{BTreeMap, HashSet};

use sha2::{Digest, Sha256};

use super::*;
use crate::{
    application::ports::renditions::RenditionsGateway,
    domain::rendition::{MAX_RENDITIONS, Rendition},
};

impl RenditionsGateway for MediaMtxHlsGateway {
    async fn available_renditions(&self, path: String) -> Result<Vec<Rendition>, MediaServerError> {
        if !valid_manifest_path(&path) {
            return Err(MediaServerError::InvalidResponse);
        }
        let base = self
            .hls_internal_base_url
            .as_deref()
            .ok_or(MediaServerError::NodeUnavailable)?
            .trim_end_matches('/');
        let master =
            Url::parse(&format!("{base}{path}")).map_err(|_| MediaServerError::InvalidResponse)?;
        let variants = parse_master(&self.playlist(master.clone()).await?, &master)?;
        // A master entry alone is not sufficient: every advertised variant must have media.
        futures_util::future::try_join_all(variants.into_iter().map(
            |(rendition, url)| async move {
                let playlist = self.playlist(url.clone()).await?;
                let segment =
                    find_segment_uri(&playlist).ok_or(MediaServerError::SegmentUnavailable)?;
                resolve_segment_url(&url, segment).ok_or(MediaServerError::InvalidResponse)?;
                Ok(rendition)
            },
        ))
        .await
    }
}

fn parse_master(playlist: &str, master: &Url) -> Result<Vec<(Rendition, Url)>, MediaServerError> {
    let invalid = || MediaServerError::InvalidResponse;
    let mut variants = Vec::new();
    let mut paths = HashSet::new();
    let mut pending = None;
    if playlist.lines().next().map(str::trim) != Some("#EXTM3U") {
        return Err(invalid());
    }
    for line in playlist
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
    {
        if let Some(value) = line.strip_prefix("#EXT-X-STREAM-INF:") {
            if pending.is_some() {
                return Err(invalid());
            }
            pending = Some(attributes(value)?);
        } else if !line.starts_with('#') {
            let fields = pending.take().ok_or_else(invalid)?;
            let url = resolve_reference(master, line, ".m3u8").ok_or_else(invalid)?;
            if !paths.insert(url.path().to_owned()) || variants.len() >= MAX_RENDITIONS {
                return Err(invalid());
            }
            let (width, height) = fields
                .get("RESOLUTION")
                .and_then(|v| v.split_once('x'))
                .ok_or_else(invalid)?;
            let width = width
                .parse::<u32>()
                .ok()
                .filter(|v| *v > 0)
                .ok_or_else(invalid)?;
            let height = height
                .parse::<u32>()
                .ok()
                .filter(|v| *v > 0)
                .ok_or_else(invalid)?;
            let bitrate_bps = fields
                .get("BANDWIDTH")
                .and_then(|v| v.parse::<u64>().ok())
                .filter(|v| (1..=9_007_199_254_740_991).contains(v))
                .ok_or_else(invalid)?;
            let codecs = fields.get("CODECS").ok_or_else(invalid)?;
            let mut video_codec = None;
            let mut audio_codec = None;
            for codec in codecs.split(',').map(str::trim) {
                if codec.len() > 128 {
                    return Err(invalid());
                }
                let family = codec.split('.').next().ok_or_else(invalid)?;
                let target = match family {
                    "avc1" | "avc3" | "hvc1" | "hev1" | "av01" | "vp09" => &mut video_codec,
                    "mp4a" | "opus" | "ac-3" | "ec-3" | "fLaC" => &mut audio_codec,
                    _ => return Err(invalid()),
                };
                if target.replace(codec.to_owned()).is_some() {
                    return Err(invalid());
                }
            }
            // Hash only the public variant path. IDs remain stable across master reordering.
            let digest = Sha256::digest(url.path().as_bytes());
            let hex: String = digest.iter().map(|byte| format!("{byte:02x}")).collect();
            let rendition_id = format!("rend_{hex}");
            variants.push((
                Rendition {
                    rendition_id,
                    width,
                    height,
                    bitrate_bps,
                    video_codec: video_codec.ok_or_else(invalid)?,
                    audio_codec,
                },
                url,
            ));
        }
    }
    if pending.is_some() || variants.is_empty() {
        return Err(invalid());
    }
    Ok(variants)
}

// RFC 8216 quoted strings (notably CODECS) may contain commas.
fn attributes(value: &str) -> Result<BTreeMap<&str, &str>, MediaServerError> {
    let mut quoted = false;
    let mut start = 0;
    let mut fields = BTreeMap::new();
    for (index, byte) in value.bytes().chain(std::iter::once(b',')).enumerate() {
        if byte == b'"' {
            quoted = !quoted;
        }
        if byte != b',' || quoted {
            continue;
        }
        let (key, raw) = value[start..index]
            .trim()
            .split_once('=')
            .ok_or(MediaServerError::InvalidResponse)?;
        let parsed = if raw.starts_with('"') {
            raw.strip_prefix('"')
                .and_then(|v| v.strip_suffix('"'))
                .ok_or(MediaServerError::InvalidResponse)?
        } else {
            raw
        };
        if key.is_empty()
            || parsed.is_empty()
            || parsed.contains('"')
            || parsed.chars().any(char::is_control)
            || fields.insert(key, parsed).is_some()
        {
            return Err(MediaServerError::InvalidResponse);
        }
        start = index + 1;
    }
    if quoted {
        return Err(MediaServerError::InvalidResponse);
    }
    Ok(fields)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn variant(height: u32, path: &str) -> String {
        format!(
            "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x{height},CODECS=\"avc1.64001f,mp4a.40.2\"\n{path}\n"
        )
    }

    fn parse(body: &str) -> Result<Vec<(Rendition, Url)>, Box<dyn std::error::Error>> {
        Ok(parse_master(
            body,
            &Url::parse("https://media.test/hls/ses_test/index.m3u8")?,
        )
        .map_err(|_| "invalid master")?)
    }

    #[test]
    fn source_720_does_not_invent_1080_and_preserves_quoted_codecs()
    -> Result<(), Box<dyn std::error::Error>> {
        let variants = parse(&format!("#EXTM3U\n{}", variant(720, "video.m3u8")))?;
        assert_eq!(variants.len(), 1);
        assert_eq!(variants[0].0.height, 720);
        assert_eq!(variants[0].0.bitrate_bps, 2_500_000);
        assert_eq!(variants[0].0.video_codec, "avc1.64001f");
        assert_eq!(variants[0].0.audio_codec.as_deref(), Some("mp4a.40.2"));
        Ok(())
    }

    #[test]
    fn four_variants_have_stable_distinct_ids_after_reordering()
    -> Result<(), Box<dyn std::error::Error>> {
        let entries: Vec<_> = [360, 480, 720, 1080]
            .into_iter()
            .map(|height| variant(height, &format!("{height}/video.m3u8")))
            .collect();
        let original = parse(&format!("#EXTM3U\n{}", entries.join("")))?;
        let reversed = parse(&format!(
            "#EXTM3U\n{}",
            entries.into_iter().rev().collect::<String>()
        ))?;
        assert_eq!(original.len(), 4);
        assert_eq!(
            original
                .iter()
                .map(|v| &v.0.rendition_id)
                .collect::<HashSet<_>>()
                .len(),
            4
        );
        assert_eq!(original[0].0, reversed[3].0);
        Ok(())
    }

    #[test]
    fn rejects_duplicates_fifth_variant_missing_metadata_and_unsafe_paths() {
        let one = variant(720, "video.m3u8");
        let five = (1..=5)
            .map(|n| variant(720, &format!("v{n}.m3u8")))
            .collect::<String>();
        for body in [
            format!("#EXTM3U\n{one}{one}"),
            format!("#EXTM3U\n{five}"),
            format!("#EXTM3U\n{}", one.replace("RESOLUTION=1280x720,", "")),
            format!("#EXTM3U\n{}", one.replace("1280x720", "0x720")),
            format!("#EXTM3U\n{}", one.replace("2500000", "9007199254740992")),
            "#EXTM3U\n#EXTINF:1\nsegment.ts\n".into(),
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=123\n".into(),
        ] {
            assert!(parse(&body).is_err());
        }
        for path in [
            "../other.m3u8",
            "https://evil.test/video.m3u8",
            "/other.m3u8",
            "v.m3u8?secret=x",
            "%2e/video.m3u8",
        ] {
            assert!(parse(&format!("#EXTM3U\n{}", variant(720, path))).is_err());
        }
        assert!(attributes("CODECS=\"avc1,mp4a").is_err());
        assert!(attributes("BANDWIDTH=1,BANDWIDTH=2").is_err());
    }

    #[tokio::test]
    async fn media_gateway_reads_authenticated_playlists_and_rejects_missing_media()
    -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        use axum::{
            Router,
            extract::State,
            http::{HeaderMap, StatusCode, Uri},
        };
        use std::sync::{
            Arc,
            atomic::{AtomicBool, Ordering},
        };

        struct Fixture {
            master: String,
            ready: AtomicBool,
        }
        async fn playlist(
            State(state): State<Arc<Fixture>>,
            uri: Uri,
            headers: HeaderMap,
        ) -> (StatusCode, String) {
            if headers.get("authorization").and_then(|v| v.to_str().ok())
                != Some("Bearer fixture-internal")
            {
                return (StatusCode::UNAUTHORIZED, String::new());
            }
            let body = if uri.path() == "/hls/ses_fixture/index.m3u8" {
                state.master.clone()
            } else if state.ready.load(Ordering::SeqCst) {
                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1\nsegment.ts\n".into()
            } else {
                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n".into()
            };
            (StatusCode::OK, body)
        }

        let fixture = Arc::new(Fixture {
            master: format!(
                "#EXTM3U\n{}",
                [360, 480, 720, 1080]
                    .into_iter()
                    .map(|height| variant(height, &format!("v{height}.m3u8")))
                    .collect::<String>()
            ),
            ready: AtomicBool::new(true),
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let router = Router::new()
            .fallback(playlist)
            .with_state(Arc::clone(&fixture));
        let server = tokio::spawn(async move { axum::serve(listener, router).await });
        let gateway = MediaMtxHlsGateway::new(
            Client::builder().no_proxy().build()?,
            None,
            None,
            None,
            None,
            Some(format!("http://{address}")),
            Some("fixture-internal".into()),
        );
        let result = gateway
            .available_renditions("/hls/ses_fixture/index.m3u8".into())
            .await;
        assert_eq!(result.as_ref().ok().map(Vec::len), Some(4));
        fixture.ready.store(false, Ordering::SeqCst);
        assert_eq!(
            gateway
                .available_renditions("/hls/ses_fixture/index.m3u8".into())
                .await,
            Err(MediaServerError::SegmentUnavailable)
        );
        server.abort();
        Ok(())
    }
}
