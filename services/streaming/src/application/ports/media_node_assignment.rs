use crate::domain::ids::StreamId;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MediaNodeLoad {
    pub media_node_id: String,
    pub active_sessions: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum MediaNodeAssignmentError {
    #[error("no eligible media node is available")]
    NoEligibleNode,
}

/// Selects a media node from a consistent snapshot of active session counts.
///
/// Implementations must be deterministic for the supplied stream and snapshot.
/// The caller serializes new session reservations in PostgreSQL and persists the
/// selected node in the same transaction, so this policy must not reserve nodes
/// through an independent side channel.
pub trait MediaNodeAssignmentPolicy: Send + Sync {
    fn select_node(
        &self,
        stream_id: &StreamId,
        available_nodes: &[MediaNodeLoad],
    ) -> Result<Option<String>, MediaNodeAssignmentError>;
}
