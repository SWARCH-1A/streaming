use std::{error::Error, sync::Arc, time::Duration};

use serde_json::json;
use sqlx::{PgPool, postgres::PgPoolOptions};
use streaming_service::{
    adapters::outbound::{
        media_node_assignment::ConfiguredMediaNodeAssignment,
        monotonic_clock::MonotonicSessionClock, postgres::*,
    },
    application::ports::{
        domain_event_outbox::{DomainEventOutboxError, DomainEventOutboxRepository},
        ingest_authorization::{
            AuthorizeIngestCommand, IngestAuthorization, IngestAuthorizationError,
            IngestAuthorizationRepository,
        },
        media_callbacks::{
            AcceptMediaCallbackCommand, MediaCallbackKind, MediaCallbackProcessingRepository,
            MediaCallbackRepository,
        },
        media_server::PlaybackEvidence,
        session_clock::SessionMonotonicClock,
        session_timeline::SessionTimelineRepository,
        stream_config::{
            CreateStreamConfigCommand, PatchStreamMetadataCommand, StreamConfigRepository,
        },
        streaming_repository::StreamingRepository,
        viewer_count_snapshots::ViewerCountSnapshotRepository,
        viewer_leases::{ViewerLeaseRepository, ViewerLeaseRepositoryError},
    },
    domain::ids::{SessionId, StreamId},
};
use uuid::Uuid;

type TestError = Box<dyn Error>;
fn debug_error(error: impl std::fmt::Debug) -> TestError {
    std::io::Error::other(format!("{error:?}")).into()
}

async fn database() -> Result<PgPool, TestError> {
    let url = std::env::var("STREAMING_TEST_DATABASE_URL")?;
    let setup = PgPool::connect(&url).await?;
    let schema = format!("streaming_test_{}", Uuid::new_v4().simple());
    // The identifier contains only a fixed prefix and UUID hexadecimal digits.
    sqlx::query(sqlx::AssertSqlSafe(format!("CREATE SCHEMA {schema}")))
        .execute(&setup)
        .await?;
    setup.close().await;
    let pool = PgPoolOptions::new()
        .max_connections(8)
        .after_connect(move |connection, _| {
            let search_path = format!("{schema}, pg_catalog");
            Box::pin(async move {
                sqlx::query("SELECT set_config('search_path', $1, false)")
                    .bind(search_path)
                    .execute(connection)
                    .await
                    .map(|_| ())
            })
        })
        .connect(&url)
        .await?;
    sqlx::migrate!("./migrations").run(&pool).await?;
    Ok(pool)
}
async fn config(pool: &PgPool, seed: u8) -> Result<(), TestError> {
    PostgresStreamConfigRepository::new(pool.clone())
        .create_if_absent(CreateStreamConfigCommand {
            authorization_expires_at: None,
            catalog_labels: vec![
                streaming_service::application::ports::taxonomy::TaxonomyValue {
                    id: "category_example".into(),
                    name: "Category".into(),
                    active: true,
                },
            ],
            stream_id: StreamId::new(),
            channel_id: format!("channel_{seed}"),
            owner_user_id: format!("user_{seed}"),
            title: "Emisión".into(),
            category_id: "category_example".into(),
            tag_ids: vec![],
            ingest_key_hash: [seed; 32],
            idempotency_key: Uuid::new_v4(),
            request_fingerprint: [seed; 32],
        })
        .await
        .map_err(debug_error)?;
    Ok(())
}
fn authorization(seed: u8) -> AuthorizeIngestCommand {
    AuthorizeIngestCommand {
        expected_stream_id: None,
        ingest_attempt_id: Uuid::new_v4(),
        stream_key_hash: [seed; 32],
        request_fingerprint: [seed; 32],
        candidate_session_id: SessionId::new(),
        owner_instance_id: "test_owner".into(),
    }
}
fn authorizer(pool: &PgPool) -> PostgresIngestAuthorizationRepository {
    PostgresIngestAuthorizationRepository::new(
        pool.clone(),
        Arc::new(ConfiguredMediaNodeAssignment::new(None)),
    )
}
async fn playable(
    pool: &PgPool,
    clock: &MonotonicSessionClock,
) -> Result<IngestAuthorization, TestError> {
    config(pool, 1).await?;
    let ingest = authorizer(pool)
        .authorize(authorization(1), clock)
        .await?
        .authorization;
    clock.start_preparing(&ingest.session_id);
    let path = format!("/hls/{}/index.m3u8", ingest.session_id.as_str());
    let callbacks = PostgresMediaCallbackRepository::new(pool.clone());
    callbacks
        .accept(AcceptMediaCallbackCommand {
            event_id: Uuid::new_v4(),
            kind: MediaCallbackKind::PlaybackReady,
            stream_id: ingest.stream_id.clone(),
            session_id: ingest.session_id.clone(),
            stream_generation: 1,
            source_generation: 1,
            payload_hash: [1; 32],
            payload: json!({"playbackPath": path}),
        })
        .await
        .map_err(debug_error)?;
    let claim = callbacks
        .claim_next("test_owner".into())
        .await?
        .ok_or("callback no reclamado")?;
    callbacks
        .apply(
            claim,
            "test_owner".into(),
            Some(PlaybackEvidence {
                manifest_path: path,
                has_reproducible_segment: true,
            }),
            clock,
        )
        .await?;
    Ok(ingest)
}
async fn snapshot(pool: &PgPool, id: &SessionId) -> Result<(i64, i64, i64), TestError> {
    Ok(sqlx::query_as("SELECT viewer_count,count_version,session_version FROM stream_sessions WHERE session_id=$1").bind(id.as_str()).fetch_one(pool).await?)
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn migration_snapshots_timeline_and_metadata_remain_local_and_atomic() -> Result<(), TestError>
{
    let pool = database().await?;
    let clock = MonotonicSessionClock::default();
    let ingest = playable(&pool, &clock).await?;
    assert_eq!(snapshot(&pool, &ingest.session_id).await?, (0, 1, 2));
    let leases = PostgresViewerLeaseRepository::new(pool.clone());
    let key = Uuid::new_v4();
    leases
        .create(
            ingest.session_id.clone(),
            key,
            "lease_example".into(),
            [9; 32],
        )
        .await
        .map_err(debug_error)?;
    assert_eq!(snapshot(&pool, &ingest.session_id).await?, (0, 1, 2));
    sqlx::query("UPDATE stream_sessions SET viewer_count_observed_at=clock_timestamp()-interval '2 seconds',timeline_sampled_at=clock_timestamp()-interval '2 seconds' WHERE session_id=$1").bind(ingest.session_id.as_str()).execute(&pool).await?;
    PostgresViewerCountSnapshotRepository::new(pool.clone())
        .refresh_due_snapshots()
        .await
        .map_err(debug_error)?;
    assert_eq!(snapshot(&pool, &ingest.session_id).await?, (1, 2, 2));
    PostgresSessionTimelineRepository::new(pool.clone())
        .checkpoint_due_samples("test_owner".into(), &clock)
        .await?;
    assert_eq!(snapshot(&pool, &ingest.session_id).await?, (1, 2, 2));
    let configs = PostgresStreamConfigRepository::new(pool.clone());
    let patched = configs
        .patch_metadata(PatchStreamMetadataCommand {
            authorization_expires_at: None,
            category_label: None,
            tag_labels: None,
            stream_id: ingest.stream_id.clone(),
            title: Some("Título vigente".into()),
            category_id: None,
            tag_ids: None,
        })
        .await
        .map_err(debug_error)?;
    assert_eq!(patched.config.metadata_version, 2);
    assert_eq!(patched.config.category_id, "category_example");
    assert_eq!(
        configs
            .patch_metadata(PatchStreamMetadataCommand {
                authorization_expires_at: None,
                category_label: None,
                tag_labels: None,
                stream_id: ingest.stream_id.clone(),
                title: Some("Título vigente".into()),
                category_id: None,
                tag_ids: None
            })
            .await
            .map_err(debug_error)?
            .config
            .metadata_version,
        2
    );
    leases
        .close("lease_example".into(), [9; 32])
        .await
        .map_err(debug_error)?;
    assert_eq!(snapshot(&pool, &ingest.session_id).await?, (1, 2, 2));
    assert_eq!(
        leases
            .create(
                ingest.session_id.clone(),
                key,
                "lease_example".into(),
                [9; 32]
            )
            .await
            .err(),
        Some(ViewerLeaseRepositoryError::Expired)
    );
    PostgresStreamingRepository::new(pool.clone())
        .stop_session(ingest.session_id.clone(), Some(29_000), None)
        .await
        .map_err(debug_error)?;
    assert_eq!(snapshot(&pool, &ingest.session_id).await?, (0, 3, 3));
    let kinds: Vec<String> = sqlx::query_scalar(
        "SELECT event_type FROM streaming_outbox WHERE consumer='chat' ORDER BY sequence",
    )
    .fetch_all(&pool)
    .await?;
    assert_eq!(
        kinds,
        [
            "StreamSessionPreparing",
            "StreamSessionStarted",
            "StreamSessionEnded"
        ]
    );
    let migrations: i64 = sqlx::query_scalar("SELECT count(*) FROM _sqlx_migrations")
        .fetch_one(&pool)
        .await?;
    assert_eq!(migrations, 1);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn concurrent_authorizations_never_reserve_more_than_five_slots() -> Result<(), TestError> {
    let pool = database().await?;
    for seed in 1..=6 {
        config(&pool, seed).await?;
    }
    let clock = MonotonicSessionClock::default();
    let authorizer = authorizer(&pool);
    let results = futures_util::future::join_all(
        (1..=6).map(|seed| authorizer.authorize(authorization(seed), &clock)),
    )
    .await;
    assert_eq!(results.iter().filter(|result| result.is_ok()).count(), 5);
    assert_eq!(
        results
            .iter()
            .filter(|result| matches!(result, Err(IngestAuthorizationError::LiveSessionLimit)))
            .count(),
        1
    );
    let slots: i64 =
        sqlx::query_scalar("SELECT count(*) FROM stream_sessions WHERE status<>'ENDED'")
            .fetch_one(&pool)
            .await?;
    assert_eq!(slots, 5);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn an_outbox_claim_that_expires_during_a_lock_wait_cannot_acknowledge()
-> Result<(), TestError> {
    let pool = database().await?;
    let clock = MonotonicSessionClock::default();
    playable(&pool, &clock).await?;
    let outbox = PostgresDomainEventOutboxRepository::new(pool.clone());
    let claim = outbox
        .claim_next("relay_test".into(), Duration::from_secs(5))
        .await?
        .ok_or("outbox vacío")?;
    sqlx::query("UPDATE streaming_outbox SET claim_expires_at=clock_timestamp()+interval '500 milliseconds' WHERE event_id=$1").bind(claim.envelope.event_id).execute(&pool).await?;
    let mut blocker = pool.begin().await?;
    sqlx::query("SELECT event_id FROM streaming_outbox WHERE event_id=$1 FOR UPDATE")
        .bind(claim.envelope.event_id)
        .fetch_one(&mut *blocker)
        .await?;
    let acknowledge = outbox.acknowledge(
        claim.envelope.event_id,
        "relay_test".into(),
        claim.claim_token,
    );
    let release = async {
        sqlx::query("SELECT pg_sleep(1)")
            .execute(&mut *blocker)
            .await?;
        blocker.commit().await
    };
    let (result, released) = tokio::join!(acknowledge, release);
    released?;
    assert_eq!(result, Err(DomainEventOutboxError::LeaseLost));
    assert!(
        outbox
            .claim_next("relay_test".into(), Duration::from_secs(5))
            .await?
            .is_some()
    );
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn reconnect_preserves_session_version_but_never_revives_an_expired_owner()
-> Result<(), TestError> {
    let pool = database().await?;
    let clock = MonotonicSessionClock::default();
    let ingest = playable(&pool, &clock).await?;
    let callbacks = PostgresMediaCallbackRepository::new(pool.clone());
    callbacks
        .accept(AcceptMediaCallbackCommand {
            event_id: Uuid::new_v4(),
            kind: MediaCallbackKind::SourceLost,
            stream_id: ingest.stream_id.clone(),
            session_id: ingest.session_id.clone(),
            stream_generation: 1,
            source_generation: 1,
            payload_hash: [2; 32],
            payload: json!({}),
        })
        .await
        .map_err(debug_error)?;
    let claim = callbacks
        .claim_next("test_owner".into())
        .await?
        .ok_or("source-lost no reclamado")?;
    callbacks
        .apply(claim, "test_owner".into(), None, &clock)
        .await?;
    let deadline: time::OffsetDateTime =
        sqlx::query_scalar("SELECT grace_deadline_at FROM stream_sessions WHERE session_id=$1")
            .bind(ingest.session_id.as_str())
            .fetch_one(&pool)
            .await?;
    let repo = authorizer(&pool);
    let (first, second) = tokio::join!(
        repo.authorize(authorization(1), &clock),
        repo.authorize(authorization(1), &clock)
    );
    let resumed = match (first, second) {
        (Ok(winner), Err(IngestAuthorizationError::ChannelAlreadyActive))
        | (Err(IngestAuthorizationError::ChannelAlreadyActive), Ok(winner)) => winner,
        _ => return Err("reconnect must accept exactly one source".into()),
    };
    assert_eq!(resumed.authorization.session_id, ingest.session_id);
    assert_eq!(resumed.authorization.stream_generation, 1);
    assert_eq!(resumed.authorization.source_generation, 2);
    assert_eq!(snapshot(&pool, &ingest.session_id).await?.2, 3);
    callbacks
        .accept(AcceptMediaCallbackCommand {
            event_id: Uuid::new_v4(),
            kind: MediaCallbackKind::SourceLost,
            stream_id: ingest.stream_id.clone(),
            session_id: ingest.session_id.clone(),
            stream_generation: 1,
            source_generation: 2,
            payload_hash: [3; 32],
            payload: json!({}),
        })
        .await
        .map_err(debug_error)?;
    let lost = callbacks
        .claim_next("test_owner".into())
        .await?
        .ok_or("reconnect loss")?;
    callbacks
        .apply(lost, "test_owner".into(), None, &clock)
        .await?;
    let ready = callbacks.accept(AcceptMediaCallbackCommand {
        event_id: Uuid::new_v4(), kind: MediaCallbackKind::PlaybackReady,
        stream_id: ingest.stream_id.clone(), session_id: ingest.session_id.clone(),
        stream_generation: 1, source_generation: 2, payload_hash: [4; 32],
        payload: json!({"playbackPath":format!("/hls/{}/index.m3u8",ingest.session_id.as_str())}),
    }).await.map_err(debug_error)?;
    assert_eq!(ready, streaming_service::application::ports::media_callbacks::MediaCallbackReceipt::StaleGeneration);
    let retry = repo.authorize(authorization(1), &clock).await?;
    assert_eq!(retry.authorization.source_generation, 3);
    let unchanged: time::OffsetDateTime =
        sqlx::query_scalar("SELECT grace_deadline_at FROM stream_sessions WHERE session_id=$1")
            .bind(ingest.session_id.as_str())
            .fetch_one(&pool)
            .await?;
    assert_eq!(unchanged, deadline);
    assert_eq!(snapshot(&pool, &ingest.session_id).await?.2, 3);
    sqlx::query("UPDATE stream_sessions SET owner_lease_expires_at=clock_timestamp()-interval '1 second' WHERE session_id=$1")
        .bind(ingest.session_id.as_str()).execute(&pool).await?;
    let next = authorizer(&pool)
        .authorize(authorization(1), &clock)
        .await?;
    assert_ne!(next.authorization.session_id, ingest.session_id);
    assert_eq!(next.authorization.stream_generation, 2);
    assert_eq!(
        next.sessions_ended.as_slice(),
        std::slice::from_ref(&ingest.session_id)
    );
    let status: String =
        sqlx::query_scalar("SELECT status FROM stream_sessions WHERE session_id=$1")
            .bind(ingest.session_id.as_str())
            .fetch_one(&pool)
            .await?;
    assert_eq!(status, "ENDED");
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn concurrent_sources_for_the_same_channel_get_only_one_session() -> Result<(), TestError> {
    let pool = database().await?;
    config(&pool, 1).await?;
    let clock = MonotonicSessionClock::default();
    let initial_command = authorization(1);
    let mut replay_command = authorization(1);
    replay_command.ingest_attempt_id = initial_command.ingest_attempt_id;
    let ingest = authorizer(&pool).authorize(initial_command, &clock).await?;
    clock.start_preparing(&ingest.authorization.session_id);
    let replay = authorizer(&pool).authorize(replay_command, &clock).await?;
    assert_eq!(
        replay.authorization.session_id,
        ingest.authorization.session_id
    );
    let authorizer = authorizer(&pool);
    let results = futures_util::future::join_all(
        (0..5).map(|_| authorizer.authorize(authorization(1), &clock)),
    )
    .await;
    assert!(
        results
            .iter()
            .all(|result| matches!(result, Err(IngestAuthorizationError::ChannelAlreadyActive)))
    );
    let slots: i64 =
        sqlx::query_scalar("SELECT count(*) FROM stream_sessions WHERE status<>'ENDED'")
            .fetch_one(&pool)
            .await?;
    assert_eq!(slots, 1);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn discovery_cut_is_stable_paged_and_expires_explicitly() -> Result<(), TestError> {
    use streaming_service::application::ports::discovery_projection::{
        DiscoveryProjectionRepository, ProjectionError,
    };
    let pool = database().await?;
    config(&pool, 1).await?;
    config(&pool, 2).await?;
    let repository = PostgresDiscoveryProjectionRepository::new(pool.clone());
    let first = repository.page(1, None).await.map_err(debug_error)?;
    assert_eq!(first["watermark"], 2);
    let cursor = first["nextCursor"]
        .as_str()
        .ok_or_else(|| debug_error("missing cursor"))?
        .to_owned();
    let items = first["items"]
        .as_array()
        .ok_or_else(|| debug_error("missing items"))?;
    assert_eq!(items.len(), 1);
    assert_eq!(items[0]["status"], "OFFLINE");
    assert_eq!(items[0]["viewerCount"], 0);
    assert_eq!(items[0]["category"]["name"], "Category");
    config(&pool, 3).await?;
    repository.refresh_due().await.map_err(debug_error)?;
    let next = repository
        .page(1, Some(cursor.clone()))
        .await
        .map_err(debug_error)?;
    assert_eq!(next["snapshotId"], first["snapshotId"]);
    assert_eq!(next["watermark"], first["watermark"]);
    assert_eq!(next["items"].as_array().map(Vec::len), Some(1));
    assert!(next["nextCursor"].is_null());
    let replay = repository
        .page(1, Some(cursor.clone()))
        .await
        .map_err(debug_error)?;
    assert_eq!(replay, next);
    assert!(matches!(
        repository.page(2, Some(cursor.clone())).await,
        Err(ProjectionError::InvalidRequest)
    ));
    sqlx::query(
        "UPDATE streaming_discovery_cuts SET expires_at=clock_timestamp()-INTERVAL '1 second'",
    )
    .execute(&pool)
    .await?;
    assert!(matches!(
        repository.page(1, Some(cursor)).await,
        Err(ProjectionError::Expired)
    ));
    let leaked:i64=sqlx::query_scalar("SELECT count(*) FROM streaming_discovery_state WHERE payload::text LIKE '%ownerUserId%' OR payload::text LIKE '%ingestKey%' OR payload::text LIKE '%timelinePosition%'").fetch_one(&pool).await?;
    assert_eq!(leaked, 0);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn authorization_expiry_rolls_back_even_when_domain_lock_is_busy() -> Result<(), TestError> {
    let pool = database().await?;
    config(&pool, 1).await?;
    let stream: String = sqlx::query_scalar("SELECT stream_id FROM stream_configs")
        .fetch_one(&pool)
        .await?;
    let mut lock = pool.begin().await?;
    sqlx::query("SELECT position FROM discovery_commit_position FOR UPDATE")
        .execute(&mut *lock)
        .await?;
    let result = PostgresStreamConfigRepository::new(pool.clone())
        .patch_metadata(PatchStreamMetadataCommand {
            stream_id: StreamId::parse(stream).ok_or_else(|| debug_error("invalid stream"))?,
            authorization_expires_at: Some(std::time::Instant::now() + Duration::from_millis(80)),
            title: Some("Expired permission".into()),
            category_id: None,
            tag_ids: None,
            category_label: None,
            tag_labels: None,
        })
        .await;
    assert!(result.is_err());
    lock.rollback().await?;
    let title: String = sqlx::query_scalar("SELECT title FROM stream_configs")
        .fetch_one(&pool)
        .await?;
    assert_eq!(title, "Emisión");
    let position: i64 = sqlx::query_scalar("SELECT position FROM discovery_commit_position")
        .fetch_one(&pool)
        .await?;
    assert_eq!(position, 1);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn delivery_consumers_have_independent_claims_and_durable_dead_letters()
-> Result<(), TestError> {
    let pool = database().await?;
    config(&pool, 1).await?;
    let clock = MonotonicSessionClock::default();
    let _ = authorizer(&pool)
        .authorize(authorization(1), &clock)
        .await?;
    let chat = PostgresDomainEventOutboxRepository::new(pool.clone());
    let discovery = PostgresDomainEventOutboxRepository::for_discovery(pool.clone());
    let c = chat
        .claim_next("chat-relay".into(), Duration::from_secs(15))
        .await?
        .ok_or_else(|| debug_error("chat claim"))?;
    let d = discovery
        .claim_next("discovery-relay".into(), Duration::from_secs(15))
        .await?
        .ok_or_else(|| debug_error("discovery claim"))?;
    assert_ne!(c.envelope.event_id, d.envelope.event_id);
    assert_eq!(d.envelope.event_type, "StreamDiscoverySnapshot");
    discovery
        .dead_letter(
            d.envelope.event_id,
            "discovery-relay".into(),
            d.claim_token,
            "PERMANENT_HTTP_ERROR",
        )
        .await?;
    chat.acknowledge(c.envelope.event_id, "chat-relay".into(), c.claim_token)
        .await?;
    let retained:bool=sqlx::query_scalar("SELECT dead_letter_at IS NOT NULL AND published_at IS NULL FROM streaming_outbox WHERE event_id=$1").bind(d.envelope.event_id).fetch_one(&pool).await?;
    assert!(retained);
    let newer = discovery
        .claim_next("discovery-relay".into(), Duration::from_secs(15))
        .await?
        .ok_or_else(|| debug_error("newer snapshot"))?;
    assert!(newer.envelope.sequence > d.envelope.sequence);
    streaming_service::adapters::outbound::postgres::delivery_operations::execute(
        &pool,
        &[
            "redrive".into(),
            d.envelope.event_id.to_string(),
            "test-operator".into(),
            "Receiver repaired".into(),
        ],
    )
    .await
    .map_err(debug_error)?;
    let same = discovery
        .claim_next("redrive".into(), Duration::from_secs(15))
        .await?
        .ok_or_else(|| debug_error("redrive claim"))?;
    assert_eq!(same.envelope.event_id, d.envelope.event_id);
    assert_eq!(same.envelope.payload, d.envelope.payload);
    assert_eq!(same.attempts, 1);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn startup_recovery_ends_sessions_without_extending_clock_or_grace() -> Result<(), TestError>
{
    let pool = database().await?;
    config(&pool, 1).await?;
    let clock = MonotonicSessionClock::default();
    let ingest = authorizer(&pool)
        .authorize(authorization(1), &clock)
        .await?;
    let ended=streaming_service::adapters::outbound::postgres::control_owner::terminate_unverifiable_sessions(&pool).await?;
    assert_eq!(ended, 1);
    let state:(String,String,Option<time::OffsetDateTime>,Option<time::OffsetDateTime>)=sqlx::query_as("SELECT status,availability,owner_lease_expires_at,grace_deadline_at FROM stream_sessions WHERE session_id=$1")
        .bind(ingest.authorization.session_id.as_str()).fetch_one(&pool).await?;
    assert_eq!(state.0, "ENDED");
    assert_eq!(state.1, "OFFLINE");
    assert!(state.2.is_none());
    assert!(state.3.is_none());
    let payload: serde_json::Value = sqlx::query_scalar(
        "SELECT payload FROM streaming_outbox WHERE event_type='StreamSessionEnded'",
    )
    .fetch_one(&pool)
    .await?;
    assert_eq!(payload.as_object().map(serde_json::Map::len), Some(6));
    assert_eq!(streaming_service::adapters::outbound::postgres::control_owner::terminate_unverifiable_sessions(&pool).await?,0);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn catalog_labels_survive_inactivation_and_cannot_overwrite_new_metadata()
-> Result<(), TestError> {
    use streaming_service::application::ports::taxonomy::TaxonomyValue;
    let pool = database().await?;
    config(&pool, 1).await?;
    let repo = PostgresStreamingRepository::new(pool.clone());
    let config = repo
        .find_stream_config_by_channel("channel_1".into())
        .await
        .map_err(debug_error)?
        .ok_or("config")?;
    let labels = vec![TaxonomyValue {
        id: "category_example".into(),
        name: "Último nombre".into(),
        active: false,
    }];
    repo.remember_catalog_values(config.stream_id.clone(), 1, labels.clone())
        .await
        .map_err(debug_error)?;
    PostgresStreamConfigRepository::new(pool.clone())
        .patch_metadata(PatchStreamMetadataCommand {
            authorization_expires_at: None,
            category_label: None,
            tag_labels: None,
            stream_id: config.stream_id.clone(),
            title: Some("Título nuevo".into()),
            category_id: None,
            tag_ids: None,
        })
        .await
        .map_err(debug_error)?;
    repo.remember_catalog_values(
        config.stream_id.clone(),
        1,
        vec![TaxonomyValue {
            id: "category_example".into(),
            name: "Stale".into(),
            active: true,
        }],
    )
    .await
    .map_err(debug_error)?;
    let stored = repo
        .stored_catalog_values(config.stream_id.clone())
        .await
        .map_err(debug_error)?;
    assert_eq!(stored[0].name, "Último nombre");
    assert!(!stored[0].active);
    let payload: serde_json::Value =
        sqlx::query_scalar("SELECT payload FROM streaming_discovery_state WHERE stream_id=$1")
            .bind(config.stream_id.as_str())
            .fetch_one(&pool)
            .await?;
    assert_eq!(payload["category"]["name"], "Último nombre");
    assert_eq!(payload["metadataVersion"], 2);
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn viewer_leases_dedupe_heartbeat_and_expire_at_thirty_seconds() -> Result<(), TestError> {
    let pool = database().await?;
    let clock = MonotonicSessionClock::default();
    let ingest = playable(&pool, &clock).await?;
    let leases = PostgresViewerLeaseRepository::new(pool.clone());
    let key = Uuid::new_v4();
    assert!(
        leases
            .create(ingest.session_id.clone(), key, "lease_live".into(), [7; 32])
            .await
            .map_err(debug_error)?
            .created
    );
    assert!(
        !leases
            .create(ingest.session_id.clone(), key, "lease_live".into(), [7; 32])
            .await
            .map_err(debug_error)?
            .created
    );
    assert_eq!(
        leases.heartbeat("lease_live".into(), [8; 32]).await.err(),
        Some(ViewerLeaseRepositoryError::InvalidToken)
    );
    sqlx::query("UPDATE viewer_leases SET heartbeat_at=clock_timestamp()-INTERVAL '29 seconds' WHERE lease_id='lease_live'").execute(&pool).await?;
    leases
        .heartbeat("lease_live".into(), [7; 32])
        .await
        .map_err(debug_error)?;
    for seconds in [30_i32, 31] {
        let name = format!("lease_expired_{seconds}");
        leases
            .create(
                ingest.session_id.clone(),
                Uuid::new_v4(),
                name.clone(),
                [9; 32],
            )
            .await
            .map_err(debug_error)?;
        sqlx::query("UPDATE viewer_leases SET heartbeat_at=clock_timestamp()-($2*INTERVAL '1 second') WHERE lease_id=$1").bind(&name).bind(seconds).execute(&pool).await?;
        assert_eq!(
            leases.heartbeat(name, [9; 32]).await.err(),
            Some(ViewerLeaseRepositoryError::Expired)
        );
    }
    sqlx::query("UPDATE stream_sessions SET viewer_count_observed_at=clock_timestamp()-INTERVAL '2 seconds' WHERE session_id=$1").bind(ingest.session_id.as_str()).execute(&pool).await?;
    PostgresViewerCountSnapshotRepository::new(pool.clone())
        .refresh_due_snapshots()
        .await
        .map_err(debug_error)?;
    assert_eq!(snapshot(&pool, &ingest.session_id).await?.0, 1);
    leases
        .close("lease_live".into(), [7; 32])
        .await
        .map_err(debug_error)?;
    leases
        .close("lease_live".into(), [7; 32])
        .await
        .map_err(debug_error)?;
    pool.close().await;
    Ok(())
}

#[tokio::test]
#[ignore = "requiere STREAMING_TEST_DATABASE_URL PostgreSQL desechable"]
async fn media_receiver_durably_dedupes_and_fences_payload_reuse_and_old_generation()
-> Result<(), TestError> {
    use streaming_service::application::ports::media_callbacks::{
        MediaCallbackReceipt, MediaCallbackRepositoryError,
    };
    let pool = database().await?;
    config(&pool, 1).await?;
    let clock = MonotonicSessionClock::default();
    let ingest = authorizer(&pool)
        .authorize(authorization(1), &clock)
        .await?
        .authorization;
    let callbacks = PostgresMediaCallbackRepository::new(pool.clone());
    let event = Uuid::new_v4();
    let command = |payload_hash| AcceptMediaCallbackCommand {
        event_id: event,
        kind: MediaCallbackKind::SourceConnected,
        stream_id: ingest.stream_id.clone(),
        session_id: ingest.session_id.clone(),
        stream_generation: 1,
        source_generation: 1,
        payload_hash,
        payload: json!({}),
    };
    assert_eq!(
        callbacks
            .accept(command([1; 32]))
            .await
            .map_err(debug_error)?,
        MediaCallbackReceipt::Accepted
    );
    assert_eq!(
        callbacks
            .accept(command([1; 32]))
            .await
            .map_err(debug_error)?,
        MediaCallbackReceipt::Duplicate
    );
    assert_eq!(
        callbacks.accept(command([2; 32])).await.err(),
        Some(MediaCallbackRepositoryError::EventIdConflict)
    );
    sqlx::query("UPDATE stream_sessions SET source_generation=2 WHERE session_id=$1")
        .bind(ingest.session_id.as_str())
        .execute(&pool)
        .await?;
    let old = AcceptMediaCallbackCommand {
        event_id: Uuid::new_v4(),
        ..command([3; 32])
    };
    assert_eq!(
        callbacks.accept(old).await.map_err(debug_error)?,
        MediaCallbackReceipt::StaleGeneration
    );
    let counts: (i64,i64) = sqlx::query_as("SELECT count(*),count(*) FILTER(WHERE processing_error_code='STALE_GENERATION') FROM media_callback_inbox").fetch_one(&pool).await?;
    assert_eq!(counts, (2, 1));
    pool.close().await;
    Ok(())
}
