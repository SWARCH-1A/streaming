use std::{env, net::SocketAddr, str::FromStr, time::Duration};

use sqlx::postgres::{PgConnectOptions, PgSslMode};
use thiserror::Error;
use uuid::Uuid;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Environment {
    Development,
    Production,
}

pub struct AppConfig {
    pub instance_id: String,
    pub bind_addr: SocketAddr,
    pub private_bind_addr: SocketAddr,
    pub database_url: String,
    pub database_read_url: Option<String>,
    pub database_min_connections: u32,
    pub database_max_connections: u32,
    pub database_read_max_connections: u32,
    pub database_acquire_timeout: Duration,
    pub database_idle_timeout: Duration,
    pub database_max_lifetime: Duration,
    pub run_migrations: bool,
    pub environment: Environment,
    pub core_base_url: Option<String>,
    pub core_service_token: Option<String>,
    pub core_consumer_token: Option<String>,
    pub chat_base_url: Option<String>,
    pub chat_service_token: Option<String>,
    pub session_cookie_name: Option<String>,
    pub web_origin: Option<String>,
    pub rtmp_ingest_base_url: Option<String>,
    pub dependency_timeout: Duration,
    pub media_node_id: Option<String>,
    pub media_control_api_url: Option<String>,
    pub media_control_api_username: Option<String>,
    pub media_control_api_password: Option<String>,
    pub mediamtx_hls_internal_base_url: Option<String>,
    pub public_hls_base_url: Option<String>,
    pub media_adapter_service_token: Option<String>,
    pub viewer_lease_hmac_key: Option<String>,
}

impl AppConfig {
    pub fn from_env() -> Result<Self, ConfigError> {
        let environment = environment_from_env()?;

        let bind_addr = parse_or("STREAMING_BIND_ADDR", "0.0.0.0:8080")?;
        let private_bind_addr = parse_or("STREAMING_PRIVATE_BIND_ADDR", "0.0.0.0:8091")?;
        if bind_addr == private_bind_addr {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_PRIVATE_BIND_ADDR",
                reason: "private and public listeners must differ",
            });
        }
        let database_url = required("STREAMING_DATABASE_URL")?;
        let database_read_url = optional("STREAMING_DATABASE_READ_URL");
        let database_min_connections = parse_or("STREAMING_DB_MIN_CONNECTIONS", "0")?;
        let database_max_connections = parse_or("STREAMING_DB_MAX_CONNECTIONS", "16")?;
        let database_read_max_connections = parse_or("STREAMING_DB_READ_MAX_CONNECTIONS", "8")?;
        if database_max_connections == 0 {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_DB_MAX_CONNECTIONS",
                reason: "must be greater than zero",
            });
        }
        if database_read_max_connections == 0 {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_DB_READ_MAX_CONNECTIONS",
                reason: "must be greater than zero",
            });
        }
        if database_min_connections > database_max_connections {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_DB_MIN_CONNECTIONS",
                reason: "must not exceed STREAMING_DB_MAX_CONNECTIONS",
            });
        }
        let database_acquire_timeout =
            parse_positive_duration("STREAMING_DB_ACQUIRE_TIMEOUT_SECONDS", "3")?;
        let database_idle_timeout =
            parse_positive_duration("STREAMING_DB_IDLE_TIMEOUT_SECONDS", "600")?;
        let database_max_lifetime =
            parse_positive_duration("STREAMING_DB_MAX_LIFETIME_SECONDS", "1800")?;

        let migration_default = match environment {
            Environment::Development => "true",
            Environment::Production => "false",
        };
        let run_migrations = parse_or("STREAMING_RUN_MIGRATIONS", migration_default)?;
        let core_base_url = optional("STREAMING_CORE_BASE_URL");
        let core_service_token = optional("STREAMING_CORE_SERVICE_TOKEN");
        let core_consumer_token = optional("STREAMING_CORE_CONSUMER_TOKEN");
        let chat_base_url = optional("STREAMING_CHAT_BASE_URL");
        let chat_service_token = optional("STREAMING_CHAT_SERVICE_TOKEN");
        let session_cookie_name = optional("STREAMING_SESSION_COOKIE_NAME");
        let web_origin = optional("STREAMING_WEB_ORIGIN")
            .map(|origin| {
                normalize_web_origin(&origin).ok_or(ConfigError::InvalidValue {
                name: "STREAMING_WEB_ORIGIN",
                reason: "must be an HTTP(S) origin without credentials, path, query, or fragment",
            })
            })
            .transpose()?;
        let rtmp_ingest_base_url = optional("STREAMING_RTMP_INGEST_BASE_URL");
        let dependency_timeout =
            parse_positive_duration("STREAMING_DEPENDENCY_TIMEOUT_SECONDS", "3")?;
        if session_cookie_name
            .as_deref()
            .is_some_and(|name| !valid_cookie_name(name))
        {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_SESSION_COOKIE_NAME",
                reason: "must be a valid HTTP cookie token name",
            });
        }
        if rtmp_ingest_base_url
            .as_deref()
            .is_some_and(|url| !is_rtmp_base_url(url))
        {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_RTMP_INGEST_BASE_URL",
                reason: "must be an RTMP or RTMPS base URL without query or fragment",
            });
        }
        let media_control_api_url = optional("STREAMING_MEDIAMTX_CONTROL_API_URL");
        let media_control_api_username = optional("STREAMING_MEDIAMTX_CONTROL_API_USERNAME");
        let media_control_api_password = optional("STREAMING_MEDIAMTX_CONTROL_API_PASSWORD");
        let media_node_id = optional("STREAMING_MEDIAMTX_NODE_ID");
        let mediamtx_hls_internal_base_url = optional("STREAMING_MEDIAMTX_HLS_INTERNAL_BASE_URL");
        let public_hls_base_url = optional("STREAMING_PUBLIC_HLS_BASE_URL");
        let media_adapter_service_token = optional("STREAMING_MEDIA_ADAPTER_SERVICE_TOKEN");
        let viewer_lease_hmac_key = optional("STREAMING_VIEWER_LEASE_HMAC_KEY");

        if media_node_id
            .as_deref()
            .is_some_and(|node_id| !valid_media_node_id(node_id))
        {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_MEDIAMTX_NODE_ID",
                reason: "must be a 1-128 character identifier using letters, digits, '_' or '-'",
            });
        }
        if media_control_api_url.is_some() != media_node_id.is_some()
            || media_control_api_url.is_some() != mediamtx_hls_internal_base_url.is_some()
            || media_control_api_url.is_some() != public_hls_base_url.is_some()
            || media_control_api_url.is_some() != media_control_api_username.is_some()
            || media_control_api_url.is_some() != media_control_api_password.is_some()
        {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_MEDIAMTX_NODE_ID",
                reason: "node ID, Control API, credentials, internal HLS URL, and public HLS URL must be configured together",
            });
        }
        if media_control_api_username
            .as_deref()
            .is_some_and(|username| {
                username.contains(':') || username.chars().any(char::is_control)
            })
        {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_MEDIAMTX_CONTROL_API_USERNAME",
                reason: "must not contain a colon or control characters",
            });
        }
        if media_control_api_password
            .as_deref()
            .is_some_and(|password| password.chars().any(char::is_control))
        {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_MEDIAMTX_CONTROL_API_PASSWORD",
                reason: "must not contain control characters",
            });
        }
        if media_control_api_url
            .as_deref()
            .is_some_and(|url| !is_http_base_url(url))
        {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_MEDIAMTX_CONTROL_API_URL",
                reason: "must be an HTTP or HTTPS base URL without query or fragment",
            });
        }
        if mediamtx_hls_internal_base_url
            .as_deref()
            .is_some_and(|url| !is_http_base_url(url))
        {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_MEDIAMTX_HLS_INTERNAL_BASE_URL",
                reason: "must be an HTTP or HTTPS base URL without query or fragment",
            });
        }
        if public_hls_base_url
            .as_deref()
            .is_some_and(|url| !is_http_base_url(url))
        {
            return Err(ConfigError::InvalidValue {
                name: "STREAMING_PUBLIC_HLS_BASE_URL",
                reason: "must be an HTTP or HTTPS base URL without query or fragment",
            });
        }

        for (name, value) in [
            ("STREAMING_CORE_BASE_URL", core_base_url.as_deref()),
            ("STREAMING_CHAT_BASE_URL", chat_base_url.as_deref()),
        ] {
            if value.is_some_and(|v| !is_http_base_url(v)) {
                return Err(ConfigError::InvalidValue {
                    name,
                    reason: "must be an HTTP(S) base URL without credentials, query or fragment",
                });
            }
        }
        if environment == Environment::Production {
            if run_migrations {
                return Err(ConfigError::InvalidValue {
                    name: "STREAMING_RUN_MIGRATIONS",
                    reason: "must be false in production; run the one-shot `streaming-service migrate` command instead",
                });
            }
            require_bearer_token(
                "STREAMING_CORE_CONSUMER_TOKEN",
                core_consumer_token.as_deref(),
            )?;
            require_bearer_token(
                "STREAMING_CHAT_SERVICE_TOKEN",
                chat_service_token.as_deref(),
            )?;
            require_https("STREAMING_CHAT_BASE_URL", chat_base_url.as_deref())?;
            require_postgres_tls("STREAMING_DATABASE_URL", &database_url)?;
            if let Some(database_read_url) = database_read_url.as_deref() {
                require_postgres_tls("STREAMING_DATABASE_READ_URL", database_read_url)?;
            }
            require_https("STREAMING_CORE_BASE_URL", core_base_url.as_deref())?;
            require_non_empty(
                "STREAMING_SESSION_COOKIE_NAME",
                session_cookie_name.as_deref(),
            )?;
            require_non_empty("STREAMING_WEB_ORIGIN", web_origin.as_deref())?;
            require_https("STREAMING_WEB_ORIGIN", web_origin.as_deref())?;
            require_bearer_token(
                "STREAMING_CORE_SERVICE_TOKEN",
                core_service_token.as_deref(),
            )?;
            require_rtmp_endpoint(
                "STREAMING_RTMP_INGEST_BASE_URL",
                rtmp_ingest_base_url.as_deref(),
            )?;
            require_https(
                "STREAMING_MEDIAMTX_CONTROL_API_URL",
                media_control_api_url.as_deref(),
            )?;
            require_non_empty("STREAMING_MEDIAMTX_NODE_ID", media_node_id.as_deref())?;
            require_http_endpoint(
                "STREAMING_MEDIAMTX_HLS_INTERNAL_BASE_URL",
                mediamtx_hls_internal_base_url.as_deref(),
            )?;
            require_https(
                "STREAMING_PUBLIC_HLS_BASE_URL",
                public_hls_base_url.as_deref(),
            )?;
            require_non_empty(
                "STREAMING_CORE_SERVICE_TOKEN",
                core_service_token.as_deref(),
            )?;
            require_non_empty(
                "STREAMING_MEDIAMTX_CONTROL_API_USERNAME",
                media_control_api_username.as_deref(),
            )?;
            require_minimum_secret_length(
                "STREAMING_MEDIAMTX_CONTROL_API_PASSWORD",
                media_control_api_password.as_deref(),
            )?;
            require_bearer_token(
                "STREAMING_MEDIA_ADAPTER_SERVICE_TOKEN",
                media_adapter_service_token.as_deref(),
            )?;
            if viewer_lease_hmac_key
                .as_deref()
                .is_none_or(|key| key.len() < 32)
            {
                return Err(ConfigError::InvalidValue {
                    name: "STREAMING_VIEWER_LEASE_HMAC_KEY",
                    reason: "must contain at least 32 bytes",
                });
            }
        }

        Ok(Self {
            instance_id: format!("instance_{}", Uuid::now_v7()),
            bind_addr,
            private_bind_addr,
            database_url,
            database_read_url,
            database_min_connections,
            database_max_connections,
            database_read_max_connections,
            database_acquire_timeout,
            database_idle_timeout,
            database_max_lifetime,
            run_migrations,
            environment,
            core_base_url,
            core_service_token,
            core_consumer_token,
            chat_base_url,
            chat_service_token,
            session_cookie_name,
            web_origin,
            rtmp_ingest_base_url,
            dependency_timeout,
            media_node_id,
            media_control_api_url,
            media_control_api_username,
            media_control_api_password,
            mediamtx_hls_internal_base_url,
            public_hls_base_url,
            media_adapter_service_token,
            viewer_lease_hmac_key,
        })
    }
}

pub(crate) fn environment_from_env() -> Result<Environment, ConfigError> {
    match required("STREAMING_ENV")?.as_str() {
        "development" => Ok(Environment::Development),
        "production" => Ok(Environment::Production),
        other => Err(ConfigError::InvalidEnvironment(other.to_owned())),
    }
}

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("missing required configuration: {0}")]
    Missing(&'static str),
    #[error("invalid configuration value for {name}: {reason}")]
    InvalidValue {
        name: &'static str,
        reason: &'static str,
    },
    #[error("unsupported STREAMING_ENV value: {0}")]
    InvalidEnvironment(String),
    #[error("invalid value for {name}: {source}")]
    Parse {
        name: &'static str,
        #[source]
        source: Box<dyn std::error::Error + Send + Sync>,
    },
}

fn required(name: &'static str) -> Result<String, ConfigError> {
    env::var(name)
        .ok()
        .filter(|value| !value.trim().is_empty())
        .ok_or(ConfigError::Missing(name))
}

fn optional(name: &'static str) -> Option<String> {
    env::var(name).ok().filter(|value| !value.trim().is_empty())
}

fn parse_or<T>(name: &'static str, default: &str) -> Result<T, ConfigError>
where
    T: FromStr,
    T::Err: std::error::Error + Send + Sync + 'static,
{
    let value = env::var(name).unwrap_or_else(|_| default.to_owned());
    value.parse().map_err(|source| ConfigError::Parse {
        name,
        source: Box::new(source),
    })
}

fn parse_positive_duration(name: &'static str, default: &str) -> Result<Duration, ConfigError> {
    let seconds = parse_or::<u64>(name, default)?;
    if seconds == 0 {
        return Err(ConfigError::InvalidValue {
            name,
            reason: "must be greater than zero",
        });
    }
    Ok(Duration::from_secs(seconds))
}

fn require_non_empty(name: &'static str, value: Option<&str>) -> Result<(), ConfigError> {
    if value.is_some_and(|value| !value.trim().is_empty()) {
        Ok(())
    } else {
        Err(ConfigError::Missing(name))
    }
}

fn require_https(name: &'static str, value: Option<&str>) -> Result<(), ConfigError> {
    require_non_empty(name, value)?;
    if value.is_some_and(|value| is_absolute_base_url(value, "https://")) {
        Ok(())
    } else {
        Err(ConfigError::InvalidValue {
            name,
            reason: "must use HTTPS in production",
        })
    }
}

pub(crate) fn require_postgres_tls(name: &'static str, value: &str) -> Result<(), ConfigError> {
    let options = PgConnectOptions::from_str(value).map_err(|_| ConfigError::InvalidValue {
        name,
        reason: "must be a valid PostgreSQL URL with TLS required in production",
    })?;
    if matches!(
        options.get_ssl_mode(),
        PgSslMode::Require | PgSslMode::VerifyCa | PgSslMode::VerifyFull
    ) {
        Ok(())
    } else {
        Err(ConfigError::InvalidValue {
            name,
            reason: "must require PostgreSQL TLS in production (sslmode=require, verify-ca, or verify-full)",
        })
    }
}

fn require_http_endpoint(name: &'static str, value: Option<&str>) -> Result<(), ConfigError> {
    require_non_empty(name, value)?;
    if value.is_some_and(|value| {
        is_absolute_base_url(value, "https://") || is_absolute_base_url(value, "http://")
    }) {
        Ok(())
    } else {
        Err(ConfigError::InvalidValue {
            name,
            reason: "must use an HTTP or HTTPS URL",
        })
    }
}

fn require_rtmp_endpoint(name: &'static str, value: Option<&str>) -> Result<(), ConfigError> {
    require_non_empty(name, value)?;
    if value.is_some_and(is_rtmp_base_url) {
        Ok(())
    } else {
        Err(ConfigError::InvalidValue {
            name,
            reason: "must be an RTMP or RTMPS base URL without query or fragment",
        })
    }
}

fn is_rtmp_base_url(value: &str) -> bool {
    (is_absolute_base_url(value, "rtmp://") || is_absolute_base_url(value, "rtmps://"))
        && !value.contains('?')
        && !value.contains('#')
        && !value.contains('\\')
        && !value.split_once("://").is_some_and(|(_, remainder)| {
            remainder.split('/').any(|part| part == "." || part == "..")
        })
}

fn is_absolute_base_url(value: &str, scheme: &str) -> bool {
    let Some(expected_scheme) = scheme.strip_suffix("://") else {
        return false;
    };
    let Some(remainder) = value.strip_prefix(scheme) else {
        return false;
    };
    let authority = remainder.split('/').next().unwrap_or_default();

    if authority.is_empty()
        || authority.starts_with(':')
        || authority.contains('@')
        || authority.bytes().any(|byte| byte.is_ascii_whitespace())
    {
        return false;
    }

    reqwest::Url::parse(value).is_ok_and(|url| {
        url.scheme() == expected_scheme
            && url.host_str().is_some()
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none()
    })
}

fn is_http_base_url(value: &str) -> bool {
    (is_absolute_base_url(value, "https://") || is_absolute_base_url(value, "http://"))
        && !value.contains('\\')
        && !value.split_once("://").is_some_and(|(_, remainder)| {
            remainder.split('/').any(|part| part == "." || part == "..")
        })
}

fn normalize_web_origin(value: &str) -> Option<String> {
    let url = reqwest::Url::parse(value).ok()?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || value.contains('@')
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return None;
    }

    Some(url.origin().ascii_serialization())
}

fn valid_media_node_id(node_id: &str) -> bool {
    !node_id.is_empty()
        && node_id.len() <= 128
        && node_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}

fn valid_cookie_name(name: &str) -> bool {
    !name.is_empty()
        && name.bytes().all(|byte| {
            byte.is_ascii_alphanumeric()
                || matches!(
                    byte,
                    b'!' | b'#'
                        ..=b'\'' | b'*' | b'+' | b'-' | b'.' | b'^' | b'_' | b'`' | b'|' | b'~'
                )
        })
}

fn require_minimum_secret_length(
    name: &'static str,
    value: Option<&str>,
) -> Result<(), ConfigError> {
    if value.is_some_and(|value| value.len() >= 32) {
        Ok(())
    } else {
        Err(ConfigError::InvalidValue {
            name,
            reason: "must contain at least 32 bytes",
        })
    }
}

fn require_bearer_token(name: &'static str, value: Option<&str>) -> Result<(), ConfigError> {
    require_non_empty(name, value)?;
    if value.is_some_and(|value| value.len() >= 32 && is_bearer_token(value)) {
        Ok(())
    } else {
        Err(ConfigError::InvalidValue {
            name,
            reason: "must contain at least 32 bytes and use valid Bearer token characters",
        })
    }
}

fn is_bearer_token(value: &str) -> bool {
    let bytes = value.as_bytes();
    let padding_start = bytes
        .iter()
        .position(|byte| *byte == b'=')
        .unwrap_or(bytes.len());
    padding_start > 0
        && bytes[..padding_start].iter().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(*byte, b'-' | b'.' | b'_' | b'~' | b'+' | b'/')
        })
        && bytes[padding_start..].iter().all(|byte| *byte == b'=')
}
