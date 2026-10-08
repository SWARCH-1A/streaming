use serde_json::json;
use sqlx::{Connection, PgConnection, PgPool};
use uuid::Uuid;

/// A dedicated connection retains the session lock for this process lifetime.
/// Dropping it closes the connection rather than returning a locked session to a pool.
pub async fn acquire(database_url: &str) -> Result<Option<PgConnection>, sqlx::Error> {
    let mut connection = PgConnection::connect(database_url).await?;
    let owned: bool = sqlx::query_scalar("SELECT pg_try_advisory_lock(60404,1)")
        .fetch_one(&mut connection)
        .await?;
    if owned {
        Ok(Some(connection))
    } else {
        connection.close().await?;
        Ok(None)
    }
}

/// Must only run after acquiring the exclusive control lock. A predecessor's
/// process has gone; its monotonic anchors cannot be reconstructed from UTC.
pub async fn terminate_unverifiable_sessions(pool: &PgPool) -> Result<u64, sqlx::Error> {
    let mut tx = pool.begin().await?;
    sqlx::query("SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE")
        .execute(&mut *tx)
        .await?;
    let sessions=sqlx::query_as::<_,(String,String,i64,i64)>("UPDATE stream_sessions SET status='ENDED',availability='OFFLINE',session_version=session_version+1,owner_lease_expires_at=NULL,grace_deadline_at=NULL,ended_at=clock_timestamp(),timeline_sampled_at=clock_timestamp(),viewer_count=0,count_version=count_version+1,viewer_count_observed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE status<>'ENDED' RETURNING session_id,stream_id,stream_generation,session_version")
        .fetch_all(&mut *tx).await?;
    for (session, stream, generation, version) in &sessions {
        sqlx::query("UPDATE viewer_leases SET closed_at=clock_timestamp() WHERE session_id=$1 AND closed_at IS NULL")
            .bind(session).execute(&mut *tx).await?;
        sqlx::query("INSERT INTO streaming_outbox(event_id,aggregate_id,sequence,event_type,payload) VALUES($1,$2,$3,'StreamSessionEnded',$4)")
            .bind(Uuid::now_v7()).bind(format!("session:{session}")).bind(version)
            .bind(json!({"streamId":stream,"sessionId":session,"streamGeneration":generation,"sessionVersion":version,"status":"ENDED","availability":"OFFLINE"}))
            .execute(&mut *tx).await?;
    }
    if !sessions.is_empty() {
        sqlx::query("UPDATE streaming_capacity_lock SET revision=revision+1 WHERE singleton")
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    Ok(sessions.len() as u64)
}
