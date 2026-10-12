use std::time::Duration;
use time::{OffsetDateTime, PrimitiveDateTime};

pub(crate) fn retry_after(headers: &reqwest::header::HeaderMap) -> Option<Duration> {
    let value = headers.get(reqwest::header::RETRY_AFTER)?.to_str().ok()?;
    if let Ok(seconds) = value.parse::<u64>() {
        return Some(Duration::from_secs(seconds));
    }
    let format = time::format_description::parse_borrowed::<2>(
        "[weekday repr:short], [day] [month repr:short] [year] [hour]:[minute]:[second] GMT",
    )
    .ok()?;
    let date = PrimitiveDateTime::parse(value, &format).ok()?.assume_utc();
    let seconds = (date - OffsetDateTime::now_utc()).whole_seconds().max(0);
    Some(Duration::from_secs(seconds as u64))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn supports_seconds_and_http_date() {
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            reqwest::header::RETRY_AFTER,
            reqwest::header::HeaderValue::from_static("12"),
        );
        assert_eq!(retry_after(&headers), Some(Duration::from_secs(12)));
        headers.insert(
            reqwest::header::RETRY_AFTER,
            reqwest::header::HeaderValue::from_static("Sun, 06 Nov 1994 08:49:37 GMT"),
        );
        assert_eq!(retry_after(&headers), Some(Duration::ZERO));
        headers.insert(
            reqwest::header::RETRY_AFTER,
            reqwest::header::HeaderValue::from_static("-1"),
        );
        assert_eq!(retry_after(&headers), None);
    }
}
