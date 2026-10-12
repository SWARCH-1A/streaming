use std::sync::Arc;

use crate::{
    adapters::outbound::{
        mediamtx::MediaMtxHlsGateway, postgres::PostgresDatabaseProbe,
        worker_health::BackgroundWorkerHealth,
    },
    application::ports::{
        media_server::MediaServerGateway,
        readiness_probe::{ReadinessProbe, ReadinessProbeError},
    },
};

pub struct StreamingReadinessProbe {
    database: PostgresDatabaseProbe,
    media_server: Arc<MediaMtxHlsGateway>,
    workers: Arc<BackgroundWorkerHealth>,
}

impl StreamingReadinessProbe {
    pub(crate) fn new(
        database: PostgresDatabaseProbe,
        media_server: Arc<MediaMtxHlsGateway>,
        workers: Arc<BackgroundWorkerHealth>,
    ) -> Self {
        Self {
            database,
            media_server,
            workers,
        }
    }
}

impl ReadinessProbe for StreamingReadinessProbe {
    fn check(&self) -> impl std::future::Future<Output = Result<(), ReadinessProbeError>> + Send {
        async move {
            let (database, media_server) =
                tokio::join!(self.database.check(), self.media_server.check_control_api(),);
            database?;
            media_server.map_err(|_| ReadinessProbeError)?;

            if !self.workers.all_registered_workers_active() {
                return Err(ReadinessProbeError);
            }

            Ok(())
        }
    }
}
