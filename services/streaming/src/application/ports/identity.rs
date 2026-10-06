use std::future::Future;

pub struct SessionCredential(String);

impl SessionCredential {
    pub fn new(value: String) -> Option<Self> {
        (!value.is_empty() && value.len() <= 4096).then_some(Self(value))
    }

    pub(crate) fn expose_to_identity_adapter(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for SessionCredential {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("SessionCredential([REDACTED])")
    }
}

#[derive(Clone, Debug)]
pub struct IdentityPrincipal {
    pub user_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum IdentityError {
    Inactive,
    Unavailable,
    InvalidResponse,
}

pub trait IdentityGateway: Send + Sync {
    fn introspect(
        &self,
        credential: SessionCredential,
    ) -> impl Future<Output = Result<IdentityPrincipal, IdentityError>> + Send;
}
