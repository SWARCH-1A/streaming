use std::{error::Error, sync::Arc};

use axum::{http::StatusCode, middleware};
use serde_json::Value;
use sqlx::postgres::PgPoolOptions;

use super::*;
use crate::{
    adapters::{
        inbound::http::request_id,
        outbound::{
            http_clients::CoreHttpGateway,
            monotonic_clock::MonotonicSessionClock,
            os_secret_generator::OsSecretGenerator,
            postgres::{PostgresStreamConfigRepository, PostgresStreamingRepository},
        },
    },
    application::ports::session_clock::SessionMonotonicClock,
};

type TestError = Box<dyn Error + Send + Sync>;

fn test_router() -> Result<Router, TestError> {
    // Extraction errors must return before using any database or Core connection.
    let pool =
        PgPoolOptions::new().connect_lazy("postgres://fixture:fixture@127.0.0.1:1/fixture")?;
    let core = Arc::new(CoreHttpGateway::new(
        reqwest::Client::new(),
        None,
        None,
        None,
    ));
    let write = Arc::new(PostgresStreamConfigRepository::new(pool.clone()));
    let read = Arc::new(PostgresStreamingRepository::new(pool));
    let secret = Arc::new(OsSecretGenerator);
    let clock: Arc<dyn SessionMonotonicClock> = Arc::new(MonotonicSessionClock::default());
    Ok(router(
        Arc::new(CreateStreamConfig::new(
            core.clone(),
            write.clone(),
            secret.clone(),
            None,
        )),
        Arc::new(GetStreamConfig::new(
            core.clone(),
            core.clone(),
            read.clone(),
            None,
        )),
        PublicStreamQueries {
            stream: Arc::new(GetPublicStream::new(read.clone(), core.clone())),
            session: Arc::new(GetPublicStreamSession::new(
                read.clone(),
                None,
                clock.clone(),
            )),
        },
        Arc::new(PatchStreamMetadata::new(
            core.clone(),
            write.clone(),
            read.clone(),
        )),
        Arc::new(RotateIngestKey::new(
            core.clone(),
            write,
            read.clone(),
            secret,
            None,
        )),
        Arc::new(StopStreamSession::new(core, read, clock)),
        StreamConfigSecurity {
            session_cookie_name: Some("fixture_session".into()),
            web_origin: Some("http://fixture.test".into()),
        },
    )
    .layer(middleware::from_fn(request_id::attach_request_id)))
}

#[tokio::test]
async fn json_rejections_keep_the_rest_envelope_and_request_id() -> Result<(), TestError> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let url = format!("http://{}", listener.local_addr()?);
    let router = test_router()?;
    let server = tokio::spawn(async move { axum::serve(listener, router).await });
    let client = reqwest::Client::new();
    let result = async {
        for (method, path, code) in [
            (
                reqwest::Method::POST,
                "/api/channels/chn_fixture/streams",
                "INVALID_STREAM_CONFIG",
            ),
            (
                reqwest::Method::PATCH,
                "/api/streams/str_fixture",
                "INVALID_STREAM_METADATA",
            ),
        ] {
            for (body, content_type, expected_status) in [
                ("{".to_owned(), "application/json", StatusCode::BAD_REQUEST),
                (
                    "{\"title\":\"Fixture\",\"categoryId\":\"cat_fixture\",\"unknown\":true}"
                        .into(),
                    "application/json",
                    StatusCode::UNPROCESSABLE_ENTITY,
                ),
                (
                    format!(
                        "{{\"title\":\"{}\",\"categoryId\":\"cat_fixture\"}}",
                        "x".repeat(MAX_REQUEST_BYTES)
                    ),
                    "application/json",
                    StatusCode::PAYLOAD_TOO_LARGE,
                ),
                (
                    "{}".into(),
                    "text/plain",
                    StatusCode::UNSUPPORTED_MEDIA_TYPE,
                ),
            ] {
                let response = client
                    .request(method.clone(), format!("{url}{path}"))
                    .header("Content-Type", content_type)
                    .header("X-Request-Id", "req_fixture_json")
                    .header("Cookie", "fixture_session=fixture")
                    .header("Origin", "http://fixture.test")
                    .header("Idempotency-Key", "00000000-0000-4000-8000-000000000001")
                    .body(body)
                    .send()
                    .await?;
                assert_eq!(response.status(), expected_status);
                assert_eq!(response.headers()["x-request-id"], "req_fixture_json");
                let value: Value = response.json().await?;
                assert_eq!(value["code"], code);
                assert_eq!(value["requestId"], "req_fixture_json");
                assert!(value["message"].as_str().is_some_and(|m| !m.is_empty()));
                assert_eq!(value.as_object().ok_or("error envelope")?.len(), 3);
            }
        }
        Ok::<(), TestError>(())
    }
    .await;
    server.abort();
    result
}
