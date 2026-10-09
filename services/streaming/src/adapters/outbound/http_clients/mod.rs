mod event_publisher;
pub use event_publisher::HttpDomainEventPublisher;
mod core;
pub use core::CoreHttpGateway;

use std::{env, fs, time::Duration};

use reqwest::{Client, redirect::Policy};

#[derive(Debug, thiserror::Error)]
pub enum ClientError {
    #[error("could not load outbound TLS CA")]
    Ca,
    #[error("could not configure outbound HTTP client")]
    Http(#[from] reqwest::Error),
}

pub fn build_client(timeout: Duration) -> Result<Client, ClientError> {
    let mut builder = Client::builder()
        .timeout(timeout)
        .connect_timeout(timeout)
        .pool_idle_timeout(Duration::from_secs(90))
        .pool_max_idle_per_host(16)
        .redirect(Policy::none())
        .tls_backend_rustls();
    if let Some(path) = env::var_os("STREAMING_HTTP_CA_FILE") {
        let pem = fs::read(path).map_err(|_| ClientError::Ca)?;
        let certs = reqwest::Certificate::from_pem_bundle(&pem).map_err(|_| ClientError::Ca)?;
        if certs.is_empty() {
            return Err(ClientError::Ca);
        }
        builder = builder.tls_certs_merge(certs);
    }
    Ok(builder.build()?)
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
