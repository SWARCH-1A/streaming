//! Streaming-owned recording snapshots. No VOD process reads this database.
use serde_json::{Value, json};
use sqlx::{PgPool, Row};
use time::format_description::well_known::Rfc3339;

pub async fn snapshots(pool: &PgPool) -> Result<Value, sqlx::Error> {
    let rows = sqlx::query("SELECT r.start_snapshot,r.initial_source_generation,s.source_generation,s.status,s.availability,s.started_at,s.timeline_position_ms FROM recording_sessions r JOIN stream_sessions s USING(session_id) WHERE NOT r.closed ORDER BY s.created_at LIMIT 100")
        .fetch_all(pool).await?;
    let mut items = Vec::new();
    for row in rows {
        let start: Value = row.try_get("start_snapshot")?;
        let started: Option<time::OffsetDateTime> = row.try_get("started_at")?;
        items.push(json!({"start":start,"initialSourceGeneration":row.try_get::<i64,_>("initial_source_generation")?,"sourceGeneration":row.try_get::<i64,_>("source_generation")?,
            "status":row.try_get::<String,_>("status")?,"availability":row.try_get::<String,_>("availability")?,
            "startedAtUtc":started.and_then(|t|t.format(&Rfc3339).ok()),"timelinePositionMs":row.try_get::<i64,_>("timeline_position_ms")?}));
    }
    Ok(json!({"items":items}))
}
pub async fn acknowledge(pool: &PgPool, session: &str) -> Result<(), sqlx::Error> {
    sqlx::query("UPDATE recording_sessions r SET closed=TRUE FROM stream_sessions s WHERE r.session_id=$1 AND s.session_id=r.session_id AND s.status='ENDED'")
        .bind(session).execute(pool).await?;
    Ok(())
}
