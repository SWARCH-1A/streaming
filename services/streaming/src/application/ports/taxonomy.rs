use std::future::Future;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TaxonomyError {
    InactiveOrUnknownValue,
    Unavailable,
    InvalidResponse,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct TaxonomyValue {
    pub id: String,
    pub name: String,
    pub active: bool,
}

pub trait TaxonomyGateway: Send + Sync {
    fn resolve_values(
        &self,
        value_ids: Vec<String>,
    ) -> impl Future<Output = Result<Vec<TaxonomyValue>, TaxonomyError>> + Send;
}
