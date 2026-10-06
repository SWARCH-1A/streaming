use reqwest::{Client, StatusCode, Url};

use crate::application::ports::media_server::{
    MediaServerError, MediaServerGateway, PlaybackEvidence,
};

const MAX_PLAYLIST_BYTES: usize = 128 * 1024;
const MAX_MEDIA_BYTES: usize = 8 * 1024 * 1024;

pub struct MediaMtxHlsGateway {
    client: Client,
    media_node_id: Option<String>,
    control_api_url: Option<String>,
    control_api_username: Option<String>,
    control_api_password: Option<String>,
    hls_internal_base_url: Option<String>,
    adapter_service_token: Option<String>,
}

impl MediaMtxHlsGateway {
    pub fn new(
        client: Client,
        media_node_id: Option<String>,
        control_api_url: Option<String>,
        control_api_username: Option<String>,
        control_api_password: Option<String>,
        hls_internal_base_url: Option<String>,
        adapter_service_token: Option<String>,
    ) -> Self {
        Self {
            client,
            media_node_id,
            control_api_url,
            control_api_username,
            control_api_password,
            hls_internal_base_url,
            adapter_service_token,
        }
    }

    async fn check_control_api(&self) -> Result<(), MediaServerError> {
        let Some(base_url) = self.control_api_url.as_deref() else {
            return Ok(());
        };
        let username = self
            .control_api_username
            .as_deref()
            .ok_or(MediaServerError::ControlApiUnavailable)?;
        let password = self
            .control_api_password
            .as_deref()
            .ok_or(MediaServerError::ControlApiUnavailable)?;
        let url = format!("{}/v3/info", base_url.trim_end_matches('/'));
        let response = self
            .client
            .get(url)
            .basic_auth(username, Some(password))
            .send()
            .await
            .map_err(|_| MediaServerError::ControlApiUnavailable)?;
        if response.status() == StatusCode::OK {
            Ok(())
        } else {
            Err(MediaServerError::ControlApiUnavailable)
        }
    }

    async fn verify(&self, playback_path: String) -> Result<PlaybackEvidence, MediaServerError> {
        let base_url = self
            .hls_internal_base_url
            .as_deref()
            .ok_or(MediaServerError::NodeUnavailable)?
            .trim_end_matches('/');
        if !valid_manifest_path(&playback_path) {
            return Err(MediaServerError::InvalidResponse);
        }
        let mut manifest_url = Url::parse(&format!("{base_url}{playback_path}"))
            .map_err(|_| MediaServerError::InvalidResponse)?;
        let mut playlist = self.playlist(manifest_url.clone()).await?;
        if let Some(variant) = find_variant_uri(&playlist) {
            manifest_url = resolve_reference(&manifest_url, variant, ".m3u8")
                .ok_or(MediaServerError::InvalidResponse)?;
            playlist = self.playlist(manifest_url.clone()).await?;
        }
        let segment_reference =
            find_segment_uri(&playlist).ok_or(MediaServerError::SegmentUnavailable)?;
        let segment_url = resolve_segment_url(&manifest_url, segment_reference)
            .ok_or(MediaServerError::InvalidResponse)?;
        let segment = self.fetch(segment_url, MAX_MEDIA_BYTES).await?;
        let initialization = if segment_reference.ends_with(".ts") {
            Vec::new()
        } else {
            let uri = playlist
                .lines()
                .filter_map(|line| line.trim().strip_prefix("#EXT-X-MAP:"))
                .find_map(uri_attribute)
                .ok_or(MediaServerError::SegmentUnavailable)?;
            let url = resolve_reference(&manifest_url, uri, ".mp4")
                .ok_or(MediaServerError::InvalidResponse)?;
            self.fetch(url, MAX_MEDIA_BYTES).await?
        };
        if !super::media_frame_probe::decodes_frame(initialization, segment).await {
            return Err(MediaServerError::SegmentUnavailable);
        }

        Ok(PlaybackEvidence {
            manifest_path: playback_path,
            has_reproducible_segment: true,
        })
    }
    async fn playlist(&self, url: Url) -> Result<String, MediaServerError> {
        let bytes = self.fetch(url, MAX_PLAYLIST_BYTES).await?;
        let playlist = String::from_utf8(bytes).map_err(|_| MediaServerError::InvalidResponse)?;
        if playlist.lines().next().map(str::trim) != Some("#EXTM3U") {
            return Err(MediaServerError::InvalidResponse);
        }
        Ok(playlist)
    }
    async fn fetch(&self, url: Url, limit: usize) -> Result<Vec<u8>, MediaServerError> {
        let mut request = self.client.get(url);
        if let Some(token) = self.adapter_service_token.as_ref() {
            request = request.bearer_auth(token);
        }
        let mut response = request
            .timeout(std::time::Duration::from_secs(2))
            .send()
            .await
            .map_err(|_| MediaServerError::NodeUnavailable)?;
        if response.status() != StatusCode::OK {
            return Err(MediaServerError::ManifestUnavailable);
        }
        if response
            .content_length()
            .is_some_and(|length| length > limit as u64)
        {
            return Err(MediaServerError::InvalidResponse);
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| MediaServerError::SegmentUnavailable)?
        {
            if bytes.len().saturating_add(chunk.len()) > limit {
                return Err(MediaServerError::InvalidResponse);
            }
            bytes.extend_from_slice(&chunk);
        }
        if bytes.is_empty() {
            return Err(MediaServerError::SegmentUnavailable);
        }
        Ok(bytes)
    }
}

impl MediaServerGateway for MediaMtxHlsGateway {
    fn check_control_api(
        &self,
    ) -> impl std::future::Future<Output = Result<(), MediaServerError>> + Send {
        async move { MediaMtxHlsGateway::check_control_api(self).await }
    }

    fn verify_playback(
        &self,
        media_node_id: String,
        playback_path: String,
    ) -> impl std::future::Future<Output = Result<PlaybackEvidence, MediaServerError>> + Send {
        async move {
            if self.media_node_id.as_deref() != Some(media_node_id.as_str()) {
                return Err(MediaServerError::NodeUnavailable);
            }
            self.verify(playback_path).await
        }
    }
}

fn find_segment_uri(playlist: &str) -> Option<&str> {
    playlist.lines().find_map(|line| {
        let line = line.trim();
        if let Some(attributes) = line.strip_prefix("#EXT-X-PART:") {
            if !attributes
                .split(',')
                .any(|attribute| attribute.trim() == "INDEPENDENT=YES")
            {
                return None;
            }
            let uri = uri_attribute(attributes)?;
            return valid_segment_uri(uri).then_some(uri);
        }
        if line.starts_with('#') {
            return None;
        }
        valid_segment_uri(line).then_some(line)
    })
}

fn valid_segment_uri(uri: &str) -> bool {
    (uri.ends_with(".m4s") || uri.ends_with(".ts") || uri.ends_with(".mp4"))
        && !uri.starts_with('/')
        && !uri.contains(['?', '#', '\\', '%', ':'])
        && !uri.chars().any(char::is_control)
        && uri
            .split('/')
            .all(|segment| !segment.is_empty() && segment != "." && segment != "..")
}

fn valid_manifest_path(path: &str) -> bool {
    let Some(path) = path.strip_prefix("/hls/") else {
        return false;
    };
    let Some((session_id, manifest_path)) = path.split_once('/') else {
        return false;
    };
    !session_id.is_empty()
        && !manifest_path.is_empty()
        && manifest_path.ends_with(".m3u8")
        && !path.contains(['?', '#', '\\', '%'])
        && !path.chars().any(char::is_control)
        && path.split('/').all(|segment| {
            !segment.is_empty()
                && segment != "."
                && segment != ".."
                && segment
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
        })
}

fn resolve_segment_url(manifest_url: &Url, segment_reference: &str) -> Option<Url> {
    if !valid_segment_uri(segment_reference) {
        return None;
    }
    let resolved_segment_url = manifest_url.join(segment_reference).ok()?;
    (same_origin(manifest_url, &resolved_segment_url)
        && resolved_segment_url
            .path()
            .starts_with(manifest_url.join(".").ok()?.path()))
    .then_some(resolved_segment_url)
}

fn same_origin(left: &Url, right: &Url) -> bool {
    left.scheme() == right.scheme()
        && left.host_str() == right.host_str()
        && left.port_or_known_default() == right.port_or_known_default()
}

fn uri_attribute(attributes: &str) -> Option<&str> {
    let start = attributes.find("URI=\"")? + 5;
    let tail = attributes.get(start..)?;
    tail.get(..tail.find('"')?)
}
fn find_variant_uri(playlist: &str) -> Option<&str> {
    let mut next = false;
    for line in playlist.lines().map(str::trim) {
        if line.starts_with("#EXT-X-STREAM-INF:") {
            next = true;
        } else if next && !line.is_empty() && !line.starts_with('#') {
            return Some(line);
        }
    }
    None
}
fn resolve_reference(manifest: &Url, reference: &str, suffix: &str) -> Option<Url> {
    if !reference.ends_with(suffix)
        || reference.starts_with('/')
        || reference.contains(['?', '#', '\\', '%', ':'])
        || reference.chars().any(char::is_control)
        || reference
            .split('/')
            .any(|component| component.is_empty() || component == "." || component == "..")
    {
        return None;
    }
    let url = manifest.join(reference).ok()?;
    (same_origin(manifest, &url) && url.path().starts_with(manifest.join(".").ok()?.path()))
        .then_some(url)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn follows_a_multivariant_reference_and_requires_independent_parts() {
        assert_eq!(
            find_variant_uri("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo.m3u8\n"),
            Some("video.m3u8")
        );
        assert_eq!(
            find_segment_uri("#EXTM3U\n#EXT-X-PART:URI=\"part.mp4\"\n"),
            None
        );
        assert_eq!(
            find_segment_uri("#EXTM3U\n#EXT-X-PART:URI=\"part.mp4\",INDEPENDENT=YES\n"),
            Some("part.mp4")
        );
        assert_eq!(uri_attribute("URI=\"init.mp4\""), Some("init.mp4"));
    }
    #[test]
    fn hls_references_stay_inside_the_session_directory() -> Result<(), reqwest::Error> {
        let parsed = Url::parse("https://media.example.test/hls/ses_test/index.m3u8");
        assert!(parsed.is_ok());
        if let Ok(base) = parsed {
            for reference in [
                "../video.m3u8",
                "/video.m3u8",
                "https://evil.test/video.m3u8",
                "%2e%2e/video.m3u8",
                "video.m3u8?token=x",
            ] {
                assert!(resolve_reference(&base, reference, ".m3u8").is_none());
            }
            assert!(resolve_reference(&base, "video.m3u8", ".m3u8").is_some());
        }
        Ok(())
    }
}
