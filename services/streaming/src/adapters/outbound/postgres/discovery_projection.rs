use crate::application::ports::discovery_projection::{
    DiscoveryProjectionRepository, ProjectionError,
};
use serde_json::{Value, json};
use sqlx::{FromRow, PgPool};
use std::future::Future;
use time::{OffsetDateTime, format_description::well_known::Rfc3339};
use uuid::Uuid;

pub struct PostgresDiscoveryProjectionRepository {
    pool: PgPool,
}
impl PostgresDiscoveryProjectionRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}
#[derive(FromRow)]
struct Cut {
    snapshot_id: Uuid,
    watermark: i64,
    captured_at: OffsetDateTime,
    expires_at: OffsetDateTime,
    page_limit: i32,
}

impl DiscoveryProjectionRepository for PostgresDiscoveryProjectionRepository {
    fn refresh_due(&self) -> impl Future<Output = Result<(), ProjectionError>> + Send {
        async move {
            loop {
                let mut tx = self.pool.begin().await.map_err(unavailable)?;
                sqlx::query(
                    "SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE",
                )
                .execute(&mut *tx)
                .await
                .map_err(unavailable)?;
                let ids = sqlx::query_scalar::<_, String>("SELECT stream_id FROM streaming_discovery_state WHERE observed_at<=clock_timestamp()-INTERVAL '1 second' ORDER BY observed_at,stream_id LIMIT 100")
                .fetch_all(&mut *tx).await.map_err(unavailable)?;
                let count = ids.len();
                for id in ids {
                    sqlx::query("SELECT publish_stream_discovery($1)")
                        .bind(id)
                        .execute(&mut *tx)
                        .await
                        .map_err(unavailable)?;
                }
                tx.commit().await.map_err(unavailable)?;
                if count < 100 {
                    break;
                }
            }
            // Expired cuts are retained for a day so retries get an explicit expiry.
            sqlx::query("DELETE FROM streaming_discovery_cuts WHERE expires_at<clock_timestamp()-INTERVAL '1 day'").execute(&self.pool).await.map_err(unavailable)?;
            Ok(())
        }
    }
    fn page(
        &self,
        limit: i32,
        cursor: Option<String>,
    ) -> impl Future<Output = Result<Value, ProjectionError>> + Send {
        async move {
            if !(1..=50).contains(&limit) {
                return Err(ProjectionError::InvalidRequest);
            }
            let mut tx = self.pool.begin().await.map_err(unavailable)?;
            sqlx::query("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                .execute(&mut *tx)
                .await
                .map_err(unavailable)?;
            let (cut, offset) = if let Some(cursor) = cursor {
                let token =
                    Uuid::parse_str(&cursor).map_err(|_| ProjectionError::InvalidRequest)?;
                let position = sqlx::query_as::<_, (Uuid,i64,i32)>("SELECT snapshot_id,ordinal,page_limit FROM streaming_discovery_cursors WHERE token=$1")
                    .bind(token).fetch_optional(&mut *tx).await.map_err(unavailable)?.ok_or(ProjectionError::Expired)?;
                if position.2 != limit {
                    return Err(ProjectionError::InvalidRequest);
                }
                let cut = sqlx::query_as::<_, Cut>("SELECT snapshot_id,watermark,captured_at,expires_at,page_limit FROM streaming_discovery_cuts WHERE snapshot_id=$1")
                    .bind(position.0).fetch_one(&mut *tx).await.map_err(unavailable)?;
                (cut, position.1)
            } else {
                let id = Uuid::new_v4();
                let cut = sqlx::query_as::<_, Cut>("INSERT INTO streaming_discovery_cuts(snapshot_id,watermark,captured_at,expires_at,page_limit) SELECT $1,position,clock_timestamp(),clock_timestamp()+INTERVAL '5 minutes',$2 FROM discovery_commit_position WHERE singleton RETURNING snapshot_id,watermark,captured_at,expires_at,page_limit")
                    .bind(id).bind(limit).fetch_one(&mut *tx).await.map_err(unavailable)?;
                sqlx::query("INSERT INTO streaming_discovery_cut_items(snapshot_id,ordinal,payload) SELECT $1,row_number() OVER (ORDER BY stream_id)-1,payload FROM streaming_discovery_state")
                    .bind(id).execute(&mut *tx).await.map_err(unavailable)?;
                (cut, 0)
            };
            let now: OffsetDateTime = sqlx::query_scalar("SELECT clock_timestamp()")
                .fetch_one(&mut *tx)
                .await
                .map_err(unavailable)?;
            if cut.expires_at <= now {
                return Err(ProjectionError::Expired);
            }
            if cut.page_limit != limit {
                return Err(ProjectionError::InvalidRequest);
            }
            let mut items = sqlx::query_scalar::<_, Value>("SELECT payload FROM streaming_discovery_cut_items WHERE snapshot_id=$1 AND ordinal>=$2 ORDER BY ordinal LIMIT $3")
                .bind(cut.snapshot_id).bind(offset).bind(i64::from(limit)+1).fetch_all(&mut *tx).await.map_err(unavailable)?;
            let next = if items.len() > limit as usize {
                items.pop();
                let token:Uuid = sqlx::query_scalar("INSERT INTO streaming_discovery_cursors(token,snapshot_id,ordinal,page_limit) VALUES($1,$2,$3,$4) ON CONFLICT(snapshot_id,ordinal,page_limit) DO UPDATE SET ordinal=EXCLUDED.ordinal RETURNING token")
                    .bind(Uuid::new_v4()).bind(cut.snapshot_id).bind(offset+i64::from(limit)).bind(limit).fetch_one(&mut *tx).await.map_err(unavailable)?;
                Some(token.to_string())
            } else {
                None
            };
            tx.commit().await.map_err(unavailable)?;
            Ok(
                json!({"snapshotId":cut.snapshot_id,"watermark":cut.watermark,
                "capturedAtUtc":cut.captured_at.format(&Rfc3339).map_err(|_| ProjectionError::Unavailable)?,
                "expiresAtUtc":cut.expires_at.format(&Rfc3339).map_err(|_| ProjectionError::Unavailable)?,
                "items":items,"nextCursor":next}),
            )
        }
    }
}
fn unavailable(_: sqlx::Error) -> ProjectionError {
    ProjectionError::Unavailable
}
