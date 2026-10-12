//! TLS belongs to each runtime; service authentication remains in the routers.
use std::{env, io, time::Duration};

use axum::Router;
use axum_server::tls_rustls::RustlsConfig;
use tokio::{net::TcpListener, sync::watch};

type Error = Box<dyn std::error::Error + Send + Sync>;

pub async fn tls_from_env() -> Result<Option<RustlsConfig>, Error> {
    let cert = env::var_os("STREAMING_TLS_CERT_FILE");
    let key = env::var_os("STREAMING_TLS_KEY_FILE");
    match (cert, key) {
        (None, None) if env::var("STREAMING_ENV").as_deref() != Ok("production") => Ok(None),
        (Some(cert), Some(key)) => RustlsConfig::from_pem_file(cert, key)
            .await
            .map(Some)
            .map_err(|_| "could not load Streaming TLS certificate/key".into()),
        _ => Err("Streaming TLS requires both certificate and key; production requires TLS".into()),
    }
}

pub async fn serve(
    listener: TcpListener,
    router: Router,
    tls: Option<RustlsConfig>,
    stopped: watch::Receiver<bool>,
) -> Result<(), Error> {
    match tls {
        None => axum::serve(listener, router)
            .with_graceful_shutdown(wait(stopped))
            .await
            .map_err(Into::into),
        Some(tls) => {
            let handle = axum_server::Handle::new();
            let server = axum_server::from_tcp_rustls(listener.into_std()?, tls)?
                .handle(handle.clone())
                .serve(router.into_make_service());
            tokio::pin!(server);
            tokio::select! {
                result = &mut server => result.map_err(Into::into),
                _ = wait(stopped) => {
                    handle.graceful_shutdown(Some(Duration::from_secs(10)));
                    server.await.map_err(|_| io::Error::other("TLS listener shutdown failed").into())
                }
            }
        }
    }
}

async fn wait(mut stopped: watch::Receiver<bool>) {
    if !*stopped.borrow() {
        let _ = stopped.changed().await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::routing::get;

    #[tokio::test]
    async fn tls_serves_only_trusted_matching_certificates_and_drains() -> Result<(), Error> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let rcgen::CertifiedKey { cert, signing_key } =
            rcgen::generate_simple_self_signed(vec!["localhost".into()])?;
        let tls = RustlsConfig::from_pem(
            cert.pem().into_bytes(),
            signing_key.serialize_pem().into_bytes(),
        )
        .await?;
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let port = listener.local_addr()?.port();
        let (stop, stopped) = watch::channel(false);
        let server = tokio::spawn(serve(
            listener,
            Router::new().route("/health", get(|| async { "ready" })),
            Some(tls),
            stopped,
        ));
        let client = reqwest::Client::builder()
            .no_proxy()
            .tls_certs_only([reqwest::Certificate::from_pem(cert.pem().as_bytes())?])
            .build()?;
        assert_eq!(
            client
                .get(format!("https://localhost:{port}/health"))
                .send()
                .await?
                .text()
                .await?,
            "ready"
        );
        assert!(
            client
                .get(format!("https://127.0.0.1:{port}/health"))
                .send()
                .await
                .is_err()
        );
        assert!(
            reqwest::Client::builder()
                .no_proxy()
                .build()?
                .get(format!("https://localhost:{port}/health"))
                .send()
                .await
                .is_err()
        );
        assert!(
            client
                .get(format!("http://localhost:{port}/health"))
                .send()
                .await
                .is_err()
        );
        stop.send_replace(true);
        tokio::time::timeout(Duration::from_secs(12), server).await???;
        Ok(())
    }
}
