//! Technical MediaMTX boundary. This process has its own database and never reads
//! Streaming's domain tables; it uses HTTP contracts for business state.

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

pub async fn run() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    if !arguments.is_empty()
        && arguments.as_slice() != ["migrate"]
        && arguments.first().is_none_or(|a| a != "dead-letter")
    {
        return Err(
            "usage: media-adapter [migrate|dead-letter list|redrive|close EVENT_ID OPERATOR NOTE]"
                .into(),
        );
    }
    let config = MediaConfig::from_env()?;
    let pool = PgPoolOptions::new()
        .max_connections(config.db_max_connections)
        .acquire_timeout(Duration::from_secs(2))
        .idle_timeout(Some(Duration::from_secs(600)))
        .max_lifetime(Some(Duration::from_secs(1800)))
        .connect(&config.database_url)
        .await?;
    if std::env::args().nth(1).as_deref() == Some("migrate") {
        sqlx::migrate!("./media-adapter/migrations")
            .run(&pool)
            .await?;
        return Ok(());
    }
    if std::env::args().nth(1).as_deref() == Some("dead-letter") {
        let args = std::env::args().skip(2).collect::<Vec<_>>();
        return operator::execute(&pool, &args).await;
    }
    if config.run_migrations {
        sqlx::migrate!("./media-adapter/migrations")
            .run(&pool)
            .await?;
    }
    let listener = tokio::net::TcpListener::bind(config.bind_addr).await?;
    let hls_listener = tokio::net::TcpListener::bind(config.hls_bind_addr).await?;
    let state = Arc::new(MediaState {
        client: crate::adapters::outbound::http_clients::build_client(Duration::from_secs(2))?,
        config,
        repository: MediaRepository::new(pool),
    });
    let (shutdown, stopped) = watch::channel(false);
    let worker = tokio::spawn(workers::run(Arc::clone(&state), stopped));
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
        _ = shutdown_signal() => (Ok(()), 0),
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
    result?;
    Ok(())
}

async fn wait_for_shutdown(mut shutdown: watch::Receiver<bool>) {
    if !*shutdown.borrow() {
        let _ = shutdown.changed().await;
    }
}
async fn shutdown_signal() {
    #[cfg(unix)]
    {
        let terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate());
        if let Ok(mut terminate) = terminate {
            tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = terminate.recv() => {} }
            return;
        }
    }
    let _ = tokio::signal::ctrl_c().await;
}

#[cfg(test)]
mod tests;
