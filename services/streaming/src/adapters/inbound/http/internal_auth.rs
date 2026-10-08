use axum::http::{HeaderMap, header::AUTHORIZATION};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

pub fn service_token_hash(token: Option<&str>) -> Option<[u8; 32]> {
    token.map(|token| Sha256::digest(token.as_bytes()).into())
}

pub fn valid_service_authorization(headers: &HeaderMap, expected_hash: Option<&[u8; 32]>) -> bool {
    if headers.get_all(AUTHORIZATION).iter().count() != 1 {
        return false;
    }
    let (Some(expected_hash), Some(header)) = (expected_hash, headers.get(AUTHORIZATION)) else {
        return false;
    };
    let Ok(header) = header.to_str() else {
        return false;
    };
    let Some((scheme, token)) = header.split_once(' ') else {
        return false;
    };
    let token = token.trim();
    if !scheme.eq_ignore_ascii_case("Bearer") || token.is_empty() || token.contains(' ') {
        return false;
    }

    let presented_hash: [u8; 32] = Sha256::digest(token.as_bytes()).into();
    bool::from(expected_hash.ct_eq(&presented_hash))
}
