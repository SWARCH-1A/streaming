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
    decode_inner(&initialization, &segment, false).is_ok_and(|bytes| bytes.len() == 160 * 90 * 3)
}

pub(crate) async fn preview_frame(initialization: Vec<u8>, segment: Vec<u8>) -> Option<Vec<u8>> {
    if initialization.len() > 8 * 1024 * 1024
        || segment.len() > 8 * 1024 * 1024
        || segment.is_empty()
    {
        return None;
    }
    tokio::task::spawn_blocking(move || decode_inner(&initialization, &segment, true).ok())
        .await
        .ok()
        .flatten()
        .filter(|bytes| bytes.starts_with(&[0xff, 0xd8]) && bytes.len() <= 512 * 1024)
}

fn decode_inner(initialization: &[u8], segment: &[u8], preview: bool) -> std::io::Result<Vec<u8>> {
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
            if preview {
                "scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2"
            } else {
                "scale=160:90"
            },
            "-an",
            "-pix_fmt",
            if preview { "yuvj420p" } else { "rgb24" },
            "-f",
            if preview { "image2" } else { "rawvideo" },
            "-y",
        ])
        .args(if preview {
            vec!["-c:v", "mjpeg", "-q:v", "5"]
        } else {
            vec![]
        })
        .arg(&output)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                if status.success()
                    && fs::metadata(&output).is_ok_and(|metadata| metadata.len() <= 512 * 1024)
                {
                    return fs::read(&output);
                }
                return Ok(Vec::new());
            }
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(10)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return Ok(Vec::new());
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
        assert!(preview_frame(vec![], vec![42]).await.is_none());
        assert!(
            preview_frame(vec![], vec![0; 8 * 1024 * 1024 + 1])
                .await
                .is_none()
        );
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
        let jpeg = preview_frame(initialization.to_vec(), segment.to_vec())
            .await
            .ok_or("preview missing")?;
        assert!(jpeg.starts_with(&[0xff, 0xd8]));
        // JPEG baseline SOF: precision, height, width. The preview is always 640 x 360.
        assert!(
            jpeg.windows(9)
                .any(|bytes| bytes[..2] == [0xff, 0xc0] && bytes[4..9] == [8, 1, 104, 2, 128])
        );
        assert!(jpeg.len() <= 512 * 1024);
        Ok(())
    }
}
