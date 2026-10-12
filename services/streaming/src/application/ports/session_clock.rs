use std::time::Duration;

use crate::domain::ids::SessionId;

/// Exposes only monotonic elapsed durations for the current fenced session owner.
/// Missing timer state means ownership changed or the process lost its clock; callers must fail closed.
pub trait SessionMonotonicClock: Send + Sync {
    fn start_preparing(&self, session_id: &SessionId);
    /// Starts the timeline anchor while preserving the reconnect anchor. Keeping that anchor
    /// allows a database transaction that later rolls back to retry the same transition safely.
    fn mark_live(&self, session_id: &SessionId) -> bool;
    /// Anchors the loss time at the callback's durable receipt time, subtracting
    /// time spent waiting in the inbox so queue delay cannot extend the grace. Repeating the
    /// same source generation preserves its original anchor; a new generation starts a new grace.
    fn start_reconnect_grace(
        &self,
        session_id: &SessionId,
        source_generation: i64,
        elapsed_before_anchor: Duration,
    ) -> bool;
    fn preparing_elapsed(&self, session_id: &SessionId) -> Option<Duration>;
    fn reconnect_elapsed(&self, session_id: &SessionId) -> Option<Duration>;
    /// Monotonic elapsed time since the first verified playback frame. This anchor is
    /// preserved across reconnect grace and must not be reconstructed from wall-clock time.
    fn timeline_elapsed(&self, session_id: &SessionId) -> Option<Duration>;
    fn forget(&self, session_id: &SessionId);
}
