use std::{fmt, marker::PhantomData};

use serde::{Deserialize, Deserializer, Serialize, de::Visitor};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct CreateStreamConfigBody {
    pub(super) title: String,
    pub(super) category_id: String,
    #[serde(default)]
    pub(super) tag_ids: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct PatchStreamMetadataBody {
    #[serde(default)]
    pub(super) title: PatchField<String>,
    #[serde(default)]
    pub(super) category_id: PatchField<String>,
    #[serde(default)]
    pub(super) tag_ids: PatchField<Vec<String>>,
}

#[derive(Default)]
pub(super) enum PatchField<T> {
    #[default]
    Missing,
    Null,
    Value(T),
}

impl<T> PatchField<T> {
    pub(super) fn into_value(self) -> Result<Option<T>, ()> {
        match self {
            Self::Missing => Ok(None),
            Self::Null => Err(()),
            Self::Value(value) => Ok(Some(value)),
        }
    }
}

impl<'de, T> Deserialize<'de> for PatchField<T>
where
    T: Deserialize<'de>,
{
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct PatchFieldVisitor<T>(PhantomData<T>);

        impl<'de, T> Visitor<'de> for PatchFieldVisitor<T>
        where
            T: Deserialize<'de>,
        {
            type Value = PatchField<T>;

            fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str("a non-null patch value or null")
            }

            fn visit_none<E>(self) -> Result<Self::Value, E> {
                Ok(PatchField::Null)
            }

            fn visit_unit<E>(self) -> Result<Self::Value, E> {
                Ok(PatchField::Null)
            }

            fn visit_some<D>(self, deserializer: D) -> Result<Self::Value, D::Error>
            where
                D: Deserializer<'de>,
            {
                T::deserialize(deserializer).map(PatchField::Value)
            }
        }

        deserializer.deserialize_option(PatchFieldVisitor(PhantomData))
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct CreateStreamConfigResponse {
    pub(super) stream_id: String,
    pub(super) channel_id: String,
    pub(super) title: String,
    pub(super) category_id: String,
    pub(super) tag_ids: Vec<String>,
    pub(super) metadata_version: i64,
    pub(super) rtmp_url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) stream_key: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct GetStreamConfigResponse {
    pub(super) stream_id: String,
    pub(super) channel_id: String,
    pub(super) title: String,
    pub(super) category_id: String,
    pub(super) tag_ids: Vec<String>,
    pub(super) metadata_version: i64,
    pub(super) stream_generation: i64,
    pub(super) rtmp_url: String,
    pub(super) status: &'static str,
    pub(super) availability: &'static str,
    pub(super) status_fresh: bool,
    pub(super) session_id: Option<String>,
    pub(super) session_version: Option<i64>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct PublicStreamResponse {
    pub(super) stream_id: String,
    pub(super) channel_id: String,
    pub(super) session_id: Option<String>,
    pub(super) stream_generation: i64,
    pub(super) title: String,
    pub(super) category: PublicTaxonomyValueResponse,
    pub(super) tags: Vec<PublicTaxonomyValueResponse>,
    pub(super) status: &'static str,
    pub(super) availability: &'static str,
    pub(super) status_fresh: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) viewer_count: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) count_version: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) viewer_count_observed_at_utc: Option<String>,
    pub(super) metadata_version: i64,
    pub(super) session_version: Option<i64>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct PublicTaxonomyValueResponse {
    pub(super) id: String,
    pub(super) name: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct PublicStreamSessionResponse {
    pub(super) session_id: String,
    pub(super) stream_id: String,
    pub(super) channel_id: String,
    pub(super) stream_generation: i64,
    pub(super) status: &'static str,
    pub(super) availability: &'static str,
    pub(super) playback_url: Option<String>,
    pub(super) timeline_position_ms: i64,
    pub(super) timeline_sampled_at_utc: Option<String>,
    pub(super) metadata_version: i64,
    pub(super) session_version: i64,
    pub(super) viewer_count: i64,
    pub(super) count_version: i64,
    pub(super) viewer_count_observed_at_utc: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct PatchStreamMetadataResponse {
    pub(super) stream_id: String,
    pub(super) channel_id: String,
    pub(super) title: String,
    pub(super) category_id: String,
    pub(super) tag_ids: Vec<String>,
    pub(super) metadata_version: i64,
    pub(super) changed_fields: Vec<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct RotateIngestKeyResponse {
    pub(super) stream_id: String,
    pub(super) channel_id: String,
    pub(super) rtmp_url: String,
    pub(super) stream_key: String,
    pub(super) ingest_key_version: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ApiError {
    pub(super) code: &'static str,
    pub(super) message: &'static str,
    pub(super) request_id: String,
}
