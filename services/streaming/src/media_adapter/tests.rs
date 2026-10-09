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
    // Ignored SQL tests also construct HTTP clients without running application bootstrap.
    let _ = rustls::crypto::ring::default_provider().install_default();
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

struct TestServer {
    url: String,
    task: tokio::task::JoinHandle<()>,
}
impl Drop for TestServer {
    fn drop(&mut self) {
        self.task.abort();
    }
}
async fn serve(router: axum::Router) -> Result<TestServer, TestError> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let url = format!("http://{}", listener.local_addr()?);
    let task = tokio::spawn(async move {
        let _ = axum::serve(listener, router).await;
    });
    Ok(TestServer { url, task })
}

#[derive(Clone)]
struct EngineFixture {
    session: std::sync::Arc<std::sync::Mutex<serde_json::Value>>,
    path: std::sync::Arc<std::sync::Mutex<(axum::http::StatusCode, String)>>,
    hls_reads: std::sync::Arc<std::sync::atomic::AtomicUsize>,
    slow_control: std::sync::Arc<std::sync::atomic::AtomicBool>,
}
impl EngineFixture {
    fn new(publisher: Uuid) -> Self {
        Self {
            session: std::sync::Arc::new(std::sync::Mutex::new(serde_json::json!({
                "status":"LIVE", "availability":"PLAYABLE"
            }))),
            path: std::sync::Arc::new(std::sync::Mutex::new((
                axum::http::StatusCode::OK,
                serde_json::json!({
                    "ready":true,"source":{"id":publisher,"type":"rtmpConn"}
                })
                .to_string(),
            ))),
            hls_reads: std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0)),
            slow_control: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
        }
    }
    fn router(&self) -> axum::Router {
        use axum::{Json, Router, extract::State, routing::get};
        Router::new()
            .route(
                "/api/streams/sessions/{session}",
                get(async |State(f): State<Self>| {
                    Json(f.session.lock().unwrap_or_else(|e| e.into_inner()).clone())
                }),
            )
            .route(
                "/v3/paths/get/{*path}",
                get(async |State(f): State<Self>| {
                    if f.slow_control.load(std::sync::atomic::Ordering::Relaxed) {
                        tokio::time::sleep(Duration::from_secs(1)).await;
                    }
                    f.path.lock().unwrap_or_else(|e| e.into_inner()).clone()
                }),
            )
            .route(
                "/live/fixture/{file}",
                get(async |State(f): State<Self>| {
                    f.hls_reads
                        .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    "#EXTM3U\n#EXTINF:1,\nsegment.ts\n"
                }),
            )
            .with_state(self.clone())
    }
}
fn media_state(pool: PgPool, server: &TestServer) -> Result<super::MediaState, TestError> {
    Ok(super::MediaState {
        repository: MediaRepository::new(pool),
        client: reqwest::Client::builder()
            .timeout(Duration::from_millis(200))
            .build()?,
        config: super::config::MediaConfig {
            database_url: String::new(),
            db_max_connections: 4,
            run_migrations: false,
            bind_addr: "127.0.0.1:0".parse()?,
            hls_bind_addr: "127.0.0.1:0".parse()?,
            streaming_url: server.url.clone(),
            streaming_public_url: server.url.clone(),
            streaming_token: "fixture_streaming_token".into(),
            control_url: server.url.clone(),
            control_user: "fixture".into(),
            control_password: "fixture".into(),
            hls_url: server.url.clone(),
            hls_secret: "fixture".into(),
            auth_header: "fixture".into(),
            max_open_dead_letters: 10000,
        },
    })
}

#[tokio::test]
async fn rtmps_publisher_is_found_and_kicked_after_rtmp_collection_returns_404()
-> Result<(), TestError> {
    use axum::{
        Router,
        extract::State,
        http::StatusCode,
        routing::{get, post},
    };
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };
    let kicks = Arc::new(AtomicUsize::new(0));
    let engine = serve(
        Router::new()
            .route(
                "/v3/rtmp/conns/get/{id}",
                get(async || StatusCode::NOT_FOUND),
            )
            .route(
                "/v3/rtmp/conns/kick/{id}",
                post(async || StatusCode::NOT_FOUND),
            )
            .route("/v3/rtmps/conns/get/{id}", get(async || StatusCode::OK))
            .route(
                "/v3/rtmps/conns/kick/{id}",
                post(async |State(kicks): State<Arc<AtomicUsize>>| {
                    kicks.fetch_add(1, Ordering::Relaxed);
                    StatusCode::OK
                }),
            )
            .with_state(Arc::clone(&kicks)),
    )
    .await?;
    let pool = PgPoolOptions::new().connect_lazy("postgres://unused:unused@localhost/unused")?;
    let state = media_state(pool, &engine)?;
    let id = Uuid::new_v4();
    assert!(state.publisher_exists(id).await.is_ok_and(|exists| exists));
    assert!(state.kick(id).await.is_ok());
    assert_eq!(kicks.load(Ordering::Relaxed), 1);
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn public_hls_waits_for_playable_while_private_reconnect_probe_can_read()
-> Result<(), TestError> {
    use axum::http::StatusCode;
    use std::sync::{Arc, atomic::Ordering};
    let pool = database().await?;
    let publisher = Uuid::new_v4();
    let fixture = EngineFixture::new(publisher);
    let engine = serve(fixture.router()).await?;
    let state = Arc::new(media_state(pool.clone(), &engine)?);
    let authorization = IngestAuthorization {
        stream_id: StreamId::new(),
        session_id: SessionId::new(),
        stream_generation: 1,
        source_generation: 1,
    };
    let previous = Uuid::new_v4();
    state
        .repository
        .save_source(previous, "live/fixture", &[1; 32], &authorization)
        .await?;
    let old_source = state
        .repository
        .source(previous)
        .await?
        .ok_or("old source")?;
    for kind in ["source-connected", "playback-ready", "source-lost"] {
        state.repository.observe(&old_source, kind).await?;
    }
    let reconnect = IngestAuthorization {
        source_generation: 2,
        ..authorization
    };
    state
        .repository
        .save_source(publisher, "live/fixture", &[2; 32], &reconnect)
        .await?;
    let hls = serve(super::handlers::hls_router(state)).await?;
    let url = format!(
        "{}/hls/{}/index.m3u8",
        hls.url,
        reconnect.session_id.as_str()
    );
    let client = reqwest::Client::new();
    for availability in ["RECONNECTING", "OFFLINE", "UNKNOWN"] {
        *fixture.session.lock().map_err(|_| "fixture poisoned")? = serde_json::json!({
            "status":"LIVE", "availability":availability
        });
        let response = client.get(&url).send().await?;
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(
            response.json::<serde_json::Value>().await?["code"],
            "PLAYBACK_NOT_READY"
        );
    }
    assert_eq!(fixture.hls_reads.load(Ordering::Relaxed), 0);
    *fixture.session.lock().map_err(|_| "fixture poisoned")? = serde_json::json!({
        "status":"LIVE", "availability":"RECONNECTING"
    });
    assert_eq!(
        client
            .get(&url)
            .bearer_auth("fixture_streaming_token")
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    *fixture.session.lock().map_err(|_| "fixture poisoned")? = serde_json::json!({
        "status":"LIVE", "availability":"PLAYABLE"
    });
    let response = client.get(&url).send().await?;
    assert_eq!(response.status(), StatusCode::OK);
    assert!(response.text().await?.starts_with("#EXTM3U"));
    *fixture.session.lock().map_err(|_| "fixture poisoned")? = serde_json::json!({"status":"LIVE"});
    assert_eq!(
        client.get(&url).send().await?.status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    *fixture.session.lock().map_err(|_| "fixture poisoned")? = serde_json::json!({
        "status":"ENDED", "availability":"OFFLINE"
    });
    assert_eq!(client.get(&url).send().await?.status(), StatusCode::GONE);
    assert_eq!(fixture.hls_reads.load(Ordering::Relaxed), 2);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn control_api_faults_do_not_record_loss_or_retire_a_connected_publisher()
-> Result<(), TestError> {
    use axum::http::StatusCode;
    use std::sync::atomic::Ordering;
    let pool = database().await?;
    let publisher = Uuid::new_v4();
    let fixture = EngineFixture::new(publisher);
    let engine = serve(fixture.router()).await?;
    let state = media_state(pool.clone(), &engine)?;
    state
        .repository
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
    let source = state.repository.source(publisher).await?.ok_or("source")?;
    state
        .repository
        .observe(&source, "source-connected")
        .await?;
    state.repository.observe(&source, "playback-ready").await?;
    let healthy = fixture.path.lock().map_err(|_| "fixture poisoned")?.clone();
    for response in [
        (StatusCode::SERVICE_UNAVAILABLE, String::new()),
        (StatusCode::OK, "invalid JSON".into()),
    ] {
        *fixture.path.lock().map_err(|_| "fixture poisoned")? = response;
        for _ in 0..2 {
            assert!(super::workers::observe_sources(&state).await.is_err());
        }
    }
    *fixture.path.lock().map_err(|_| "fixture poisoned")? = healthy;
    fixture.slow_control.store(true, Ordering::Relaxed);
    assert!(super::workers::observe_sources(&state).await.is_err());
    fixture.slow_control.store(false, Ordering::Relaxed);
    assert!(super::workers::observe_sources(&state).await.is_ok());
    let source = state
        .repository
        .source(publisher)
        .await?
        .ok_or("source after fault")?;
    assert!(source.lost_at.is_none() && source.retired_at.is_none());
    let losses: i64 =
        sqlx::query_scalar("SELECT count(*) FROM media_callback_outbox WHERE kind='source-lost'")
            .fetch_one(&pool)
            .await?;
    assert_eq!(losses, 0);
    *fixture.path.lock().map_err(|_| "fixture poisoned")? = (StatusCode::NOT_FOUND, String::new());
    assert!(super::workers::observe_sources(&state).await.is_ok());
    assert!(
        state
            .repository
            .source(publisher)
            .await?
            .ok_or("lost source")?
            .lost_at
            .is_some()
    );
    let losses: i64 =
        sqlx::query_scalar("SELECT count(*) FROM media_callback_outbox WHERE kind='source-lost'")
            .fetch_one(&pool)
            .await?;
    assert_eq!(losses, 1);
    pool.close().await;
    Ok(())
}
