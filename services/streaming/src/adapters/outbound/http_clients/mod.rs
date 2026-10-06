mod event_publisher;
pub use event_publisher::HttpDomainEventPublisher;
mod core;
pub use core::CoreHttpGateway;

use std::time::Duration;

use reqwest::{Client, redirect::Policy};

pub fn build_client(timeout: Duration) -> Result<Client, reqwest::Error> {
    Client::builder()
        .timeout(timeout)
        .connect_timeout(timeout)
        .pool_idle_timeout(Duration::from_secs(90))
        .pool_max_idle_per_host(16)
        .redirect(Policy::none())
        .tls_backend_rustls()
        .build()
}

fn endpoint(base_url: Option<&str>, path: &str) -> Option<String> {
    let base_url = base_url?.trim_end_matches('/');
    (!base_url.is_empty()).then(|| format!("{base_url}/{path}"))
}

pub(super) fn valid_identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}
