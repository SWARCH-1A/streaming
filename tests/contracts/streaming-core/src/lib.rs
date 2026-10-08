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
        let catalog_only = CoreHttpGateway::new(client.clone(), Some(value("privateUrl")?),
            Some(value("catalogServiceToken")?), Some("stream_session".into()));
        let catalog_values = catalog_only.resolve_values(vec![category.clone(), tag.clone()]).await
            .map_err(|_| "catalog-only gateway rejected catalog response")?;
        assert_eq!(catalog_values.len(), 2);
        for operation in [OwnerOperation::CreateConfig, OwnerOperation::PatchMetadata,
            OwnerOperation::RotateKey, OwnerOperation::StopSession] {
            assert_eq!(catalog_only.authorize(credential("credential")?,
                request(operation, Some(category.clone()), None)?).await.err(), Some(OwnerContextError::Inactive));
        }
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
        for token in ["serviceToken", "catalogServiceToken"] {
          for path in ["owner-context", "catalog-values"] {
            let response = client.post(format!("{}/internal/core/streaming/{path}", value("publicUrl")?))
                .header("X-Service-Name", "streaming").header("X-Service-Token", value(token)?)
                .json(&serde_json::json!({})).send().await?;
            assert_eq!(response.status(), reqwest::StatusCode::NOT_FOUND);
          }
        }
        // Save wire payloads for the independent neutral-schema consumer. The fixture credentials
        // remain in headers; only responses from these fictitious accounts are written.
        let mut samples = Vec::new();
        for (path, body, token, expected, schema) in [
            ("owner-context", serde_json::json!({"commandId":uuid::Uuid::new_v4(),"operation":"CREATE_CONFIG",
                "channelId":value("channelId")?,"categoryId":category,"tagIds":[]}), "serviceToken", 200, "OwnerContext"),
            ("catalog-values", serde_json::json!({"ids":[category]}), "catalogServiceToken", 200, "CatalogValues"),
            ("owner-context", serde_json::json!({"commandId":uuid::Uuid::new_v4(),"operation":"STOP_SESSION",
                "channelId":value("channelId")?}), "catalogServiceToken", 401, "Error"),
        ] {
            let response = client.post(format!("{}/internal/core/streaming/{path}", value("privateUrl")?))
                .header("X-Service-Name", "streaming").header("X-Service-Token", value(token)?)
                .header("X-Session-Credential", value("credential")?).json(&body).send().await?;
            assert_eq!(response.status().as_u16(), expected);
            samples.push(serde_json::json!({"schema":schema,"public":schema=="Error",
                "payload":response.json::<serde_json::Value>().await?}));
        }
        std::fs::write("responses.json", serde_json::to_vec_pretty(&samples)?)?;
        Ok(())
    }
}
