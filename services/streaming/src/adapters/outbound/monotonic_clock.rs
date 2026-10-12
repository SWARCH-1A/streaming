use std::{
    collections::HashMap,
    sync::{
        Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use tokio::time::Instant;

use crate::{application::ports::session_clock::SessionMonotonicClock, domain::ids::SessionId};

#[derive(Default)]
pub struct MonotonicSessionClock {
    invalidated: AtomicBool,
    sessions: Mutex<HashMap<SessionId, SessionTimers>>,
}

#[derive(Clone, Copy)]
struct SessionTimers {
    preparing_started: Instant,
    reconnect_started: Option<Instant>,
    reconnect_source_generation: Option<i64>,
    timeline_started: Option<Instant>,
}

impl MonotonicSessionClock {
    pub fn is_available(&self) -> bool {
        !self.invalidated.load(Ordering::Acquire)
    }
    pub fn invalidate(&self) {
        self.invalidated.store(true, Ordering::Release);
        if let Ok(mut sessions) = self.sessions.lock() {
            sessions.clear();
        }
    }
}
impl SessionMonotonicClock for MonotonicSessionClock {
    fn start_preparing(&self, session_id: &SessionId) {
        if let Ok(mut sessions) = self.sessions.lock() {
            if !self.is_available() {
                return;
            }
            sessions.insert(
                session_id.clone(),
                SessionTimers {
                    preparing_started: Instant::now(),
                    reconnect_started: None,
                    reconnect_source_generation: None,
                    timeline_started: None,
                },
            );
        }
    }

    fn mark_live(&self, session_id: &SessionId) -> bool {
        if !self.is_available() {
            return false;
        }
        self.sessions
            .lock()
            .ok()
            .and_then(|mut sessions| {
                let timers = sessions.get_mut(session_id)?;
                timers.timeline_started.get_or_insert_with(Instant::now);
                Some(())
            })
            .is_some()
    }

    fn start_reconnect_grace(
        &self,
        session_id: &SessionId,
        source_generation: i64,
        elapsed_before_anchor: Duration,
    ) -> bool {
        if !self.is_available() {
            return false;
        }
        self.sessions
            .lock()
            .ok()
            .and_then(|mut sessions| {
                let timers = sessions.get_mut(session_id)?;
                if timers.reconnect_source_generation == Some(source_generation) {
                    return Some(());
                }
                let started = Instant::now().checked_sub(elapsed_before_anchor)?;
                timers.reconnect_started = Some(started);
                timers.reconnect_source_generation = Some(source_generation);
                Some(())
            })
            .is_some()
    }

    fn preparing_elapsed(&self, session_id: &SessionId) -> Option<Duration> {
        let sessions = self.sessions.lock().ok()?;
        if !self.is_available() {
            return None;
        }
        Some(sessions.get(session_id)?.preparing_started.elapsed())
    }

    fn reconnect_elapsed(&self, session_id: &SessionId) -> Option<Duration> {
        let sessions = self.sessions.lock().ok()?;
        if !self.is_available() {
            return None;
        }
        Some(sessions.get(session_id)?.reconnect_started?.elapsed())
    }

    fn timeline_elapsed(&self, session_id: &SessionId) -> Option<Duration> {
        let sessions = self.sessions.lock().ok()?;
        if !self.is_available() {
            return None;
        }
        Some(sessions.get(session_id)?.timeline_started?.elapsed())
    }

    fn forget(&self, session_id: &SessionId) {
        if let Ok(mut sessions) = self.sessions.lock() {
            sessions.remove(session_id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loss_of_control_invalidates_existing_and_new_anchors() {
        let clock = MonotonicSessionClock::default();
        let id = SessionId::new();
        clock.start_preparing(&id);
        assert!(clock.mark_live(&id));
        clock.invalidate();
        assert!(!clock.is_available());
        assert_eq!(clock.timeline_elapsed(&id), None);
        clock.start_preparing(&id);
        assert_eq!(clock.preparing_elapsed(&id), None);
        assert!(!clock.mark_live(&id));
        assert!(!clock.start_reconnect_grace(&id, 1, Duration::ZERO));
    }
    #[test]
    fn clock_starts_preparing_and_live_anchors_only_for_known_sessions() {
        let clock = MonotonicSessionClock::default();
        let session_id = SessionId::new();

        assert_eq!(clock.preparing_elapsed(&session_id), None);
        assert!(!clock.mark_live(&session_id));
        assert!(!clock.start_reconnect_grace(&session_id, 1, Duration::ZERO));

        clock.start_preparing(&session_id);
        let first_elapsed = clock.preparing_elapsed(&session_id);
        let later_elapsed = clock.preparing_elapsed(&session_id);
        assert!(first_elapsed.is_some());
        assert!(later_elapsed >= first_elapsed);
        assert!(clock.mark_live(&session_id));
        assert!(clock.timeline_elapsed(&session_id).is_some());
    }

    #[test]
    fn repeated_live_mark_keeps_the_existing_timeline_anchor() {
        let clock = MonotonicSessionClock::default();
        let session_id = SessionId::new();
        clock.start_preparing(&session_id);

        assert!(clock.mark_live(&session_id));
        let first_elapsed = clock.timeline_elapsed(&session_id);
        assert!(clock.mark_live(&session_id));
        let later_elapsed = clock.timeline_elapsed(&session_id);

        assert!(later_elapsed >= first_elapsed);
    }

    #[test]
    fn reconnect_retries_preserve_the_anchor_and_a_new_generation_replaces_it() {
        let clock = MonotonicSessionClock::default();
        let session_id = SessionId::new();
        clock.start_preparing(&session_id);
        let simulated_queue_delay = Duration::from_millis(1);

        assert!(clock.start_reconnect_grace(&session_id, 3, simulated_queue_delay));
        let first_elapsed = clock.reconnect_elapsed(&session_id);
        assert!(first_elapsed >= Some(simulated_queue_delay));
        assert!(clock.start_reconnect_grace(&session_id, 3, Duration::ZERO));
        let retried_elapsed = clock.reconnect_elapsed(&session_id);
        assert!(retried_elapsed >= Some(simulated_queue_delay));

        assert!(clock.start_reconnect_grace(&session_id, 4, Duration::ZERO));
        assert!(clock.reconnect_elapsed(&session_id).is_some());
    }

    #[test]
    fn timeline_anchor_survives_reconnect_and_forget_removes_all_anchors() {
        let clock = MonotonicSessionClock::default();
        let session_id = SessionId::new();
        clock.start_preparing(&session_id);
        assert!(clock.mark_live(&session_id));
        assert!(clock.start_reconnect_grace(&session_id, 2, Duration::from_millis(1)));

        assert!(clock.timeline_elapsed(&session_id).is_some());
        assert!(clock.reconnect_elapsed(&session_id).is_some());
        clock.forget(&session_id);
        assert_eq!(clock.preparing_elapsed(&session_id), None);
        assert_eq!(clock.timeline_elapsed(&session_id), None);
        assert_eq!(clock.reconnect_elapsed(&session_id), None);
    }
}
