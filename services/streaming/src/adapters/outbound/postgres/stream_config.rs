use crate::adapters::outbound::authorization_budget;
use std::future::Future;

use serde_json::Value;
use sqlx::{FromRow, PgPool, Postgres, Transaction, types::Json};
use subtle::ConstantTimeEq;
use uuid::Uuid;

use crate::{
    application::ports::{
        stream_config::{
            CreateStreamConfigCommand, PatchStreamMetadataCommand, PatchStreamMetadataResult,
            RotateIngestKeyCommand, RotatedIngestKeyResult, StreamConfigRepository,
            StreamConfigRepositoryError, StreamConfigWriteResult,
        },
        streaming_repository::StreamConfigSnapshot,
    },
    domain::ids::StreamId,
};

const OPERATION: &str = "stream-config-create";

pub struct PostgresStreamConfigRepository {
    pool: PgPool,
}

impl PostgresStreamConfigRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    async fn create_in_transaction(
        &self,
        command: CreateStreamConfigCommand,
    ) -> Result<StreamConfigWriteResult, StreamConfigRepositoryError> {
        let deadline = command.authorization_expires_at;
        let mut transaction = self.pool.begin().await.map_err(persistence_error)?;
        authorization_budget::configure(&mut transaction, deadline)
            .await
            .map_err(persistence_error)?;
        sqlx::query("SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE")
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
        let operation_scope = OPERATION;
        sqlx::query(
            "INSERT INTO streaming_idempotency (operation_scope, idempotency_key, request_fingerprint) VALUES ($1, $2, $3) ON CONFLICT (operation_scope, idempotency_key) DO NOTHING",
        )
        .bind(operation_scope)
        .bind(command.idempotency_key)
        .bind(command.request_fingerprint.as_slice())
        .execute(&mut *transaction)
        .await
        .map_err(persistence_error)?;

        let idempotency = sqlx::query_as::<_, IdempotencyRecord>(
            "SELECT request_fingerprint, response_status, response_payload, resource_id FROM streaming_idempotency WHERE operation_scope = $1 AND idempotency_key = $2 FOR UPDATE",
        )
        .bind(operation_scope)
        .bind(command.idempotency_key)
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?
        .ok_or(StreamConfigRepositoryError::InvalidStoredData)?;

        if idempotency.request_fingerprint.len() != 32
            || idempotency
                .request_fingerprint
                .as_slice()
                .ct_eq(command.request_fingerprint.as_slice())
                .unwrap_u8()
                != 1
        {
            return Err(StreamConfigRepositoryError::IdempotencyKeyReused);
        }

        if let Some(resource_id) = idempotency.resource_id.as_deref() {
            if idempotency.response_status.is_none() || idempotency.response_payload.is_none() {
                return Err(StreamConfigRepositoryError::InvalidStoredData);
            }
            let config = serde_json::from_value::<StreamConfigSnapshot>(
                idempotency
                    .response_payload
                    .ok_or(StreamConfigRepositoryError::InvalidStoredData)?,
            )
            .map_err(|_| StreamConfigRepositoryError::InvalidStoredData)?;
            if config.stream_id.as_str() != resource_id {
                return Err(StreamConfigRepositoryError::InvalidStoredData);
            }
            // Configurations are durable and never deleted. Confirm the referenced row remains
            // present, while replaying the original non-secret response snapshot.
            let _ = find_config_by_stream_id(&mut transaction, resource_id).await?;
            authorization_budget::valid(deadline).map_err(persistence_error)?;
            transaction.commit().await.map_err(persistence_error)?;
            return Ok(StreamConfigWriteResult {
                config,
                created: false,
            });
        }
        if idempotency.response_status.is_some() || idempotency.response_payload.is_some() {
            return Err(StreamConfigRepositoryError::InvalidStoredData);
        }

        if let Some(existing) =
            find_config_by_channel(&mut transaction, &command.channel_id).await?
        {
            persist_result(
                &mut transaction,
                operation_scope,
                command.idempotency_key,
                &existing,
                200,
            )
            .await?;
            authorization_budget::valid(deadline).map_err(persistence_error)?;
            transaction.commit().await.map_err(persistence_error)?;
            return Ok(StreamConfigWriteResult {
                config: existing,
                created: false,
            });
        }

        let result = sqlx::query(
            "INSERT INTO stream_configs (stream_id, channel_id, owner_user_id, title, category_id, tag_ids, ingest_key_hash, catalog_labels) VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (channel_id) DO NOTHING",
        )
        .bind(command.stream_id.as_str())
        .bind(&command.channel_id)
        .bind(&command.owner_user_id)
        .bind(&command.title)
        .bind(&command.category_id)
        .bind(Json(&command.tag_ids))
        .bind(command.ingest_key_hash.as_slice())
        .bind(Json(&command.catalog_labels))
        .execute(&mut *transaction)
        .await
        .map_err(persistence_error)?;

        let created = result.rows_affected() == 1;
        let config = if created {
            find_config_by_stream_id(&mut transaction, command.stream_id.as_str()).await?
        } else {
            find_config_by_channel(&mut transaction, &command.channel_id)
                .await?
                .ok_or(StreamConfigRepositoryError::InvalidStoredData)?
        };
        persist_result(
            &mut transaction,
            operation_scope,
            command.idempotency_key,
            &config,
            if created { 201 } else { 200 },
        )
        .await?;
        authorization_budget::valid(deadline).map_err(persistence_error)?;
        transaction.commit().await.map_err(persistence_error)?;
        Ok(StreamConfigWriteResult { config, created })
    }

    async fn patch_in_transaction(
        &self,
        command: PatchStreamMetadataCommand,
    ) -> Result<PatchStreamMetadataResult, StreamConfigRepositoryError> {
        let deadline = command.authorization_expires_at;
        let mut transaction = self.pool.begin().await.map_err(persistence_error)?;
        authorization_budget::configure(&mut transaction, deadline)
            .await
            .map_err(persistence_error)?;
        sqlx::query("SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE")
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
        let row = sqlx::query_as::<_, StreamConfigRow>(
            "SELECT stream_id, channel_id, owner_user_id, title, category_id, tag_ids, metadata_version, stream_generation FROM stream_configs WHERE stream_id = $1 FOR UPDATE",
        )
        .bind(command.stream_id.as_str())
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?
        .ok_or(StreamConfigRepositoryError::NotFound)?;
        let mut config = row_to_snapshot(row)?;

        let active_session = sqlx::query_as::<_, (String, String)>(
            "SELECT session_id, status FROM stream_sessions WHERE stream_id = $1 AND status <> 'ENDED' FOR UPDATE",
        )
        .bind(config.stream_id.as_str())
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?;
        if let Some((_, status)) = active_session.as_ref() {
            match status.as_str() {
                "LIVE" => {}
                "PREPARING" | "RECONNECT_GRACE" => {
                    return Err(StreamConfigRepositoryError::Conflict);
                }
                _ => return Err(StreamConfigRepositoryError::InvalidStoredData),
            }
        }

        let mut labels = sqlx::query_scalar::<
            _,
            Json<Vec<crate::application::ports::taxonomy::TaxonomyValue>>,
        >("SELECT catalog_labels FROM stream_configs WHERE stream_id=$1")
        .bind(config.stream_id.as_str())
        .fetch_one(&mut *transaction)
        .await
        .map_err(persistence_error)?
        .0;
        let mut new_labels = labels.clone();
        if let Some(category) = command.category_label {
            new_labels.retain(|v| v.id != config.category_id);
            new_labels.insert(0, category);
        }
        if let Some(tags) = command.tag_labels {
            new_labels.retain(|v| !config.tag_ids.contains(&v.id));
            new_labels.extend(tags);
        }
        let labels_changed = labels != new_labels;
        labels = new_labels;
        let mut changed_fields = Vec::new();
        if let Some(title) = command.title
            && title != config.title
        {
            config.title = title;
            changed_fields.push("title".to_owned());
        }
        if let Some(category_id) = command.category_id
            && category_id != config.category_id
        {
            config.category_id = category_id;
            changed_fields.push("categoryId".to_owned());
        }
        if let Some(tag_ids) = command.tag_ids
            && tag_ids != config.tag_ids
        {
            config.tag_ids = tag_ids;
            changed_fields.push("tagIds".to_owned());
        }

        if changed_fields.is_empty() && !labels_changed {
            authorization_budget::valid(deadline).map_err(persistence_error)?;
            transaction.commit().await.map_err(persistence_error)?;
            return Ok(PatchStreamMetadataResult {
                config,
                changed_fields,
            });
        }

        config.metadata_version = config
            .metadata_version
            .checked_add(1)
            .filter(|version| *version > 0)
            .ok_or(StreamConfigRepositoryError::InvalidStoredData)?;
        let now = sqlx::query_scalar::<_, time::OffsetDateTime>("SELECT clock_timestamp()")
            .fetch_one(&mut *transaction)
            .await
            .map_err(persistence_error)?;
        sqlx::query(
            "UPDATE stream_configs SET title = $2, category_id = $3, tag_ids = $4, metadata_version = $5, updated_at = $6, catalog_labels = $7 WHERE stream_id = $1",
        )
        .bind(config.stream_id.as_str())
        .bind(&config.title)
        .bind(&config.category_id)
        .bind(Json(&config.tag_ids))
        .bind(config.metadata_version)
        .bind(now)
        .bind(Json(&labels))
        .execute(&mut *transaction)
        .await
        .map_err(persistence_error)?;

        authorization_budget::valid(deadline).map_err(persistence_error)?;
        transaction.commit().await.map_err(persistence_error)?;
        Ok(PatchStreamMetadataResult {
            config,
            changed_fields,
        })
    }

    async fn rotate_ingest_key_in_transaction(
        &self,
        command: RotateIngestKeyCommand,
    ) -> Result<RotatedIngestKeyResult, StreamConfigRepositoryError> {
        let deadline = command.authorization_expires_at;
        let mut transaction = self.pool.begin().await.map_err(persistence_error)?;
        authorization_budget::configure(&mut transaction, deadline)
            .await
            .map_err(persistence_error)?;
        sqlx::query("SELECT position FROM discovery_commit_position WHERE singleton FOR UPDATE")
            .execute(&mut *transaction)
            .await
            .map_err(persistence_error)?;
        let key_row = sqlx::query_as::<_, IngestKeyConfigRow>(
            "SELECT stream_id, channel_id, owner_user_id, title, category_id, tag_ids, metadata_version, stream_generation, ingest_key_version FROM stream_configs WHERE stream_id = $1 FOR UPDATE",
        )
        .bind(command.stream_id.as_str())
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?
        .ok_or(StreamConfigRepositoryError::NotFound)?;
        let config = row_to_snapshot(StreamConfigRow {
            stream_id: key_row.stream_id,
            channel_id: key_row.channel_id,
            owner_user_id: key_row.owner_user_id,
            title: key_row.title,
            category_id: key_row.category_id,
            tag_ids: key_row.tag_ids,
            metadata_version: key_row.metadata_version,
            stream_generation: key_row.stream_generation,
        })?;
        let active_session = sqlx::query_scalar::<_, String>(
            "SELECT status FROM stream_sessions WHERE stream_id = $1 AND status <> 'ENDED' FOR UPDATE",
        )
        .bind(config.stream_id.as_str())
        .fetch_optional(&mut *transaction)
        .await
        .map_err(persistence_error)?;
        if active_session.is_some() {
            return Err(StreamConfigRepositoryError::Conflict);
        }
        let ingest_key_version = key_row
            .ingest_key_version
            .checked_add(1)
            .filter(|version| *version > 0)
            .ok_or(StreamConfigRepositoryError::InvalidStoredData)?;
        sqlx::query(
            "UPDATE stream_configs SET ingest_key_hash = $2, ingest_key_version = $3, updated_at = clock_timestamp() WHERE stream_id = $1",
        )
        .bind(config.stream_id.as_str())
        .bind(command.ingest_key_hash.as_slice())
        .bind(ingest_key_version)
        .execute(&mut *transaction)
        .await
        .map_err(persistence_error)?;
        authorization_budget::valid(deadline).map_err(persistence_error)?;
        transaction.commit().await.map_err(persistence_error)?;
        Ok(RotatedIngestKeyResult {
            config,
            ingest_key_version,
        })
    }
}

impl StreamConfigRepository for PostgresStreamConfigRepository {
    fn create_if_absent(
        &self,
        command: CreateStreamConfigCommand,
    ) -> impl Future<Output = Result<StreamConfigWriteResult, StreamConfigRepositoryError>> + Send
    {
        async move { self.create_in_transaction(command).await }
    }

    fn patch_metadata(
        &self,
        command: PatchStreamMetadataCommand,
    ) -> impl Future<Output = Result<PatchStreamMetadataResult, StreamConfigRepositoryError>> + Send
    {
        async move { self.patch_in_transaction(command).await }
    }

    fn rotate_ingest_key(
        &self,
        command: RotateIngestKeyCommand,
    ) -> impl Future<Output = Result<RotatedIngestKeyResult, StreamConfigRepositoryError>> + Send
    {
        async move { self.rotate_ingest_key_in_transaction(command).await }
    }
}

#[derive(FromRow)]
struct IdempotencyRecord {
    request_fingerprint: Vec<u8>,
    response_status: Option<i16>,
    response_payload: Option<Value>,
    resource_id: Option<String>,
}

#[derive(FromRow)]
struct IngestKeyConfigRow {
    stream_id: String,
    channel_id: String,
    owner_user_id: String,
    title: String,
    category_id: String,
    tag_ids: Json<Vec<String>>,
    metadata_version: i64,
    stream_generation: i64,
    ingest_key_version: i64,
}

#[derive(FromRow)]
struct StreamConfigRow {
    stream_id: String,
    channel_id: String,
    owner_user_id: String,
    title: String,
    category_id: String,
    tag_ids: Json<Vec<String>>,
    metadata_version: i64,
    stream_generation: i64,
}

async fn find_config_by_stream_id(
    transaction: &mut Transaction<'_, Postgres>,
    stream_id: &str,
) -> Result<StreamConfigSnapshot, StreamConfigRepositoryError> {
    let row = sqlx::query_as::<_, StreamConfigRow>(
        "SELECT stream_id, channel_id, owner_user_id, title, category_id, tag_ids, metadata_version, stream_generation FROM stream_configs WHERE stream_id = $1",
    )
    .bind(stream_id)
    .fetch_optional(&mut **transaction)
    .await
    .map_err(persistence_error)?
    .ok_or(StreamConfigRepositoryError::InvalidStoredData)?;
    row_to_snapshot(row)
}

async fn find_config_by_channel(
    transaction: &mut Transaction<'_, Postgres>,
    channel_id: &str,
) -> Result<Option<StreamConfigSnapshot>, StreamConfigRepositoryError> {
    let row = sqlx::query_as::<_, StreamConfigRow>(
        "SELECT stream_id, channel_id, owner_user_id, title, category_id, tag_ids, metadata_version, stream_generation FROM stream_configs WHERE channel_id = $1",
    )
    .bind(channel_id)
    .fetch_optional(&mut **transaction)
    .await
    .map_err(persistence_error)?;
    row.map(row_to_snapshot).transpose()
}

fn row_to_snapshot(
    row: StreamConfigRow,
) -> Result<StreamConfigSnapshot, StreamConfigRepositoryError> {
    Ok(StreamConfigSnapshot {
        stream_id: StreamId::parse(row.stream_id)
            .ok_or(StreamConfigRepositoryError::InvalidStoredData)?,
        channel_id: row.channel_id,
        owner_user_id: row.owner_user_id,
        title: row.title,
        category_id: row.category_id,
        tag_ids: row.tag_ids.0,
        metadata_version: row.metadata_version,
        stream_generation: row.stream_generation,
    })
}

async fn persist_result(
    transaction: &mut Transaction<'_, Postgres>,
    operation_scope: &str,
    idempotency_key: Uuid,
    config: &StreamConfigSnapshot,
    status: i16,
) -> Result<(), StreamConfigRepositoryError> {
    let payload =
        serde_json::to_value(config).map_err(|_| StreamConfigRepositoryError::InvalidStoredData)?;
    let updated = sqlx::query(
        "UPDATE streaming_idempotency SET response_status = $3, response_payload = $4, resource_id = $5 WHERE operation_scope = $1 AND idempotency_key = $2 AND response_status IS NULL AND response_payload IS NULL",
    )
    .bind(operation_scope)
    .bind(idempotency_key)
    .bind(status)
    .bind(payload)
    .bind(config.stream_id.as_str())
    .execute(&mut **transaction)
    .await
    .map_err(persistence_error)?;
    if updated.rows_affected() != 1 {
        return Err(StreamConfigRepositoryError::InvalidStoredData);
    }
    Ok(())
}

fn persistence_error(_: sqlx::Error) -> StreamConfigRepositoryError {
    StreamConfigRepositoryError::Unavailable
}
