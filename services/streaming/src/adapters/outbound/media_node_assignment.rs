use crate::{
    application::ports::media_node_assignment::{
        MediaNodeAssignmentError, MediaNodeAssignmentPolicy, MediaNodeLoad,
    },
    domain::ids::StreamId,
};

/// Keeps current deployments pinned to their single configured MediaMTX node.
///
/// The PostgreSQL repository verifies that the configured node is enabled and
/// selects it while holding the platform capacity lock. Multi-node deployments
/// can replace this policy with load-aware placement without moving reservation
/// logic out of the transaction.
pub struct ConfiguredMediaNodeAssignment {
    media_node_id: Option<String>,
}

impl ConfiguredMediaNodeAssignment {
    pub fn new(media_node_id: Option<String>) -> Self {
        Self { media_node_id }
    }
}

impl MediaNodeAssignmentPolicy for ConfiguredMediaNodeAssignment {
    fn select_node(
        &self,
        _stream_id: &StreamId,
        available_nodes: &[MediaNodeLoad],
    ) -> Result<Option<String>, MediaNodeAssignmentError> {
        let Some(media_node_id) = self.media_node_id.as_ref() else {
            return Ok(None);
        };

        available_nodes
            .iter()
            .any(|node| node.media_node_id == *media_node_id)
            .then(|| Some(media_node_id.clone()))
            .ok_or(MediaNodeAssignmentError::NoEligibleNode)
    }
}
