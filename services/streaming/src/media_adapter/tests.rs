use super::repository::MediaRepository;
use crate::{
    application::ports::ingest_authorization::IngestAuthorization,
    domain::ids::{SessionId, StreamId},
};
use sqlx::{PgPool, postgres::PgPoolOptions};
use std::{error::Error, time::Duration};
use uuid::Uuid;
type TestError = Box<dyn Error + Send + Sync>;
async fn database() -> Result<PgPool, TestError> {
    let url = std::env::var("STREAMING_TEST_DATABASE_URL")?;
    let admin = PgPoolOptions::new()
        .max_connections(1)
        .connect(&url)
        .await?;
    let schema = format!("media_test_{}", Uuid::new_v4().simple());
    // The identifier consists only of a fixed prefix and generated UUID hex.
    sqlx::query(sqlx::AssertSqlSafe(format!("CREATE SCHEMA {schema}")))
        .execute(&admin)
        .await?;
    admin.close().await;
    let pool = PgPoolOptions::new()
        .max_connections(4)
        .after_connect(move |connection, _| {
            let path = format!("{schema},pg_catalog");
            Box::pin(async move {
                sqlx::query("SELECT set_config('search_path',$1,false)")
                    .bind(path)
                    .execute(connection)
                    .await
                    .map(|_| ())
            })
        })
        .connect(&url)
        .await?;
    sqlx::migrate!("./media-adapter/migrations")
        .run(&pool)
        .await?;
    Ok(pool)
}
#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn durable_media_observations_dedupe_and_do_not_revive_a_lost_source() -> Result<(), TestError>
{
    let pool = database().await?;
    let repository = MediaRepository::new(pool.clone());
    let publisher = Uuid::new_v4();
    let authorization = IngestAuthorization {
        stream_id: StreamId::new(),
        session_id: SessionId::new(),
        stream_generation: 1,
        source_generation: 1,
    };
    repository
        .save_source(publisher, "live/fixture", &[1; 32], &authorization)
        .await?;
    let source = repository.source(publisher).await?.ok_or("source")?;
    repository.observe(&source, "source-connected").await?;
    repository.observe(&source, "source-connected").await?;
    repository.observe(&source, "playback-ready").await?;
    repository.observe(&source, "source-lost").await?;
    assert!(repository.alert_once(publisher).await?);
    assert!(!repository.alert_once(publisher).await?);
    for kind in ["source-connected", "playback-ready", "source-lost"] {
        let event = repository.claim().await?.ok_or("callback")?;
        assert_eq!(event.kind, kind);
        repository.finish(&event, "ACK", 0, false).await?;
    }
    assert!(repository.claim().await?.is_none());
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM media_callback_outbox")
        .fetch_one(&pool)
        .await?;
    assert_eq!(count, 3);
    let second = Uuid::new_v4();
    let next = IngestAuthorization {
        source_generation: 2,
        ..authorization
    };
    repository
        .save_source(second, "live/fixture", &[2; 32], &next)
        .await?;
    let source = repository.source(second).await?.ok_or("second source")?;
    repository.observe(&source, "source-connected").await?;
    repository.observe(&source, "source-lost").await?;
    repository.observe(&source, "playback-ready").await?;
    let ready:i64=sqlx::query_scalar("SELECT count(*) FROM media_callback_outbox WHERE publisher_id=$1 AND kind='playback-ready'").bind(second).fetch_one(&pool).await?;
    assert_eq!(ready, 0);
    pool.close().await;
    Ok(())
}
#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn expired_media_claim_cannot_ack_after_waiting_for_a_row_lock() -> Result<(), TestError> {
    let pool = database().await?;
    let repository = std::sync::Arc::new(MediaRepository::new(pool.clone()));
    let publisher = Uuid::new_v4();
    let authorization = IngestAuthorization {
        stream_id: StreamId::new(),
        session_id: SessionId::new(),
        stream_generation: 1,
        source_generation: 1,
    };
    repository
        .save_source(publisher, "live/fixture", &[1; 32], &authorization)
        .await?;
    let source = repository.source(publisher).await?.ok_or("source")?;
    repository.observe(&source, "source-connected").await?;
    let callback = repository.claim().await?.ok_or("callback")?;
    sqlx::query("UPDATE media_callback_outbox SET claim_until=clock_timestamp()+INTERVAL '100 milliseconds' WHERE event_id=$1").bind(callback.event_id).execute(&pool).await?;
    let mut lock = pool.begin().await?;
    sqlx::query("SELECT event_id FROM media_callback_outbox WHERE event_id=$1 FOR UPDATE")
        .bind(callback.event_id)
        .execute(&mut *lock)
        .await?;
    let worker = repository.clone();
    let task = tokio::spawn(async move { worker.finish(&callback, "ACK", 0, false).await });
    tokio::time::sleep(Duration::from_millis(150)).await;
    lock.commit().await?;
    assert!(task.await?.is_err());
    let delivered: bool =
        sqlx::query_scalar("SELECT delivered_at IS NOT NULL FROM media_callback_outbox")
            .fetch_one(&pool)
            .await?;
    assert!(!delivered);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn media_dead_letter_redrive_preserves_event_and_manual_close_does_not_ack()
-> Result<(), TestError> {
    let pool = database().await?;
    let repository = MediaRepository::new(pool.clone());
    let publisher = Uuid::new_v4();
    repository
        .save_source(
            publisher,
            "live/fixture",
            &[1; 32],
            &IngestAuthorization {
                stream_id: StreamId::new(),
                session_id: SessionId::new(),
                stream_generation: 1,
                source_generation: 1,
            },
        )
        .await?;
    let source = repository.source(publisher).await?.ok_or("source")?;
    repository.observe(&source, "source-connected").await?;
    let original = repository.claim().await?.ok_or("callback")?;
    repository.finish(&original, "PERMANENT", 0, true).await?;
    assert!(repository.claim().await?.is_none());
    super::operator::execute(
        &pool,
        &[
            "redrive".into(),
            original.event_id.to_string(),
            "operator_fixture".into(),
            "Receiver repaired".into(),
        ],
    )
    .await?;
    let retry = repository.claim().await?.ok_or("redrive")?;
    assert_eq!(retry.event_id, original.event_id);
    assert_eq!(retry.payload, original.payload);
    assert_eq!(retry.attempts, 1);
    repository.finish(&retry, "PERMANENT", 0, true).await?;
    super::operator::execute(
        &pool,
        &[
            "close".into(),
            original.event_id.to_string(),
            "operator_fixture".into(),
            "Confirmed obsolete".into(),
        ],
    )
    .await?;
    let result: (bool, bool, i64) = sqlx::query_as("SELECT delivered_at IS NOT NULL,closed_at IS NOT NULL,(SELECT count(*) FROM media_callback_operations WHERE event_id=$1) FROM media_callback_outbox WHERE event_id=$1")
        .bind(original.event_id).fetch_one(&pool).await?;
    assert_eq!(result, (false, true, 2));
    assert!(repository.claim().await?.is_none());
    pool.close().await;
    Ok(())
}
