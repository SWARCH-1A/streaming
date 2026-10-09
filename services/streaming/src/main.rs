use std::{io, process::ExitCode, time::Duration};

use futures_util::{FutureExt, StreamExt, stream::FuturesUnordered};

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
    let tls = streaming_service::transport::tls_from_env().await?;
    tracing::info!(address=%config.bind_addr,"streaming public API listening");
    tracing::info!(address=%config.private_bind_addr,"streaming private API listening");
    let (stop, stopped) = tokio::sync::watch::channel(false);
    let server = streaming_service::transport::serve(listener, app.router(), tls.clone(), stopped);
    let private = streaming_service::transport::serve(
        private_listener,
        app.private_router(),
        tls,
        stop.subscribe(),
    );
    let mut servers = FuturesUnordered::new();
    servers.push(async { server.await }.boxed());
    servers.push(async { private.await }.boxed());
    servers.push(streaming_service::media_adapter::serve(stop.subscribe()).boxed());
    let failure = app.worker_failure_signal();
    let server_result: Result<(), Box<dyn std::error::Error + Send + Sync>> = tokio::select! {
        result = servers.next() => match result {
            Some(Err(error)) => Err(error),
            _ => Err(io::Error::other("a service listener exited unexpectedly").into()),
        },
        _ = shutdown_signal() => Ok(()),
        _ = failure => Ok(()),
    };
    stop.send_replace(true);
    // Media owns two HTTP listeners and its worker drain; keep polling it while
    // the control listeners finish, rather than dropping an unsupervised task.
    if tokio::time::timeout(Duration::from_secs(15), async {
        while servers.next().await.is_some() {}
    })
    .await
    .is_err()
    {
        tracing::warn!("service listeners exceeded shutdown timeout");
    }
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
