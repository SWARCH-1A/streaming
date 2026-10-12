use super::{endpoint, valid_identifier};
use crate::application::ports::{
    channels::{ChannelOwner, ChannelsError, ChannelsGateway},
    identity::{IdentityError, IdentityGateway, IdentityPrincipal, SessionCredential},
    owner_context::{
        OwnerContext, OwnerContextError, OwnerContextGateway, OwnerContextRequest, OwnerOperation,
    },
    taxonomy::{TaxonomyError, TaxonomyGateway, TaxonomyValue},
};
use reqwest::{Client, RequestBuilder, StatusCode, header::HeaderValue};
use serde::{Deserialize, de::DeserializeOwned};
use serde_json::json;
use std::{
    future::Future,
    time::{Duration, Instant},
};
use time::OffsetDateTime;

pub struct CoreHttpGateway {
    client: Client,
    base_url: Option<String>,
    service_token: Option<String>,
    cookie_name: Option<String>,
}
impl CoreHttpGateway {
    pub fn new(
        client: Client,
        base_url: Option<String>,
        service_token: Option<String>,
        cookie_name: Option<String>,
    ) -> Self {
        Self {
            client,
            base_url,
            service_token,
            cookie_name,
        }
    }
    fn private_request(&self, path: &str) -> Result<RequestBuilder, OwnerContextError> {
        let url = endpoint(self.base_url.as_deref(), path).ok_or(OwnerContextError::Unavailable)?;
        let mut token = HeaderValue::from_str(
            self.service_token
                .as_deref()
                .ok_or(OwnerContextError::Unavailable)?,
        )
        .map_err(|_| OwnerContextError::Unavailable)?;
        token.set_sensitive(true);
        Ok(self
            .client
            .post(url)
            .header("X-Service-Name", "streaming")
            .header("X-Service-Token", token))
    }
}

// Bound the complete body, including chunked responses. Transport errors never expose URLs.
async fn decode<T: DeserializeOwned>(mut response: reqwest::Response) -> Result<T, ()> {
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| ())? {
        if body.len().saturating_add(chunk.len()) > 65536 {
            return Err(());
        }
        body.extend_from_slice(&chunk);
    }
    serde_json::from_slice(&body).map_err(|_| ())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ContextResponse {
    command_id: uuid::Uuid,
    operation: OwnerOperation,
    user_id: String,
    channel_id: String,
    authorized_at_utc: String,
    catalog_version: i64,
    category: Option<CatalogValue>,
    tags: Option<Vec<CatalogValue>>,
}
#[derive(Deserialize)]
struct CatalogValue {
    id: String,
    kind: String,
    name: String,
    active: bool,
}
impl CatalogValue {
    fn valid(&self, kind: &str, active: bool) -> bool {
        self.kind == kind
            && valid_identifier(&self.id)
            && !self.name.trim().is_empty()
            && self.name.len() <= 1024
            && !self.name.chars().any(char::is_control)
            && (!active || self.active)
    }
    fn public(self) -> TaxonomyValue {
        TaxonomyValue {
            id: self.id,
            name: self.name,
            active: self.active,
        }
    }
}
impl OwnerContextGateway for CoreHttpGateway {
    fn authorize(
        &self,
        credential: SessionCredential,
        request: OwnerContextRequest,
    ) -> impl Future<Output = Result<OwnerContext, OwnerContextError>> + Send {
        async move {
            let expires_at = Instant::now() + Duration::from_secs(1);
            let mut session = HeaderValue::from_str(credential.expose_to_identity_adapter())
                .map_err(|_| OwnerContextError::Inactive)?;
            session.set_sensitive(true);
            let result = tokio::time::timeout_at(expires_at.into(), async {
                let response = self
                    .private_request("internal/core/streaming/owner-context")?
                    .header("X-Session-Credential", session)
                    .json(&request)
                    .send()
                    .await
                    .map_err(|_| OwnerContextError::Unavailable)?;
                match response.status() {
                    StatusCode::UNAUTHORIZED => return Err(OwnerContextError::Inactive),
                    StatusCode::FORBIDDEN => return Err(OwnerContextError::Forbidden),
                    StatusCode::NOT_FOUND => return Err(OwnerContextError::NotFound),
                    StatusCode::UNPROCESSABLE_ENTITY => {
                        return Err(OwnerContextError::InvalidCatalog);
                    }
                    s if !s.is_success() => return Err(OwnerContextError::Unavailable),
                    _ => {}
                }
                let context: ContextResponse = decode(response)
                    .await
                    .map_err(|_| OwnerContextError::Unavailable)?;
                if context.command_id != request.command_id
                    || context.operation != request.operation
                    || context.channel_id != request.channel_id
                    || !valid_identifier(&context.user_id)
                    || context.catalog_version < 0
                    || OffsetDateTime::parse(
                        &context.authorized_at_utc,
                        &time::format_description::well_known::Rfc3339,
                    )
                    .is_err()
                {
                    return Err(OwnerContextError::Unavailable);
                }
                match (&request.category_id, &context.category) {
                    (Some(id), Some(value)) if value.id == *id && value.valid("CATEGORY", true) => {
                    }
                    (None, None) => {}
                    _ => return Err(OwnerContextError::Unavailable),
                }
                match (&request.tag_ids, &context.tags) {
                    (Some(ids), Some(values))
                        if ids.len() == values.len()
                            && values.iter().all(|v| v.valid("TAG", true)) =>
                    {
                        let expected: std::collections::HashSet<_> = ids.iter().collect();
                        let returned: std::collections::HashSet<_> =
                            values.iter().map(|v| &v.id).collect();
                        if expected != returned || returned.len() != values.len() {
                            return Err(OwnerContextError::Unavailable);
                        }
                    }
                    (None, None) => {}
                    _ => return Err(OwnerContextError::Unavailable),
                }
                let tags = context.tags.map(|values| {
                    let mut values = values
                        .into_iter()
                        .map(CatalogValue::public)
                        .collect::<Vec<_>>();
                    if let Some(ids) = &request.tag_ids {
                        values.sort_by_key(|v| ids.iter().position(|id| id == &v.id));
                    }
                    values
                });
                Ok(OwnerContext {
                    user_id: context.user_id,
                    category: context.category.map(CatalogValue::public),
                    tags,
                    expires_at,
                })
            })
            .await
            .map_err(|_| OwnerContextError::Unavailable)??;
            if Instant::now() >= expires_at {
                return Err(OwnerContextError::Unavailable);
            }
            Ok(result)
        }
    }
}
impl IdentityGateway for CoreHttpGateway {
    fn introspect(
        &self,
        credential: SessionCredential,
    ) -> impl Future<Output = Result<IdentityPrincipal, IdentityError>> + Send {
        async move {
            // Protected reads use Core's authenticated profile read; no synthetic owner operation.
            let cookie_name = self
                .cookie_name
                .as_deref()
                .ok_or(IdentityError::Unavailable)?;
            let value = credential.expose_to_identity_adapter();
            if value
                .bytes()
                .any(|b| b <= 32 || b >= 127 || matches!(b, b';' | b'"' | b',' | b'\\'))
            {
                return Err(IdentityError::Inactive);
            }
            let mut cookie = HeaderValue::from_str(&format!("{cookie_name}={value}"))
                .map_err(|_| IdentityError::Inactive)?;
            cookie.set_sensitive(true);
            let url = endpoint(self.base_url.as_deref(), "api/profile/me")
                .ok_or(IdentityError::Unavailable)?;
            let response = self
                .client
                .get(url)
                .header(reqwest::header::COOKIE, cookie)
                .send()
                .await
                .map_err(|_| IdentityError::Unavailable)?;
            if response.status() == StatusCode::UNAUTHORIZED {
                return Err(IdentityError::Inactive);
            }
            if !response.status().is_success() {
                return Err(IdentityError::Unavailable);
            }
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct Profile {
                user_id: String,
            }
            let profile: Profile = decode(response)
                .await
                .map_err(|_| IdentityError::InvalidResponse)?;
            if !valid_identifier(&profile.user_id) {
                return Err(IdentityError::InvalidResponse);
            }
            Ok(IdentityPrincipal {
                user_id: profile.user_id,
            })
        }
    }
}
impl ChannelsGateway for CoreHttpGateway {
    fn find_owner_channel(
        &self,
        owner_user_id: String,
    ) -> impl Future<Output = Result<Option<ChannelOwner>, ChannelsError>> + Send {
        async move {
            if !valid_identifier(&owner_user_id) {
                return Err(ChannelsError::InvalidResponse);
            }
            let url = endpoint(
                self.base_url.as_deref(),
                &format!("api/channels/by-owner/{owner_user_id}"),
            )
            .ok_or(ChannelsError::Unavailable)?;
            let response = self
                .client
                .get(url)
                .send()
                .await
                .map_err(|_| ChannelsError::Unavailable)?;
            if response.status() == StatusCode::NOT_FOUND {
                return Ok(None);
            }
            if !response.status().is_success() {
                return Err(ChannelsError::Unavailable);
            }
            #[derive(Deserialize)]
            struct Bootstrap {
                channel: Channel,
            }
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct Channel {
                channel_id: String,
            }
            let body: Bootstrap = decode(response)
                .await
                .map_err(|_| ChannelsError::InvalidResponse)?;
            if !valid_identifier(&body.channel.channel_id) {
                return Err(ChannelsError::InvalidResponse);
            }
            Ok(Some(ChannelOwner {
                channel_id: body.channel.channel_id,
                owner_user_id,
            }))
        }
    }
}
impl TaxonomyGateway for CoreHttpGateway {
    fn resolve_values(
        &self,
        ids: Vec<String>,
    ) -> impl Future<Output = Result<Vec<TaxonomyValue>, TaxonomyError>> + Send {
        async move {
            if ids.is_empty() || ids.len() > 50 || ids.iter().any(|id| !valid_identifier(id)) {
                return Err(TaxonomyError::InvalidResponse);
            }
            let response = self
                .private_request("internal/core/streaming/catalog-values")
                .map_err(|_| TaxonomyError::Unavailable)?
                .json(&json!({"ids":ids}))
                .send()
                .await
                .map_err(|_| TaxonomyError::Unavailable)?;
            if !response.status().is_success() {
                return Err(TaxonomyError::Unavailable);
            }
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct Catalog {
                catalog_version: i64,
                values: Vec<CatalogValue>,
            }
            let body: Catalog = decode(response)
                .await
                .map_err(|_| TaxonomyError::InvalidResponse)?;
            if body.catalog_version < 0 || body.values.len() != ids.len() {
                return Err(TaxonomyError::InvalidResponse);
            }
            let mut result = Vec::with_capacity(ids.len());
            let mut values = body.values;
            for (index, id) in ids.iter().enumerate() {
                let position = values
                    .iter()
                    .position(|v| &v.id == id)
                    .ok_or(TaxonomyError::InvalidResponse)?;
                let value = values.remove(position);
                if !value.valid(if index == 0 { "CATEGORY" } else { "TAG" }, false) {
                    return Err(TaxonomyError::InvalidResponse);
                }
                result.push(value.public());
            }
            Ok(result)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Json, Router, routing::post};
    type Error = Box<dyn std::error::Error + Send + Sync>;
    async fn fixture(
        kind: &'static str,
        wrong_id: bool,
    ) -> Result<(CoreHttpGateway, tokio::task::JoinHandle<()>), Error> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let router=Router::new().route("/internal/core/streaming/owner-context",post(move |headers:axum::http::HeaderMap,Json(request):Json<serde_json::Value>| async move {
            if headers.get("X-Service-Name").and_then(|v| v.to_str().ok())!=Some("streaming") || !headers.contains_key("X-Session-Credential") {
                return (axum::http::StatusCode::UNAUTHORIZED,Json(json!({})));
            }
            let command=if wrong_id { json!(uuid::Uuid::nil()) } else { request["commandId"].clone() };
            (axum::http::StatusCode::OK,Json(json!({"commandId":command,"operation":request["operation"],"channelId":request["channelId"],"userId":"usr_fixture","authorizedAtUtc":"2026-10-03T20:00:00Z","catalogVersion":1,
                "category":{"id":"cat_fixture","kind":kind,"name":"Fixture category","active":true},"tags":[]})))
        }));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let task = tokio::spawn(async move {
            let _ = axum::serve(listener, router).await;
        });
        let gateway = CoreHttpGateway::new(
            super::super::build_client(Duration::from_secs(1))?,
            Some(format!("http://{address}")),
            Some("fixture_service_token_32characters".into()),
            Some("stream_session".into()),
        );
        Ok((gateway, task))
    }
    fn request() -> OwnerContextRequest {
        OwnerContextRequest {
            command_id: uuid::Uuid::new_v4(),
            operation: OwnerOperation::CreateConfig,
            channel_id: "chn_fixture".into(),
            category_id: Some("cat_fixture".into()),
            tag_ids: Some(vec![]),
        }
    }
    #[tokio::test]
    async fn context_is_bound_to_command_and_typed_catalog() -> Result<(), Error> {
        for (kind, wrong_id, valid) in [
            ("CATEGORY", false, true),
            ("TAG", false, false),
            ("CATEGORY", true, false),
        ] {
            let (gateway, task) = fixture(kind, wrong_id).await?;
            let credential =
                SessionCredential::new("fixture_cookie".into()).ok_or("fixture credential")?;
            let result = gateway.authorize(credential, request()).await;
            assert_eq!(result.is_ok(), valid);
            if let Ok(context) = result {
                assert_eq!(context.user_id, "usr_fixture");
                assert!(Instant::now() < context.expires_at);
            }
            task.abort();
            let _ = task.await;
        }
        Ok(())
    }
}
