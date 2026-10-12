mod discovery_projection;
pub use discovery_projection::PostgresDiscoveryProjectionRepository;
pub mod dead_letters;
pub mod domain_event_outbox;
pub mod ingest_authorization;
pub mod media_callbacks;
pub mod session_timeline;
pub mod stream_config;
pub mod stream_read;
pub mod viewer_count_snapshots;
pub mod viewer_leases;
pub mod worker_queue_metrics;

pub use dead_letters::PostgresMediaDeadLetterRepository;
pub use domain_event_outbox::PostgresDomainEventOutboxRepository;
pub use ingest_authorization::PostgresIngestAuthorizationRepository;
pub use media_callbacks::{PostgresMediaCallbackRepository, PostgresSessionDeadlineRepository};
pub use session_timeline::PostgresSessionTimelineRepository;
pub use stream_config::PostgresStreamConfigRepository;
pub use stream_read::PostgresStreamingRepository;
pub use viewer_count_snapshots::PostgresViewerCountSnapshotRepository;
pub use viewer_leases::PostgresViewerLeaseRepository;
pub use worker_queue_metrics::PostgresWorkerQueueMetricsRepository;

use std::{
    sync::atomic::{AtomicU8, Ordering},
    time::Duration,
};

use sqlx::PgPool;

use crate::application::ports::readiness_probe::ReadinessProbeError;

pub struct PostgresDatabaseProbe {
    write_pool: PgPool,
    read_pool: Option<PgPool>,
    timeout: Duration,
    read_replica_health: AtomicU8,
}

const READ_REPLICA_UNKNOWN: u8 = 0;
const READ_REPLICA_HEALTHY: u8 = 1;
const READ_REPLICA_DEGRADED: u8 = 2;

impl PostgresDatabaseProbe {
    pub fn new(write_pool: PgPool, read_pool: Option<PgPool>, timeout: Duration) -> Self {
        Self {
            write_pool,
            read_pool,
            timeout,
            read_replica_health: AtomicU8::new(READ_REPLICA_UNKNOWN),
        }
    }
}

impl PostgresDatabaseProbe {
    pub async fn check(&self) -> Result<(), ReadinessProbeError> {
        if let Some(read_pool) = self.read_pool.as_ref() {
            let (write_check, read_check) = tokio::join!(
                tokio::time::timeout(self.timeout, check_pool(&self.write_pool, false)),
                tokio::time::timeout(self.timeout, check_pool(read_pool, true))
            );
            self.record_read_replica_health(matches!(read_check, Ok(Ok(()))));
            match write_check {
                Ok(result) => result?,
                Err(_) => return Err(ReadinessProbeError),
            }
        } else {
            tokio::time::timeout(self.timeout, check_pool(&self.write_pool, false))
                .await
                .map_err(|_| ReadinessProbeError)??;
        }
        Ok(())
    }

    fn record_read_replica_health(&self, is_healthy: bool) {
        let state = if is_healthy {
            READ_REPLICA_HEALTHY
        } else {
            READ_REPLICA_DEGRADED
        };
        let previous = self.read_replica_health.swap(state, Ordering::Relaxed);
        if previous == state {
            return;
        }

        if is_healthy {
            tracing::info!(
                dependency = "postgres_read_replica",
                state = "healthy",
                "read replica health changed"
            );
        } else {
            tracing::warn!(
                dependency = "postgres_read_replica",
                state = "degraded",
                reason = "probe_failed_or_not_hot_standby",
                "public stream reads will fall back to primary"
            );
        }
    }
}

async fn check_pool(pool: &PgPool, must_be_replica: bool) -> Result<(), ReadinessProbeError> {
    let schema_ready =
        sqlx::query_scalar::<_, bool>("SELECT to_regclass('stream_configs') IS NOT NULL")
            .fetch_one(pool)
            .await
            .map_err(|_| ReadinessProbeError)?;
    if !schema_ready {
        return Err(ReadinessProbeError);
    }
    if must_be_replica {
        let is_replica = sqlx::query_scalar::<_, bool>("SELECT pg_is_in_recovery()")
            .fetch_one(pool)
            .await
            .map_err(|_| ReadinessProbeError)?;
        if !is_replica {
            return Err(ReadinessProbeError);
        }
    }
    Ok(())
}

pub mod delivery_operations;

pub mod control_owner;
