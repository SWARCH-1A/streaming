use std::{env, net::SocketAddr};

pub(super) struct MediaConfig {
    pub database_url: String,
    pub db_max_connections: u32,
    pub run_migrations: bool,
    pub bind_addr: SocketAddr,
    pub hls_bind_addr: SocketAddr,
    pub streaming_url: String,
    pub streaming_public_url: String,
    pub streaming_token: String,
    pub control_url: String,
    pub control_user: String,
    pub control_password: String,
    pub hls_url: String,
    pub hls_secret: String,
    pub auth_header: String,
    pub max_open_dead_letters: i64,
}

impl MediaConfig {
    pub fn from_env() -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let production = match required("STREAMING_ENV")?.as_str() {
            "production" => true,
            "development" => false,
            _ => return Err("invalid STREAMING_ENV".into()),
        };
        let database_url = required("MEDIA_DATABASE_URL")?;
        if production {
            crate::config::require_postgres_tls("MEDIA_DATABASE_URL", &database_url)?;
        }
        let streaming_url = http_url("MEDIA_STREAMING_URL", production, true)?;
        let streaming_public_url = http_url("MEDIA_STREAMING_PUBLIC_URL", production, true)?;
        let control_url = http_url("MEDIA_CONTROL_URL", production, false)?;
        let hls_url = http_url("MEDIA_HLS_URL", false, false)?;
        let streaming_token = secret("MEDIA_STREAMING_TOKEN")?;
        let control_user = required("MEDIA_CONTROL_USER")?;
        let control_password = secret("MEDIA_CONTROL_PASSWORD")?;
        let hls_secret = secret("MEDIA_HLS_SECRET")?;
        let auth_token = secret("MEDIA_AUTH_TOKEN")?;
        if !auth_token
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
        {
            return Err("MEDIA_AUTH_TOKEN must use URL-safe letters, digits, '-' or '_'".into());
        }
        let request = reqwest::Client::new()
            .get("http://localhost/")
            .basic_auth("mediamtx", Some(auth_token))
            .build()?;
        let auth_header = request
            .headers()
            .get(reqwest::header::AUTHORIZATION)
            .ok_or("cannot configure authentication")?
            .to_str()?
            .to_owned();
        let db_max_connections = value("MEDIA_DB_MAX_CONNECTIONS", "8").parse()?;
        let max_open_dead_letters = value("MEDIA_MAX_OPEN_DEAD_LETTERS", "10000").parse()?;
        if db_max_connections == 0 || max_open_dead_letters <= 0 {
            return Err("database pool and dead-letter capacity must be positive".into());
        }
        let run_migrations = value("MEDIA_RUN_MIGRATIONS", "false").parse()?;
        if production && run_migrations {
            return Err("production migrations must run as a one-shot command".into());
        }
        Ok(Self {
            database_url,
            db_max_connections,
            run_migrations,
            bind_addr: value("MEDIA_BIND_ADDR", "0.0.0.0:8090").parse()?,
            hls_bind_addr: value("MEDIA_HLS_BIND_ADDR", "0.0.0.0:8888").parse()?,
            streaming_url,
            streaming_public_url,
            streaming_token,
            control_url,
            control_user,
            control_password,
            hls_url,
            hls_secret,
            auth_header,
            max_open_dead_letters,
        })
    }
}

fn value(name: &str, default: &str) -> String {
    env::var(name).unwrap_or_else(|_| default.to_owned())
}
fn required(name: &str) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
    env::var(name)
        .ok()
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| format!("missing {name}").into())
}
fn secret(name: &str) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
    let value = required(name)?;
    if value.len() < 32 || value.chars().any(char::is_control) {
        return Err(format!("{name} must contain at least32 bytes and no controls").into());
    }
    Ok(value)
}
fn http_url(
    name: &str,
    https: bool,
    allow_loopback: bool,
) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
    validate_http_url(name, &required(name)?, https, allow_loopback)
}

fn validate_http_url(
    name: &str,
    value: &str,
    https: bool,
    allow_loopback: bool,
) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
    let url = reqwest::Url::parse(value)?;
    if !matches!(url.scheme(), "http" | "https")
        || (https
            && url.scheme() != "https"
            && !(allow_loopback
                && matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "[::1]"))))
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(format!("invalid {name}").into());
    }
    Ok(value.trim_end_matches('/').to_owned())
}

#[cfg(test)]
mod tests {
    use super::validate_http_url;

    #[test]
    fn production_http_is_only_allowed_for_internal_loopback_contracts() {
        for url in [
            "http://127.0.0.1:8091",
            "http://[::1]:8091",
            "http://localhost:8091",
        ] {
            assert!(validate_http_url("test", url, true, true).is_ok());
            assert!(validate_http_url("test", url, true, false).is_err());
        }
        for url in [
            "http://streaming:8091",
            "http://127.0.0.1.example:8091",
            "http://localhost@remote:8091",
            "http://127.0.0.1:8091?token=x",
        ] {
            assert!(validate_http_url("test", url, true, true).is_err());
        }
        assert!(validate_http_url("test", "https://streaming:8091", true, true).is_ok());
    }
}
