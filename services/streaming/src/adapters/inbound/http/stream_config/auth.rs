use axum::http::{
    HeaderMap,
    header::{COOKIE, ORIGIN},
};

use crate::application::ports::identity::SessionCredential;

pub(super) fn session_credential(
    headers: &HeaderMap,
    cookie_name: &str,
) -> Option<SessionCredential> {
    let mut credential = None;
    for cookie_header in headers.get_all(COOKIE).iter() {
        let value = cookie_header.to_str().ok()?;
        for pair in value.split(';') {
            let (name, value) = pair.trim().split_once('=')?;
            if name.trim() == cookie_name {
                if credential.is_some() {
                    return None;
                }
                credential = Some(SessionCredential::new(value.trim().to_owned())?);
            }
        }
    }
    credential
}

pub(super) fn matches_web_origin(headers: &HeaderMap, expected_origin: &str) -> bool {
    let mut origins = headers.get_all(ORIGIN).iter();
    let Some(origin) = origins.next().and_then(|value| value.to_str().ok()) else {
        return false;
    };
    if origins.next().is_some() {
        return false;
    }

    let Ok(url) = reqwest::Url::parse(origin) else {
        return false;
    };
    matches!(url.scheme(), "http" | "https")
        && url.host_str().is_some()
        && !origin.contains('@')
        && url.username().is_empty()
        && url.password().is_none()
        && url.path() == "/"
        && url.query().is_none()
        && url.fragment().is_none()
        && url.origin().ascii_serialization() == expected_origin
}
