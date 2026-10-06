use super::{identity::SessionCredential, taxonomy::TaxonomyValue};
use serde::{Deserialize, Serialize};
use std::{future::Future, time::Instant};
use uuid::Uuid;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum OwnerOperation {
    CreateConfig,
    PatchMetadata,
    RotateKey,
    StopSession,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OwnerContextRequest {
    pub command_id: Uuid,
    pub operation: OwnerOperation,
    pub channel_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tag_ids: Option<Vec<String>>,
}

pub struct OwnerContext {
    pub user_id: String,
    pub category: Option<TaxonomyValue>,
    pub tags: Option<Vec<TaxonomyValue>>,
    pub expires_at: Instant,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OwnerContextError {
    Inactive,
    Forbidden,
    NotFound,
    InvalidCatalog,
    Unavailable,
}

pub trait OwnerContextGateway: Send + Sync {
    fn authorize(
        &self,
        credential: SessionCredential,
        request: OwnerContextRequest,
    ) -> impl Future<Output = Result<OwnerContext, OwnerContextError>> + Send;
}
