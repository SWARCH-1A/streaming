use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct StreamId(String);

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct SessionId(String);

impl StreamId {
    pub fn new() -> Self {
        Self(format!("str_{}", Uuid::now_v7()))
    }

    pub fn parse(value: String) -> Option<Self> {
        has_uuid_suffix(&value, "str_").then_some(Self(value))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl Default for StreamId {
    fn default() -> Self {
        Self::new()
    }
}

impl SessionId {
    pub fn new() -> Self {
        Self(format!("ses_{}", Uuid::now_v7()))
    }

    pub fn parse(value: String) -> Option<Self> {
        has_uuid_suffix(&value, "ses_").then_some(Self(value))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl Default for SessionId {
    fn default() -> Self {
        Self::new()
    }
}

fn has_uuid_suffix(value: &str, prefix: &str) -> bool {
    value
        .strip_prefix(prefix)
        .is_some_and(|suffix| Uuid::parse_str(suffix).is_ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_ids_use_their_type_prefix_and_parse_back() {
        let stream_id = StreamId::new();
        let session_id = SessionId::new();

        assert!(stream_id.as_str().starts_with("str_"));
        assert!(session_id.as_str().starts_with("ses_"));
        assert_eq!(
            StreamId::parse(stream_id.as_str().to_owned()),
            Some(stream_id)
        );
        assert_eq!(
            SessionId::parse(session_id.as_str().to_owned()),
            Some(session_id)
        );
    }

    #[test]
    fn parser_rejects_wrong_prefixes_and_malformed_uuids() {
        assert!(
            StreamId::parse(String::from("ses_550e8400-e29b-41d4-a716-446655440000")).is_none()
        );
        assert!(StreamId::parse(String::from("str_not-a-uuid")).is_none());
        assert!(StreamId::parse(String::from("str_")).is_none());
        assert!(
            SessionId::parse(String::from("str_550e8400-e29b-41d4-a716-446655440000")).is_none()
        );
        assert!(SessionId::parse(String::from("ses_not-a-uuid")).is_none());
        assert!(SessionId::parse(String::from("ses_")).is_none());
    }

    #[test]
    fn parser_accepts_valid_prefixed_uuids() {
        assert!(
            StreamId::parse(String::from("str_550e8400-e29b-41d4-a716-446655440000")).is_some()
        );
        assert!(
            SessionId::parse(String::from("ses_550e8400-e29b-41d4-a716-446655440000")).is_some()
        );
    }
}
