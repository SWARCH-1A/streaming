// This crate deliberately imports the real Streaming gateway, not a reimplementation of its DTOs.
#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};
    use streaming_service::adapters::outbound::http_clients::{CoreHttpGateway, build_client};
    use streaming_service::application::ports::{
        identity::SessionCredential,
        owner_context::{OwnerContextError, OwnerContextGateway, OwnerContextRequest, OwnerOperation},
        taxonomy::TaxonomyGateway,
    };
    type Error = Box<dyn std::error::Error + Send + Sync>;

    #[tokio::test]
    async fn actual_rust_gateway_accepts_core_and_preserves_error_semantics() -> Result<(), Error> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let file = std::env::var("CONTRACT_FIXTURE")?;
        let data: serde_json::Value = serde_json::from_slice(&std::fs::read(file)?)?;
        let value = |key: &str| data[key].as_str().map(str::to_owned).ok_or("missing fixture field");
        let client = build_client(Duration::from_secs(5))?;
        let gateway = CoreHttpGateway::new(client.clone(), Some(value("privateUrl")?),
            Some(value("serviceToken")?), Some("stream_session".into()));
        let request = |operation, category_id, tag_ids| -> Result<OwnerContextRequest, Error> {
            Ok(OwnerContextRequest { command_id: uuid::Uuid::new_v4(), operation,
                channel_id: value("channelId")?, category_id, tag_ids })
        };
        let credential = |key| -> Result<SessionCredential, Error> {
            SessionCredential::new(value(key)?).ok_or_else(|| "invalid fixture credential".into())
        };
        // Exercise the actual deserializer and kind/ID/command validation against Java.
        let category = value("categoryId")?;
        let tag = value("tagId")?;
        let resolved = gateway.resolve_values(vec![category.clone(), tag.clone()]).await
            .map_err(|_| "real gateway rejected active catalog response")?;
        assert_eq!(resolved.len(), 2);
        let context = gateway.authorize(credential("credential")?, request(OwnerOperation::CreateConfig,
            Some(category.clone()), Some(vec![tag.clone()]))?).await
            .map_err(|_| "real gateway rejected owner context")?;
        assert_eq!(context.user_id, value("userId")?);
        assert_eq!(context.category.as_ref().map(|v| &v.id), Some(&category));
        assert_eq!(context.tags.as_ref().map(Vec::len), Some(1));
        assert!(Instant::now() < context.expires_at);

        for operation in [OwnerOperation::PatchMetadata, OwnerOperation::RotateKey, OwnerOperation::StopSession] {
            let context = gateway.authorize(credential("credential")?, request(operation, None, None)?).await
                .map_err(|_| "omitted fields were not compatible")?;
            assert!(context.category.is_none() && context.tags.is_none());
        }
        for (key, expected) in [("strangerCredential", OwnerContextError::Forbidden),
            ("revokedCredential", OwnerContextError::Inactive)] {
            let error = gateway.authorize(credential(key)?, request(OwnerOperation::PatchMetadata, None, None)?).await.err();
            assert_eq!(error, Some(expected));
        }
        for invalid in [tag, "cat_unknown".into(), value("tombstoneId")?] {
            let error = gateway.authorize(credential("credential")?, request(OwnerOperation::CreateConfig,
                Some(invalid), Some(vec![]))?).await.err();
            assert_eq!(error, Some(OwnerContextError::InvalidCatalog));
        }
        let tombstone = gateway.resolve_values(vec![value("tombstoneId")?]).await
            .map_err(|_| "real gateway rejected tombstone response")?;
        assert!(!tombstone[0].active);
        assert_eq!(tombstone[0].name, "Contrato conservado");
        let missing = OwnerContextRequest { channel_id: "chn_unknown".into(),
            ..request(OwnerOperation::StopSession, None, None)? };
        assert_eq!(gateway.authorize(credential("credential")?, missing).await.err(), Some(OwnerContextError::NotFound));

        let denied = CoreHttpGateway::new(client.clone(), Some(value("privateUrl")?),
            Some("invalid-service-token".into()), Some("stream_session".into()));
        assert!(denied.authorize(credential("credential")?, request(OwnerOperation::StopSession, None, None)?).await.is_err());
        for path in ["owner-context", "catalog-values"] {
            let response = client.post(format!("{}/internal/core/streaming/{path}", value("publicUrl")?))
                .header("X-Service-Name", "streaming").header("X-Service-Token", value("serviceToken")?)
                .json(&serde_json::json!({})).send().await?;
            assert_eq!(response.status(), reqwest::StatusCode::NOT_FOUND);
        }
        Ok(())
    }
}
