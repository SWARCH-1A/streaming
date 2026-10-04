use hmac::{Hmac, KeyInit, Mac};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::{
    application::ports::viewer_leases::{
        ViewerLeaseCredentialError, ViewerLeaseCredentialIssuer, ViewerLeaseCredentials,
    },
    domain::ids::SessionId,
};

type HmacSha256 = Hmac<Sha256>;

pub struct HmacViewerLeaseCredentialIssuer {
    key: Option<Vec<u8>>,
}

impl HmacViewerLeaseCredentialIssuer {
    pub fn new(key: Option<String>) -> Self {
        Self {
            key: key.map(String::into_bytes),
        }
    }
}

impl ViewerLeaseCredentialIssuer for HmacViewerLeaseCredentialIssuer {
    fn issue(
        &self,
        session_id: &SessionId,
        idempotency_key: Uuid,
    ) -> Result<ViewerLeaseCredentials, ViewerLeaseCredentialError> {
        let key = self.key.as_deref().ok_or(ViewerLeaseCredentialError)?;
        let id_input = format!(
            "viewer-lease-id-v1\0{}\0{idempotency_key}",
            session_id.as_str()
        );
        let token_input = format!(
            "viewer-lease-token-v1\0{}\0{idempotency_key}",
            session_id.as_str()
        );
        let id_mac = sign(key, id_input.as_bytes())?;
        let token_mac = sign(key, token_input.as_bytes())?;
        let lease_id = format!("lease_{}", hex(&id_mac[..16]));
        let lease_token = format!("vl_{}", hex(&token_mac));
        let token_hash = Sha256::digest(lease_token.as_bytes()).into();
        Ok(ViewerLeaseCredentials {
            lease_id,
            lease_token,
            token_hash,
        })
    }
}

fn sign(key: &[u8], message: &[u8]) -> Result<[u8; 32], ViewerLeaseCredentialError> {
    let mut mac = HmacSha256::new_from_slice(key).map_err(|_| ViewerLeaseCredentialError)?;
    mac.update(message);
    Ok(mac.finalize().into_bytes().into())
}

fn hex(bytes: &[u8]) -> String {
    let mut encoded = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use std::fmt::Write;
        let _ = write!(&mut encoded, "{byte:02x}");
    }
    encoded
}
