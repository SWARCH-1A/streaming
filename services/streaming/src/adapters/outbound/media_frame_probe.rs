use std::{
    fs,
    io::Write,
    path::PathBuf,
    process::{Command, Stdio},
    time::{Duration, Instant},
};

use uuid::Uuid;

pub(super) async fn decodes_frame(initialization: Vec<u8>, segment: Vec<u8>) -> bool {
    tokio::task::spawn_blocking(move || decode(initialization, segment))
        .await
        .unwrap_or(false)
}

fn decode(initialization: Vec<u8>, segment: Vec<u8>) -> bool {
    if initialization.len() > 8 * 1024 * 1024
        || segment.len() > 8 * 1024 * 1024
        || segment.is_empty()
    {
        return false;
    }
    decode_inner(&initialization, &segment).unwrap_or(false)
}

fn decode_inner(initialization: &[u8], segment: &[u8]) -> std::io::Result<bool> {
    let directory = TemporaryDirectory::new()?;
    let input = directory.0.join("segment.media");
    let output = directory.0.join("frame.rgb");
    let mut file = fs::File::create(&input)?;
    file.write_all(initialization)?;
    file.write_all(segment)?;
    drop(file);
    let executable =
        std::env::var("STREAMING_FFMPEG_EXECUTABLE").unwrap_or_else(|_| "ffmpeg".into());
    let mut child = Command::new(executable)
        .args([
            "-v",
            "error",
            "-nostdin",
            "-max_alloc",
            "67108864",
            "-threads",
            "1",
            "-protocol_whitelist",
            "file",
            "-f",
        ])
        .arg(if initialization.is_empty() {
            "mpegts"
        } else {
            "mov"
        })
        .arg("-i")
        .arg(input)
        .args([
            "-map",
            "0:v:0",
            "-frames:v",
            "1",
            "-threads",
            "1",
            "-filter_threads",
            "1",
            "-vf",
            "scale=160:90",
            "-an",
            "-pix_fmt",
            "rgb24",
            "-f",
            "rawvideo",
            "-y",
        ])
        .arg(&output)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                return Ok(status.success()
                    && fs::metadata(&output).is_ok_and(|metadata| metadata.len() == 160 * 90 * 3));
            }
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(10)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return Ok(false);
            }
        }
    }
}

struct TemporaryDirectory(PathBuf);
impl TemporaryDirectory {
    fn new() -> std::io::Result<Self> {
        let path = std::env::temp_dir().join(format!("streaming-frame-{}", Uuid::new_v4()));
        let mut builder = fs::DirBuilder::new();
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        builder.create(&path)?;
        Ok(Self(path))
    }
}
impl Drop for TemporaryDirectory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn arbitrary_bytes_are_not_a_reproducible_frame() {
        assert!(!decodes_frame(vec![], vec![42]).await);
    }
    #[tokio::test]
    async fn decodes_a_real_fragmented_h264_frame() -> Result<(), Box<dyn std::error::Error>> {
        let directory = TemporaryDirectory::new()?;
        let fixture = directory.0.join("fixture.mp4");
        let executable =
            std::env::var("STREAMING_FFMPEG_EXECUTABLE").unwrap_or_else(|_| "ffmpeg".into());
        let result = Command::new(executable)
            .args([
                "-v",
                "error",
                "-f",
                "lavfi",
                "-i",
                "color=black:size=160x90:rate=1",
                "-frames:v",
                "1",
                "-c:v",
                "libx264",
                "-threads",
                "1",
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "frag_keyframe+empty_moov",
                "-y",
            ])
            .arg(&fixture)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        assert!(
            result.is_ok_and(|status| status.success()),
            "FFmpeg con libx264 requerido para la prueba de decoder"
        );
        let bytes = fs::read(fixture).unwrap_or_default();
        let offset = bytes
            .windows(4)
            .position(|window| window == b"moof")
            .and_then(|position| position.checked_sub(4));
        assert!(offset.is_some());
        let (initialization, segment) = bytes.split_at(offset.unwrap_or_default());
        assert!(decodes_frame(initialization.to_vec(), segment.to_vec()).await);
        Ok(())
    }
}
