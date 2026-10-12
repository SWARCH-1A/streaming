use super::taxonomy::TaxonomyValue;
use std::{future::Future, time::Instant};

use uuid::Uuid;

use crate::{
    application::ports::streaming_repository::StreamConfigSnapshot, domain::ids::StreamId,
};

pub struct CreateStreamConfigCommand {
    pub authorization_expires_at: Option<Instant>,
    pub catalog_labels: Vec<TaxonomyValue>,
    pub stream_id: StreamId,
    pub channel_id: String,
    pub owner_user_id: String,
    pub title: String,
    pub category_id: String,
    pub tag_ids: Vec<String>,
    pub ingest_key_hash: [u8; 32],
    pub idempotency_key: Uuid,
    pub request_fingerprint: [u8; 32],
}

#[derive(Clone, Debug)]
pub struct StreamConfigWriteResult {
    pub config: StreamConfigSnapshot,
    pub created: bool,
}

pub struct PatchStreamMetadataCommand {
    pub authorization_expires_at: Option<Instant>,
    pub category_label: Option<TaxonomyValue>,
    pub tag_labels: Option<Vec<TaxonomyValue>>,
    pub stream_id: StreamId,
    pub title: Option<String>,
    pub category_id: Option<String>,
    pub tag_ids: Option<Vec<String>>,
}

#[derive(Clone, Debug)]
pub struct PatchStreamMetadataResult {
    pub config: StreamConfigSnapshot,
    pub changed_fields: Vec<String>,
}

pub struct RotateIngestKeyCommand {
    pub authorization_expires_at: Option<Instant>,
    pub stream_id: StreamId,
    pub ingest_key_hash: [u8; 32],
}

#[derive(Clone, Debug)]
pub struct RotatedIngestKeyResult {
    pub config: StreamConfigSnapshot,
    pub ingest_key_version: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StreamConfigRepositoryError {
    NotFound,
    Conflict,
    IdempotencyKeyReused,
    Unavailable,
    InvalidStoredData,
}

pub trait StreamConfigRepository: Send + Sync {
    fn create_if_absent(
        &self,
        command: CreateStreamConfigCommand,
    ) -> impl Future<Output = Result<StreamConfigWriteResult, StreamConfigRepositoryError>> + Send;

    fn patch_metadata(
        &self,
        command: PatchStreamMetadataCommand,
    ) -> impl Future<Output = Result<PatchStreamMetadataResult, StreamConfigRepositoryError>> + Send;

    fn rotate_ingest_key(
        &self,
        command: RotateIngestKeyCommand,
    ) -> impl Future<Output = Result<RotatedIngestKeyResult, StreamConfigRepositoryError>> + Send;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SecretGenerationError;

pub trait SecretGenerator: Send + Sync {
    fn generate_stream_key(
        &self,
    ) -> impl Future<Output = Result<String, SecretGenerationError>> + Send;
}
