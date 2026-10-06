use std::process::ExitCode;

#[tokio::main]
async fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .with_target(false)
        .init();
    match streaming_service::media_adapter::run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(_) => {
            // Driver/network errors can contain URLs or credentials.
            tracing::error!("media adapter stopped; check configuration and private dependencies");
            ExitCode::FAILURE
        }
    }
}
