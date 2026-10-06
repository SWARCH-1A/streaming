use serde_json::{Value, json};
use sqlx::{FromRow, PgPool};
use time::OffsetDateTime;
use uuid::Uuid;

use crate::application::ports::ingest_authorization::IngestAuthorization;

pub(super) struct MediaRepository {
    pub pool: PgPool,
}

#[derive(Clone, FromRow)]
pub(super) struct Source {
    pub publisher_id: Uuid,
    pub stream_id: String,
    pub session_id: String,
    pub stream_generation: i64,
    pub source_generation: i64,
    pub ingest_path: String,
    pub request_hash: Vec<u8>,
    pub created_at: OffsetDateTime,
    pub connected_at: Option<OffsetDateTime>,
    pub playback_at: Option<OffsetDateTime>,
    pub lost_at: Option<OffsetDateTime>,
    pub retired_at: Option<OffsetDateTime>,
}

#[derive(FromRow)]
pub(super) struct Callback {
    pub event_id: Uuid,
    pub kind: String,
    pub payload: Value,
    pub attempts: i32,
    pub age_ms: i64,
    pub publisher_id: Uuid,
    pub alerted_at: Option<OffsetDateTime>,
    pub claim_token: Uuid,
}

impl MediaRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn save_source(
        &self,
        id: Uuid,
        path: &str,
        hash: &[u8],
        authorization: &IngestAuthorization,
    ) -> Result<(), sqlx::Error> {
        sqlx::query("INSERT INTO media_sources (publisher_id,stream_id,session_id,stream_generation,source_generation,ingest_path,request_hash) VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (publisher_id) DO NOTHING")
            .bind(id).bind(authorization.stream_id.as_str()).bind(authorization.session_id.as_str())
            .bind(authorization.stream_generation).bind(authorization.source_generation).bind(path).bind(hash)
            .execute(&self.pool).await?;
        Ok(())
    }
    pub async fn source(&self, id: Uuid) -> Result<Option<Source>, sqlx::Error> {
        sqlx::query_as("SELECT publisher_id,stream_id,session_id,stream_generation,source_generation,ingest_path,request_hash,created_at,connected_at,playback_at,lost_at,retired_at FROM media_sources WHERE publisher_id=$1")
            .bind(id)
            .fetch_optional(&self.pool)
            .await
    }
    pub async fn binding(&self, session: &str) -> Result<Option<Source>, sqlx::Error> {
        sqlx::query_as("SELECT publisher_id,stream_id,session_id,stream_generation,source_generation,ingest_path,request_hash,created_at,connected_at,playback_at,lost_at,retired_at FROM media_sources WHERE session_id=$1 ORDER BY source_generation DESC LIMIT 1")
            .bind(session).fetch_optional(&self.pool).await
    }
    pub async fn active_sources(&self) -> Result<Vec<Source>, sqlx::Error> {
        sqlx::query_as(
            "SELECT publisher_id,stream_id,session_id,stream_generation,source_generation,ingest_path,request_hash,created_at,connected_at,playback_at,lost_at,retired_at FROM media_sources WHERE retired_at IS NULL ORDER BY created_at LIMIT 100",
        )
        .fetch_all(&self.pool)
        .await
    }
    pub async fn observe(&self, source: &Source, kind: &str) -> Result<(), sqlx::Error> {
        let update = match kind {
            "source-connected" => {
                "UPDATE media_sources SET connected_at=clock_timestamp() WHERE publisher_id=$1 AND connected_at IS NULL AND lost_at IS NULL AND retired_at IS NULL"
            }
            "playback-ready" => {
                "UPDATE media_sources SET playback_at=clock_timestamp() WHERE publisher_id=$1 AND playback_at IS NULL AND connected_at IS NOT NULL AND lost_at IS NULL AND retired_at IS NULL"
            }
            "source-lost" => {
                "UPDATE media_sources SET lost_at=clock_timestamp() WHERE publisher_id=$1 AND lost_at IS NULL AND retired_at IS NULL"
            }
            _ => return Err(sqlx::Error::Protocol("invalid callback kind".to_owned())),
        };
        let mut tx = self.pool.begin().await?;
        let id = Uuid::now_v7();
        let mut payload = json!({
            "eventId":id,"streamId":source.stream_id,"sessionId":source.session_id,
            "streamGeneration":source.stream_generation,"sourceGeneration":source.source_generation,
        });
        if kind == "playback-ready" {
            payload["playbackPath"] = json!(format!("/hls/{}/index.m3u8", source.session_id));
        }
        // The predicate ensures a concurrent probe can never mint another event
        // or resurrect an observation retired by stop/reconciliation.
        let updated = sqlx::query(update)
            .bind(source.publisher_id)
            .execute(&mut *tx)
            .await?;
        if updated.rows_affected() == 1 {
            sqlx::query("INSERT INTO media_callback_outbox(event_id,publisher_id,kind,payload) VALUES($1,$2,$3,$4)")
                .bind(id).bind(source.publisher_id).bind(kind).bind(payload).execute(&mut *tx).await?;
        }
        tx.commit().await
    }
    pub async fn retire(&self, id: Uuid) -> Result<(), sqlx::Error> {
        sqlx::query("UPDATE media_sources SET retired_at=COALESCE(retired_at,clock_timestamp()) WHERE publisher_id=$1")
            .bind(id).execute(&self.pool).await?;
        Ok(())
    }
    pub async fn claim(&self) -> Result<Option<Callback>, sqlx::Error> {
        let token = Uuid::new_v4();
        sqlx::query_as("WITH candidate AS (SELECT event_id FROM media_callback_outbox o WHERE delivered_at IS NULL AND dead_letter_at IS NULL AND closed_at IS NULL AND available_at<=clock_timestamp() AND (claim_until IS NULL OR claim_until<=clock_timestamp()) AND NOT EXISTS(SELECT 1 FROM media_callback_outbox p WHERE p.publisher_id=o.publisher_id AND (p.created_at,p.event_id)<(o.created_at,o.event_id) AND p.delivered_at IS NULL AND p.dead_letter_at IS NULL) ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE media_callback_outbox o SET claim_token=$1,claim_until=clock_timestamp()+INTERVAL '15 seconds',first_attempt_at=COALESCE(first_attempt_at,clock_timestamp()),attempts=attempts+1 FROM candidate c WHERE o.event_id=c.event_id RETURNING o.event_id,o.kind,o.payload,o.attempts,(EXTRACT(EPOCH FROM clock_timestamp()-o.first_attempt_at)*1000)::BIGINT AS age_ms,o.publisher_id,o.alerted_at,o.claim_token")
            .bind(token).fetch_optional(&self.pool).await
    }
    pub async fn alert_once(&self, id: Uuid) -> Result<bool, sqlx::Error> {
        Ok(sqlx::query("UPDATE media_sources SET callback_alerted_at=clock_timestamp() WHERE publisher_id=$1 AND callback_alerted_at IS NULL")
            .bind(id).execute(&self.pool).await?.rows_affected()==1)
    }
    pub async fn finish(
        &self,
        callback: &Callback,
        result: &str,
        delay_ms: i64,
        alert: bool,
    ) -> Result<(), sqlx::Error> {
        let mut tx = self.pool.begin().await?;
        let current=sqlx::query_as::<_,(Option<Uuid>,Option<OffsetDateTime>)>("SELECT claim_token,claim_until FROM media_callback_outbox WHERE event_id=$1 FOR UPDATE")
            .bind(callback.event_id).fetch_one(&mut *tx).await?;
        let now: OffsetDateTime = sqlx::query_scalar("SELECT clock_timestamp()")
            .fetch_one(&mut *tx)
            .await?;
        if current.0 != Some(callback.claim_token) || current.1.is_none_or(|expires| expires <= now)
        {
            return Err(sqlx::Error::Protocol("callback claim lost".to_owned()));
        }
        let updated=sqlx::query("UPDATE media_callback_outbox SET delivered_at=CASE WHEN $3='ACK' OR $3='OBSOLETE' THEN clock_timestamp() ELSE delivered_at END,dead_letter_at=CASE WHEN $3='PERMANENT' OR $3='EXPIRED' THEN clock_timestamp() ELSE dead_letter_at END,available_at=LEAST(clock_timestamp()+$4*INTERVAL '1 millisecond',first_attempt_at+INTERVAL '15 minutes'),last_error_code=$3,alerted_at=CASE WHEN $5 THEN COALESCE(alerted_at,clock_timestamp()) ELSE alerted_at END,claim_token=NULL,claim_until=NULL WHERE event_id=$1 AND claim_token=$2 AND claim_until>clock_timestamp()")
            .bind(callback.event_id).bind(callback.claim_token).bind(result).bind(delay_ms).bind(alert)
            .execute(&mut *tx).await?;
        if updated.rows_affected() != 1 {
            return Err(sqlx::Error::Protocol("callback claim lost".to_owned()));
        }
        tx.commit().await?;
        Ok(())
    }
    pub async fn open_dead_letters(&self) -> Result<i64, sqlx::Error> {
        sqlx::query_scalar("SELECT count(*) FROM media_callback_outbox WHERE dead_letter_at IS NOT NULL AND delivered_at IS NULL AND closed_at IS NULL")
            .fetch_one(&self.pool).await
    }
}
