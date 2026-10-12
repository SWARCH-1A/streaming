use std::{
    env,
    future::Future,
    io::{self, Write},
    panic::AssertUnwindSafe,
    sync::Arc,
    time::Duration,
};

use axum::Router;
use futures_util::FutureExt;
use serde_json::json;
use sqlx::postgres::PgPoolOptions;
use thiserror::Error;
use time::format_description::well_known::Rfc3339;
use tokio::{sync::watch, task::JoinHandle, time::timeout};

use crate::{
    adapters::{
        inbound::{cli::OperatorCliCommand, http},
        outbound::{
            http_clients::{CoreHttpGateway, HttpDomainEventPublisher, build_client},
            media_node_assignment::ConfiguredMediaNodeAssignment,
            mediamtx::MediaMtxHlsGateway,
            monotonic_clock::MonotonicSessionClock,
            os_secret_generator::OsSecretGenerator,
            postgres::{
                PostgresDatabaseProbe, PostgresDiscoveryProjectionRepository,
                PostgresDomainEventOutboxRepository, PostgresMediaCallbackRepository,
                PostgresMediaDeadLetterRepository, PostgresSessionDeadlineRepository,
                PostgresSessionTimelineRepository, PostgresStreamConfigRepository,
                PostgresStreamingRepository, PostgresViewerCountSnapshotRepository,
                PostgresViewerLeaseRepository, PostgresWorkerQueueMetricsRepository,
            },
            readiness::StreamingReadinessProbe,
            viewer_lease_credentials::HmacViewerLeaseCredentialIssuer,
            worker_health::BackgroundWorkerHealth,
        },
    },
    application::{
        ports::{
            discovery_projection::DiscoveryProjectionRepository,
            ingest_authorization::IngestAuthorizationRepository,
            media_node_assignment::MediaNodeAssignmentPolicy,
        },
        use_cases::{
            AuthorizeIngest, CheckReadiness, CheckpointSessionTimeline, CloseViewerLease,
            CreateStreamConfig, CreateViewerLease, ExpireSessionDeadlines, GetPublicStream,
            GetPublicStreamSession, GetStreamConfig, HeartbeatViewerLease, ManageMediaDeadLetters,
            PatchStreamMetadata, ProcessMediaCallbacks, RecordMediaCallback,
            RefreshViewerCountSnapshots, RelayDomainEvents, ReportWorkerQueueMetrics,
            RotateIngestKey, StopStreamSession,
        },
    },
    config::{AppConfig, ConfigError, Environment, environment_from_env, require_postgres_tls},
};

#[derive(Debug, Error)]
pub enum BootstrapError {
    #[error("could not connect to the streaming database")]
    Database(#[from] sqlx::Error),
    #[error("could not apply streaming database migrations")]
    Migration(#[from] sqlx::migrate::MigrateError),
    #[error("could not configure the Rustls crypto provider")]
    TlsProvider,
    #[error("another Streaming control process already owns this private database")]
    ControlOwnerPresent,
    #[error("could not build the outbound HTTP client")]
    HttpClient(#[from] crate::adapters::outbound::http_clients::ClientError),
}

#[derive(Debug, Error)]
pub enum OperatorCliError {
    #[error("STREAMING_DATABASE_URL is required for dead-letter operations")]
    MissingDatabaseUrl,
    #[error("STREAMING_MIGRATIONS_DATABASE_URL is required for the migration command")]
    MissingMigrationDatabaseUrl,
    #[error(transparent)]
    Config(#[from] ConfigError),
    #[error("could not configure the Rustls crypto provider")]
    TlsProvider,
    #[error("could not connect to the streaming database")]
    Database(#[from] sqlx::Error),
    #[error("could not apply streaming database migrations")]
    Migration(#[from] sqlx::migrate::MigrateError),
    #[error(transparent)]
    DeadLetters(#[from] crate::application::use_cases::ManageMediaDeadLettersError),
    #[error("could not serialize the dead-letter command output")]
    Serialize(#[from] serde_json::Error),
    #[error("could not format a dead-letter timestamp")]
    Timestamp,
    #[error("could not write command output")]
    Output(#[from] io::Error),
    #[error("delivery operation failed; check arguments, database and dead-letter state")]
    DeliveryOperation,
}

const BACKGROUND_WORKER_SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(5);

pub struct StreamingApp {
    router: Router,
    private_router: Router,
    worker_shutdown: watch::Sender<bool>,
    worker_tasks: Vec<(&'static str, JoinHandle<()>)>,
    worker_failures: watch::Receiver<Option<&'static str>>,
}

impl StreamingApp {
    pub fn private_router(&self) -> Router {
        self.private_router.clone()
    }

    pub fn router(&self) -> Router {
        self.router.clone()
    }

    pub fn worker_failure_signal(&self) -> impl Future<Output = ()> + Send + 'static {
        let mut failures = self.worker_failures.clone();
        async move {
            if failures.borrow().is_some() {
                return;
            }

            let _ = failures.changed().await;
        }
    }

    pub async fn shutdown(mut self) -> Option<&'static str> {
        self.worker_shutdown.send_replace(true);

        for (worker_name, mut task) in self.worker_tasks.drain(..) {
            match timeout(BACKGROUND_WORKER_SHUTDOWN_TIMEOUT, &mut task).await {
                Ok(Ok(())) => {}
                Ok(Err(_)) => {
                    tracing::warn!(
                        worker = worker_name,
                        "background worker stopped unexpectedly"
                    );
                }
                Err(_) => {
                    task.abort();
                    let _ = task.await;
                    tracing::warn!(
                        worker = worker_name,
                        "background worker exceeded shutdown timeout"
                    );
                }
            }
        }

        *self.worker_failures.borrow()
    }
}

fn spawn_cancellable_worker(
    worker_name: &'static str,
    mut shutdown: watch::Receiver<bool>,
    worker_health: Arc<BackgroundWorkerHealth>,
    worker_failure_sender: watch::Sender<Option<&'static str>>,
    task: impl Future<Output = ()> + Send + 'static,
) -> JoinHandle<()> {
    worker_health.register_worker();
    tokio::spawn(async move {
        let _active_worker = worker_health.enter_worker();
        let result = tokio::select! {
            biased;
            _ = wait_for_worker_shutdown(&mut shutdown) => {
                None
            }
            result = AssertUnwindSafe(task).catch_unwind() => Some(result),
        };

        match result {
            None => tracing::debug!(worker = worker_name, "background worker received shutdown"),
            Some(Ok(())) => {
                tracing::error!(
                    worker = worker_name,
                    "background worker exited unexpectedly"
                );
                worker_failure_sender.send_replace(Some(worker_name));
            }
            Some(Err(_)) => {
                tracing::error!(worker = worker_name, "background worker panicked");
                worker_failure_sender.send_replace(Some(worker_name));
            }
        }
    })
}

async fn wait_for_worker_shutdown(shutdown: &mut watch::Receiver<bool>) {
    if *shutdown.borrow() {
        return;
    }

    while shutdown.changed().await.is_ok() {
        if *shutdown.borrow() {
            return;
        }
    }
}

pub async fn run_operator_cli(command: OperatorCliCommand) -> Result<(), OperatorCliError> {
    match command {
        OperatorCliCommand::DeliveryDeadLetters { arguments } => {
            let url = env::var("STREAMING_DATABASE_URL")
                .map_err(|_| OperatorCliError::MissingDatabaseUrl)?;
            validate_operator_database_url("STREAMING_DATABASE_URL", &url)?;
            let _ = rustls::crypto::ring::default_provider().install_default();
            let pool = PgPoolOptions::new()
                .max_connections(1)
                .acquire_timeout(Duration::from_secs(2))
                .connect(&url)
                .await?;
            let result = crate::adapters::outbound::postgres::delivery_operations::execute(
                &pool, &arguments,
            )
            .await
            .map_err(|_| OperatorCliError::DeliveryOperation);
            pool.close().await;
            result
        }
        OperatorCliCommand::Help => {
            write_output(crate::adapters::inbound::cli::usage())?;
            Ok(())
        }
        OperatorCliCommand::Migrate => run_migrations_cli().await,
        OperatorCliCommand::ListDeadLetters { limit } => {
            run_dead_letter_cli(DeadLetterOperation::List { limit }).await
        }
        OperatorCliCommand::RedriveDeadLetter {
            event_id,
            operator_id,
            resolution_note,
        } => {
            run_dead_letter_cli(DeadLetterOperation::Redrive {
                event_id,
                operator_id,
                resolution_note,
            })
            .await
        }
        OperatorCliCommand::CloseDeadLetter {
            event_id,
            operator_id,
            resolution_note,
        } => {
            run_dead_letter_cli(DeadLetterOperation::Close {
                event_id,
                operator_id,
                resolution_note,
            })
            .await
        }
    }
}

async fn run_migrations_cli() -> Result<(), OperatorCliError> {
    let database_url = env::var("STREAMING_MIGRATIONS_DATABASE_URL")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .ok_or(OperatorCliError::MissingMigrationDatabaseUrl)?;
    validate_operator_database_url("STREAMING_MIGRATIONS_DATABASE_URL", &database_url)?;
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| OperatorCliError::TlsProvider)?;
    let pool = PgPoolOptions::new()
        .max_connections(1)
        .acquire_timeout(Duration::from_secs(10))
        .connect(&database_url)
        .await?;
    sqlx::migrate!().run(&pool).await?;
    pool.close().await;
    write_output("streaming migrations applied")?;
    Ok(())
}

enum DeadLetterOperation {
    List {
        limit: i64,
    },
    Redrive {
        event_id: uuid::Uuid,
        operator_id: String,
        resolution_note: String,
    },
    Close {
        event_id: uuid::Uuid,
        operator_id: String,
        resolution_note: String,
    },
}

async fn run_dead_letter_cli(operation: DeadLetterOperation) -> Result<(), OperatorCliError> {
    let database_url = env::var("STREAMING_DATABASE_URL")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .ok_or(OperatorCliError::MissingDatabaseUrl)?;
    validate_operator_database_url("STREAMING_DATABASE_URL", &database_url)?;
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| OperatorCliError::TlsProvider)?;
    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect(&database_url)
        .await?;
    let repository = Arc::new(PostgresMediaDeadLetterRepository::new(pool.clone()));
    let management = ManageMediaDeadLetters::new(repository);
    let result = match operation {
        DeadLetterOperation::List { limit } => {
            let page = management.list_open(limit).await?;
            let items = page
                .items
                .into_iter()
                .map(|item| {
                    Ok(json!({
                        "deadLetterId": item.dead_letter_id,
                        "eventId": item.event_id,
                        "aggregateId": item.aggregate_id,
                        "eventType": item.event_type,
                        "payload": item.payload,
                        "reasonCode": item.reason_code,
                        "attempts": item.attempts,
                        "firstFailedAtUtc": item.first_failed_at.format(&Rfc3339).map_err(|_| OperatorCliError::Timestamp)?,
                        "lastFailedAtUtc": item.last_failed_at.format(&Rfc3339).map_err(|_| OperatorCliError::Timestamp)?,
                    }))
                })
                .collect::<Result<Vec<_>, OperatorCliError>>()?;
            write_json(json!({"items": items, "hasMore": page.has_more}))
        }
        DeadLetterOperation::Redrive {
            event_id,
            operator_id,
            resolution_note,
        } => {
            management
                .redrive(event_id, &operator_id, &resolution_note)
                .await?;
            write_json(json!({"eventId": event_id, "status": "redriven"}))
        }
        DeadLetterOperation::Close {
            event_id,
            operator_id,
            resolution_note,
        } => {
            management
                .close(event_id, &operator_id, &resolution_note)
                .await?;
            write_json(json!({"eventId": event_id, "status": "closed"}))
        }
    };
    pool.close().await;
    result
}

fn validate_operator_database_url(
    name: &'static str,
    database_url: &str,
) -> Result<(), OperatorCliError> {
    match environment_from_env()? {
        Environment::Development => Ok(()),
        Environment::Production => {
            require_postgres_tls(name, database_url)?;
            Ok(())
        }
    }
}

fn write_json(value: serde_json::Value) -> Result<(), OperatorCliError> {
    write_output(&serde_json::to_string_pretty(&value)?)
}

fn write_output(output: &str) -> Result<(), OperatorCliError> {
    let mut stdout = io::stdout().lock();
    writeln!(stdout, "{output}")?;
    Ok(())
}

pub async fn build_app(config: &AppConfig) -> Result<StreamingApp, BootstrapError> {
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| BootstrapError::TlsProvider)?;

    let http_client = build_client(config.dependency_timeout)?;
    let pool = PgPoolOptions::new()
        .min_connections(config.database_min_connections)
        .max_connections(config.database_max_connections)
        .acquire_timeout(config.database_acquire_timeout)
        .idle_timeout(Some(config.database_idle_timeout))
        .max_lifetime(Some(config.database_max_lifetime))
        .connect(&config.database_url)
        .await?;

    let control_owner =
        crate::adapters::outbound::postgres::control_owner::acquire(&config.database_url)
            .await?
            .ok_or(BootstrapError::ControlOwnerPresent)?;
    if config.run_migrations {
        sqlx::migrate!().run(&pool).await?;
    }

    let terminated =
        crate::adapters::outbound::postgres::control_owner::terminate_unverifiable_sessions(&pool)
            .await?;
    if terminated > 0 {
        tracing::warn!(
            sessions = terminated,
            "closed sessions whose predecessor owner clock is unavailable"
        );
    }

    if let (Some(media_node_id), Some(control_api_url)) = (
        config.media_node_id.as_deref(),
        config.media_control_api_url.as_deref(),
    ) {
        sqlx::query(
            "INSERT INTO media_nodes (media_node_id, display_name, control_api_url) VALUES ($1, $2, $3) ON CONFLICT (media_node_id) DO UPDATE SET display_name = EXCLUDED.display_name, control_api_url = EXCLUDED.control_api_url, enabled = TRUE, updated_at = clock_timestamp()",
        )
        .bind(media_node_id)
        .bind(format!("MediaMTX node {media_node_id}"))
        .bind(control_api_url)
        .execute(&pool)
        .await?;
    }

    let read_pool = if let Some(database_read_url) = config.database_read_url.as_deref() {
        Some(
            PgPoolOptions::new()
                .max_connections(config.database_read_max_connections)
                .acquire_timeout(config.database_acquire_timeout)
                .idle_timeout(Some(config.database_idle_timeout))
                .max_lifetime(Some(config.database_max_lifetime))
                .connect(database_read_url)
                .await?,
        )
    } else {
        None
    };

    let media_server = Arc::new(MediaMtxHlsGateway::new(
        http_client.clone(),
        config.media_node_id.clone(),
        config.media_control_api_url.clone(),
        config.media_control_api_username.clone(),
        config.media_control_api_password.clone(),
        config.mediamtx_hls_internal_base_url.clone(),
        config.media_adapter_service_token.clone(),
    ));
    let worker_health = Arc::new(BackgroundWorkerHealth::new());
    let readiness_probe = Arc::new(StreamingReadinessProbe::new(
        PostgresDatabaseProbe::new(
            pool.clone(),
            read_pool.clone(),
            config.database_acquire_timeout,
        ),
        Arc::clone(&media_server),
        Arc::clone(&worker_health),
    ));
    let readiness = Arc::new(CheckReadiness::new(readiness_probe));
    let media_node_assignment: Arc<dyn MediaNodeAssignmentPolicy> = Arc::new(
        ConfiguredMediaNodeAssignment::new(config.media_node_id.clone()),
    );
    let authorization_repository = Arc::new(
        crate::adapters::outbound::postgres::PostgresIngestAuthorizationRepository::new(
            pool.clone(),
            media_node_assignment,
        ),
    );
    let media_callback_repository = Arc::new(PostgresMediaCallbackRepository::new(pool.clone()));
    let session_deadline_repository =
        Arc::new(PostgresSessionDeadlineRepository::new(pool.clone()));
    let session_timeline_repository =
        Arc::new(PostgresSessionTimelineRepository::new(pool.clone()));
    let viewer_count_snapshot_repository = PostgresViewerCountSnapshotRepository::new(pool.clone());
    let stream_config_repository = Arc::new(PostgresStreamConfigRepository::new(pool.clone()));
    let streaming_repository = Arc::new(match read_pool {
        Some(read_pool) => PostgresStreamingRepository::with_read_pool(pool.clone(), read_pool),
        None => PostgresStreamingRepository::new(pool.clone()),
    });
    let identity = Arc::new(CoreHttpGateway::new(
        http_client.clone(),
        config.core_base_url.clone(),
        config.core_service_token.clone(),
        config.session_cookie_name.clone(),
    ));
    let channels = Arc::clone(&identity);
    let taxonomy = Arc::clone(&identity);
    let secret_generator = Arc::new(OsSecretGenerator);
    let create_viewer_lease = Arc::new(CreateViewerLease::new(
        HmacViewerLeaseCredentialIssuer::new(config.viewer_lease_hmac_key.clone()),
        PostgresViewerLeaseRepository::new(pool.clone()),
    ));
    let heartbeat_viewer_lease = Arc::new(HeartbeatViewerLease::new(
        PostgresViewerLeaseRepository::new(pool.clone()),
    ));
    let close_viewer_lease = Arc::new(CloseViewerLease::new(PostgresViewerLeaseRepository::new(
        pool.clone(),
    )));
    let create_stream_config = Arc::new(CreateStreamConfig::new(
        Arc::clone(&identity),
        Arc::clone(&stream_config_repository),
        Arc::clone(&secret_generator),
        config.rtmp_ingest_base_url.clone(),
    ));
    let get_stream_config = Arc::new(GetStreamConfig::new(
        Arc::clone(&identity),
        Arc::clone(&channels),
        Arc::clone(&streaming_repository),
        config.rtmp_ingest_base_url.clone(),
    ));
    let control_clock = Arc::new(MonotonicSessionClock::default());
    let session_clock: Arc<dyn crate::application::ports::session_clock::SessionMonotonicClock> =
        control_clock.clone();
    let get_public_stream_session = Arc::new(GetPublicStreamSession::new(
        Arc::clone(&streaming_repository),
        config.public_hls_base_url.clone(),
        Arc::clone(&session_clock),
    ));
    let get_public_stream = Arc::new(GetPublicStream::new(
        Arc::clone(&streaming_repository),
        Arc::clone(&taxonomy),
    ));
    let patch_stream_metadata = Arc::new(PatchStreamMetadata::new(
        Arc::clone(&identity),
        Arc::clone(&stream_config_repository),
        Arc::clone(&streaming_repository),
    ));
    let rotate_ingest_key = Arc::new(RotateIngestKey::new(
        Arc::clone(&identity),
        Arc::clone(&stream_config_repository),
        Arc::clone(&streaming_repository),
        Arc::clone(&secret_generator),
        config.rtmp_ingest_base_url.clone(),
    ));
    let stop_stream_session = Arc::new(StopStreamSession::new(
        identity,
        Arc::clone(&streaming_repository),
        Arc::clone(&session_clock),
    ));
    let authorize_ingest = Arc::new(AuthorizeIngest::new(
        Arc::clone(&authorization_repository),
        Arc::clone(&session_clock),
        config.instance_id.clone(),
    ));
    let record_media_callback = Arc::new(RecordMediaCallback::new(Arc::clone(
        &media_callback_repository,
    )));
    let process_media_callbacks = Arc::new(ProcessMediaCallbacks::new(
        Arc::clone(&media_callback_repository),
        media_server,
        Arc::clone(&session_clock),
    ));

    let discovery_repository = Arc::new(PostgresDiscoveryProjectionRepository::new(pool.clone()));
    let private_router = http::core_context::router(
        Arc::clone(&streaming_repository),
        Arc::clone(&get_public_stream_session),
        Arc::clone(&discovery_repository),
        config.core_consumer_token.as_deref(),
    );

    let (worker_shutdown, worker_shutdown_rx) = watch::channel(false);
    let (worker_failure_sender, worker_failures) = watch::channel(None);
    let mut worker_tasks = Vec::with_capacity(10);
    let guard_clock = Arc::clone(&control_clock);
    let guard_shutdown = worker_shutdown.clone();
    let guard_failure = worker_failure_sender.clone();
    worker_tasks.push((
        "control-owner",
        spawn_cancellable_worker(
            "control-owner",
            worker_shutdown_rx.clone(),
            Arc::clone(&worker_health),
            worker_failure_sender.clone(),
            async move {
                let mut connection = control_owner;
                let mut interval = tokio::time::interval(Duration::from_millis(500));
                interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
                loop {
                    interval.tick().await;
                    let result = timeout(
                        Duration::from_secs(1),
                        sqlx::query("SELECT 1").execute(&mut connection),
                    )
                    .await;
                    if !matches!(result, Ok(Ok(_))) {
                        guard_clock.invalidate();
                        guard_failure.send_replace(Some("control-owner"));
                        guard_shutdown.send_replace(true);
                        tracing::error!("exclusive Streaming control connection lost");
                        return;
                    }
                }
            },
        ),
    ));

    let callback_worker = Arc::clone(&process_media_callbacks);
    let callback_worker_owner = config.instance_id.clone();
    worker_tasks.push((
        "media-callbacks",
        spawn_cancellable_worker(
            "media-callbacks",
            worker_shutdown_rx.clone(),
            Arc::clone(&worker_health),
            worker_failure_sender.clone(),
            async move {
                callback_worker.run(callback_worker_owner).await;
            },
        ),
    ));

    let expire_session_deadlines = Arc::new(ExpireSessionDeadlines::new(
        session_deadline_repository,
        Arc::clone(&session_clock),
    ));
    let deadline_worker = Arc::clone(&expire_session_deadlines);
    let deadline_worker_owner = config.instance_id.clone();
    worker_tasks.push((
        "session-deadlines",
        spawn_cancellable_worker(
            "session-deadlines",
            worker_shutdown_rx.clone(),
            Arc::clone(&worker_health),
            worker_failure_sender.clone(),
            async move {
                deadline_worker.run(deadline_worker_owner).await;
            },
        ),
    ));

    let checkpoint_session_timeline = Arc::new(CheckpointSessionTimeline::new(
        session_timeline_repository,
        Arc::clone(&session_clock),
    ));
    let timeline_worker = Arc::clone(&checkpoint_session_timeline);
    let timeline_worker_owner = config.instance_id.clone();
    worker_tasks.push((
        "session-timeline-checkpoint",
        spawn_cancellable_worker(
            "session-timeline-checkpoint",
            worker_shutdown_rx.clone(),
            Arc::clone(&worker_health),
            worker_failure_sender.clone(),
            async move {
                timeline_worker.run_worker(timeline_worker_owner).await;
            },
        ),
    ));

    let refresh_viewer_count_snapshots = Arc::new(RefreshViewerCountSnapshots::new(
        viewer_count_snapshot_repository,
    ));
    worker_tasks.push((
        "viewer-count-snapshots",
        spawn_cancellable_worker(
            "viewer-count-snapshots",
            worker_shutdown_rx.clone(),
            Arc::clone(&worker_health),
            worker_failure_sender.clone(),
            async move {
                let mut interval = tokio::time::interval(Duration::from_millis(500));
                interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
                loop {
                    interval.tick().await;
                    if let Err(error) = refresh_viewer_count_snapshots.run().await {
                        tracing::warn!(error = ?error, "could not refresh viewer count snapshots");
                    }
                }
            },
        ),
    ));

    let report_worker_queue_metrics = Arc::new(ReportWorkerQueueMetrics::new(Arc::new(
        PostgresWorkerQueueMetricsRepository::new(pool.clone()),
    )));
    worker_tasks.push((
        "worker-queue-metrics",
        spawn_cancellable_worker(
            "worker-queue-metrics",
            worker_shutdown_rx.clone(),
            Arc::clone(&worker_health),
            worker_failure_sender.clone(),
            async move {
                report_worker_queue_metrics.run_worker().await;
            },
        ),
    ));

    worker_tasks.push((
        "discovery-observations",
        spawn_cancellable_worker(
            "discovery-observations",
            worker_shutdown_rx.clone(),
            Arc::clone(&worker_health),
            worker_failure_sender.clone(),
            async move {
                let mut interval = tokio::time::interval(Duration::from_millis(500));
                interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
                loop {
                    interval.tick().await;
                    if discovery_repository.refresh_due().await.is_err() {
                        tracing::warn!("discovery observation unavailable");
                    }
                }
            },
        ),
    ));
    for consumer in ["chat", "discovery"] {
        let (repository, url, token) = if consumer == "discovery" {
            (
                PostgresDomainEventOutboxRepository::for_discovery(pool.clone()),
                config.core_base_url.as_ref().map(|base| {
                    format!(
                        "{}/internal/core/discovery/stream-events",
                        base.trim_end_matches('/')
                    )
                }),
                config.core_service_token.clone(),
            )
        } else {
            (
                PostgresDomainEventOutboxRepository::new(pool.clone()),
                config.chat_base_url.as_ref().map(|base| {
                    format!(
                        "{}/internal/chat/session-events",
                        base.trim_end_matches('/')
                    )
                }),
                config.chat_service_token.clone(),
            )
        };
        if url.is_some() && token.is_some() {
            let relay = RelayDomainEvents::new(
                Arc::new(repository),
                Arc::new(HttpDomainEventPublisher::new(
                    http_client.clone(),
                    url,
                    token,
                )),
            );
            let owner = config.instance_id.clone();
            worker_tasks.push((
                consumer,
                spawn_cancellable_worker(
                    consumer,
                    worker_shutdown_rx.clone(),
                    Arc::clone(&worker_health),
                    worker_failure_sender.clone(),
                    async move { relay.run(owner).await },
                ),
            ));
        }
    }

    worker_tasks.push((
        "owner-lease-renewal",
        spawn_owner_lease_renewal(
            Arc::clone(&authorization_repository),
            config.instance_id.clone(),
            worker_shutdown_rx,
            Arc::clone(&worker_health),
            worker_failure_sender,
        ),
    ));

    let http_endpoints = http::router(http::RouterDependencies {
        readiness,
        authorize_ingest,
        record_media_callback,
        create_stream_config,
        get_stream_config,
        get_public_stream,
        get_public_stream_session,
        patch_stream_metadata,
        rotate_ingest_key,
        stop_stream_session,
        create_viewer_lease,
        heartbeat_viewer_lease,
        close_viewer_lease,
        media_adapter_service_token: config.media_adapter_service_token.clone(),
        session_cookie_name: config.session_cookie_name.clone(),
        web_origin: config.web_origin.clone(),
    });
    let owner_gate = axum::middleware::from_fn(
        move |request: axum::extract::Request, next: axum::middleware::Next| {
            let clock = Arc::clone(&control_clock);
            async move {
                use axum::response::IntoResponse;
                if !clock.is_available() {
                    return (axum::http::StatusCode::SERVICE_UNAVAILABLE, axum::Json(json!({"code":"STREAMING_UNAVAILABLE","message":"Streaming control is unavailable."}))).into_response();
                }
                next.run(request).await
            }
        },
    );
    let router = http_endpoints.public.layer(owner_gate.clone());
    let private_router = http_endpoints
        .private
        .merge(private_router)
        .layer(owner_gate);

    Ok(StreamingApp {
        router,
        private_router,
        worker_shutdown,
        worker_tasks,
        worker_failures,
    })
}

fn spawn_owner_lease_renewal<R>(
    repository: Arc<R>,
    owner_instance_id: String,
    shutdown: watch::Receiver<bool>,
    worker_health: Arc<BackgroundWorkerHealth>,
    worker_failure_sender: watch::Sender<Option<&'static str>>,
) -> JoinHandle<()>
where
    R: IngestAuthorizationRepository + 'static,
{
    spawn_cancellable_worker(
        "owner-lease-renewal",
        shutdown,
        worker_health,
        worker_failure_sender,
        async move {
            let mut interval = tokio::time::interval(Duration::from_secs(5));
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                interval.tick().await;
                if let Err(error) = repository
                    .renew_owner_leases(owner_instance_id.clone())
                    .await
                {
                    tracing::warn!(error = %error, "could not renew streaming session owner leases");
                }
            }
        },
    )
}
