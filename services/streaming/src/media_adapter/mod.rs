//! Technical MediaMTX boundary, hosted by the Streaming runtime in P1.
//! Its database stays separate; business state uses authenticated HTTP contracts.

mod config;
mod handlers;
mod mediamtx;
mod operator;
mod repository;
mod workers;

use std::{future::IntoFuture, sync::Arc, time::Duration};

use axum::Router;
use reqwest::Client;
use sqlx::postgres::PgPoolOptions;
use tokio::sync::watch;

use config::MediaConfig;
use repository::MediaRepository;

pub(crate) struct MediaState {
    config: MediaConfig,
    repository: MediaRepository,
    client: Client,
}

type MediaError = Box<dyn std::error::Error + Send + Sync>;

/// Operator CLI only. The server is supervised by `streaming-service`.
pub async fn run_operator_cli() -> Result<(), MediaError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    if arguments.as_slice() != ["migrate"]
        && arguments
            .first()
            .is_none_or(|argument| argument != "dead-letter")
    {
        return Err(
            "usage: media-adapter migrate | dead-letter list|redrive|close EVENT_ID OPERATOR NOTE"
                .into(),
        );
    }
    let config = MediaConfig::from_env()?;
    let pool = connect(&config).await?;
    let result = if arguments.as_slice() == ["migrate"] {
        sqlx::migrate!("./media-adapter/migrations")
            .run(&pool)
            .await
            .map_err(Into::into)
    } else {
        operator::execute(&pool, &arguments[1..]).await
    };
    pool.close().await;
    result
}

async fn connect(config: &MediaConfig) -> Result<sqlx::PgPool, MediaError> {
    Ok(PgPoolOptions::new()
        .max_connections(config.db_max_connections)
        .acquire_timeout(Duration::from_secs(2))
        .idle_timeout(Some(Duration::from_secs(600)))
        .max_lifetime(Some(Duration::from_secs(1800)))
        .connect(&config.database_url)
        .await?)
}

/// Run Media listeners/workers in the parent's Tokio runtime and stop together.
/// Driver errors are deliberately sanitized before reaching the service logger.
pub async fn serve(stopped: watch::Receiver<bool>) -> Result<(), MediaError> {
    serve_inner(stopped)
        .await
        .map_err(|_| "media subsystem stopped; check configuration and private dependencies".into())
}

async fn serve_inner(stopped: watch::Receiver<bool>) -> Result<(), MediaError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let config = MediaConfig::from_env()?;
    let pool = connect(&config).await?;
    if config.run_migrations {
        sqlx::migrate!("./media-adapter/migrations")
            .run(&pool)
            .await?;
    }
    let listener = tokio::net::TcpListener::bind(config.bind_addr).await?;
    let hls_listener = tokio::net::TcpListener::bind(config.hls_bind_addr).await?;
    tracing::info!(address=%config.bind_addr, "media authorization API listening");
    tracing::info!(address=%config.hls_bind_addr, "media HLS listening");
    let state = Arc::new(MediaState {
        client: crate::adapters::outbound::http_clients::build_client(Duration::from_secs(2))?,
        config,
        repository: MediaRepository::new(pool),
    });
    let (shutdown, worker_stopped) = watch::channel(false);
    let worker = tokio::spawn(workers::run(Arc::clone(&state), worker_stopped));
    let router: Router = handlers::router(Arc::clone(&state));
    let server_stopped = shutdown.subscribe();
    let server = axum::serve(listener, router)
        .with_graceful_shutdown(wait_for_shutdown(server_stopped))
        .into_future();
    let hls_server = axum::serve(hls_listener, handlers::hls_router(Arc::clone(&state)))
        .with_graceful_shutdown(wait_for_shutdown(shutdown.subscribe()))
        .into_future();
    tokio::pin!(server);
    tokio::pin!(hls_server);
    let mut worker = worker;
    let (result, finished) = tokio::select! {
        result = &mut server => (result.map_err(std::io::Error::other), 1),
        result = &mut hls_server => (result.map_err(std::io::Error::other), 2),
        _ = wait_for_shutdown(stopped) => (Ok(()), 0),
        result = &mut worker => {
            let _ = result;
            (Err(std::io::Error::other("media worker stopped unexpectedly")), 0)
        }
    };
    shutdown.send_replace(true);
    let _ = tokio::time::timeout(Duration::from_secs(5), async {
        match finished {
            1 => {
                let _ = hls_server.await;
            }
            2 => {
                let _ = server.await;
            }
            _ => {
                let _ = tokio::join!(&mut server, &mut hls_server);
            }
        }
    })
    .await;
    if !worker.is_finished()
        && tokio::time::timeout(Duration::from_secs(5), &mut worker)
            .await
            .is_err()
    {
        worker.abort();
    }
    state.repository.pool.close().await;
    result?;
    Ok(())
}

async fn wait_for_shutdown(mut shutdown: watch::Receiver<bool>) {
    if !*shutdown.borrow() {
        let _ = shutdown.changed().await;
    }
}
#[cfg(test)]
mod tests;
