use std::{future::IntoFuture, io, process::ExitCode, time::Duration};

use streaming_service::{bootstrap, config::AppConfig};
use tracing_subscriber::{EnvFilter, fmt};

#[tokio::main]
async fn main() -> ExitCode {
    init_tracing();

    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            tracing::error!(error = %error, "streaming service stopped");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let command =
        streaming_service::adapters::inbound::cli::parse_operator_cli(std::env::args_os().skip(1))?;
    if let Some(command) = command {
        streaming_service::bootstrap::run_operator_cli(command).await?;
        return Ok(());
    }

    let config = AppConfig::from_env()?;
    let listener = tokio::net::TcpListener::bind(config.bind_addr).await?;
    let private_listener = tokio::net::TcpListener::bind(config.private_bind_addr).await?;
    let app = bootstrap::build_app(&config).await?;
    tracing::info!(address=%config.bind_addr,"streaming public API listening");
    tracing::info!(address=%config.private_bind_addr,"streaming private API listening");
    let (stop, stopped) = tokio::sync::watch::channel(false);
    let server = axum::serve(listener, app.router())
        .with_graceful_shutdown(wait_for_stop(stopped))
        .into_future();
    let private = axum::serve(private_listener, app.private_router())
        .with_graceful_shutdown(wait_for_stop(stop.subscribe()))
        .into_future();
    tokio::pin!(server);
    tokio::pin!(private);
    let failure = app.worker_failure_signal();
    let (server_result, finished) = tokio::select! {
        result=&mut server=>(result,1),result=&mut private=>(result,2),
        _=shutdown_signal()=>(Ok(()),0),_=failure=>(Ok(()),0),
    };
    stop.send_replace(true);
    let _ = tokio::time::timeout(Duration::from_secs(5), async {
        match finished {
            1 => {
                let _ = private.await;
            }
            2 => {
                let _ = server.await;
            }
            _ => {
                let _ = tokio::join!(&mut server, &mut private);
            }
        }
    })
    .await;
    let worker_failure = app.shutdown().await;

    if let Some(worker) = worker_failure {
        return Err(
            io::Error::other(format!("background worker {worker} exited unexpectedly")).into(),
        );
    }

    server_result?;

    Ok(())
}

fn init_tracing() {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    fmt()
        .with_env_filter(filter)
        .with_target(false)
        .compact()
        .init();
}

async fn shutdown_signal() {
    let ctrl_c = async {
        if let Err(error) = tokio::signal::ctrl_c().await {
            tracing::warn!(error = %error, "failed to listen for Ctrl-C");
        }
    };

    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut signal) => {
                signal.recv().await;
            }
            Err(error) => tracing::warn!(error = %error, "failed to listen for SIGTERM"),
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}

async fn wait_for_stop(mut stopped: tokio::sync::watch::Receiver<bool>) {
    if !*stopped.borrow() {
        let _ = stopped.changed().await;
    }
}
