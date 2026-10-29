/// Metadata of a video variant actually advertised by Media, never a generated ladder.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rendition {
    pub rendition_id: String,
    pub width: u32,
    pub height: u32,
    pub bitrate_bps: u64,
    pub video_codec: String,
    pub audio_codec: Option<String>,
}

pub const MAX_RENDITIONS: usize = 4;
