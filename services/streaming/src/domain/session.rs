use std::time::Duration;

use time::OffsetDateTime;

use super::ids::{SessionId, StreamId};

pub const PREPARING_TIMEOUT: Duration = Duration::from_secs(30);
pub const RECONNECT_GRACE: Duration = Duration::from_secs(30);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionStatus {
    Preparing,
    Live,
    ReconnectGrace,
    Ended,
}

impl SessionStatus {
    pub fn as_public_str(self) -> &'static str {
        match self {
            Self::ReconnectGrace => "LIVE",
            _ => self.as_db_str(),
        }
    }
    pub fn as_db_str(self) -> &'static str {
        match self {
            Self::Preparing => "PREPARING",
            Self::Live => "LIVE",
            Self::ReconnectGrace => "RECONNECT_GRACE",
            Self::Ended => "ENDED",
        }
    }

    pub fn is_active(self) -> bool {
        self != Self::Ended
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Availability {
    Offline,
    Reconnecting,
    Playable,
}

impl Availability {
    pub fn as_db_str(self) -> &'static str {
        match self {
            Self::Offline => "OFFLINE",
            Self::Reconnecting => "RECONNECTING",
            Self::Playable => "PLAYABLE",
        }
    }
}

#[derive(Clone, Debug)]
pub struct StreamSession {
    pub session_id: SessionId,
    pub stream_id: StreamId,
    pub channel_id: String,
    pub stream_generation: i64,
    pub source_generation: i64,
    pub status: SessionStatus,
    pub availability: Availability,
    pub session_version: i64,
    pub preparing_deadline_at: OffsetDateTime,
    pub grace_deadline_at: Option<OffsetDateTime>,
    pub started_at: Option<OffsetDateTime>,
    pub ended_at: Option<OffsetDateTime>,
}

impl StreamSession {
    pub fn new(
        session_id: SessionId,
        stream_id: StreamId,
        channel_id: String,
        stream_generation: i64,
        source_generation: i64,
        now: OffsetDateTime,
    ) -> Self {
        Self {
            session_id,
            stream_id,
            channel_id,
            stream_generation,
            source_generation,
            status: SessionStatus::Preparing,
            availability: Availability::Offline,
            session_version: 1,
            preparing_deadline_at: now + PREPARING_TIMEOUT,
            grace_deadline_at: None,
            started_at: None,
            ended_at: None,
        }
    }

    pub fn mark_playable(
        &mut self,
        source_generation: i64,
        now: OffsetDateTime,
        playback_verified: bool,
        monotonic_preparing_elapsed: Duration,
        monotonic_grace_elapsed: Option<Duration>,
    ) -> Result<(), SessionTransitionError> {
        self.ensure_current_source(source_generation)?;
        if !playback_verified {
            return Err(SessionTransitionError::PlaybackNotVerified);
        }

        match self.status {
            SessionStatus::Preparing => {
                if monotonic_preparing_elapsed >= PREPARING_TIMEOUT {
                    self.end(now)?;
                    return Err(SessionTransitionError::PreparingTimedOut);
                }
            }
            SessionStatus::ReconnectGrace => {
                let elapsed = monotonic_grace_elapsed
                    .ok_or(SessionTransitionError::MonotonicGraceClockRequired)?;
                if elapsed >= RECONNECT_GRACE {
                    self.end(now)?;
                    return Err(SessionTransitionError::ReconnectGraceExpired);
                }
            }
            SessionStatus::Live => return Ok(()),
            SessionStatus::Ended => return Err(SessionTransitionError::AlreadyEnded),
        }

        let session_version = self.next_session_version()?;
        self.status = SessionStatus::Live;
        self.availability = Availability::Playable;
        self.grace_deadline_at = None;
        self.started_at.get_or_insert(now);
        self.session_version = session_version;
        Ok(())
    }

    pub fn source_lost(
        &mut self,
        source_generation: i64,
        now: OffsetDateTime,
    ) -> Result<(), SessionTransitionError> {
        self.ensure_current_source(source_generation)?;
        match self.status {
            SessionStatus::Live => {
                let session_version = self.next_session_version()?;
                self.status = SessionStatus::ReconnectGrace;
                self.availability = Availability::Reconnecting;
                self.grace_deadline_at = Some(now + RECONNECT_GRACE);
                self.session_version = session_version;
                Ok(())
            }
            SessionStatus::ReconnectGrace => Ok(()),
            SessionStatus::Preparing => self.end(now),
            SessionStatus::Ended => Err(SessionTransitionError::AlreadyEnded),
        }
    }

    pub fn expire_reconnect_grace(
        &mut self,
        now: OffsetDateTime,
        monotonic_grace_elapsed: Duration,
    ) -> Result<(), SessionTransitionError> {
        if self.status != SessionStatus::ReconnectGrace {
            return Err(SessionTransitionError::InvalidTransition);
        }
        if monotonic_grace_elapsed < RECONNECT_GRACE {
            return Err(SessionTransitionError::DeadlineNotReached);
        }
        self.end(now)
    }

    pub fn stop(&mut self, now: OffsetDateTime) -> Result<(), SessionTransitionError> {
        if self.status == SessionStatus::Ended {
            return Ok(());
        }
        self.end(now)
    }

    fn ensure_current_source(&self, source_generation: i64) -> Result<(), SessionTransitionError> {
        if source_generation != self.source_generation {
            return Err(SessionTransitionError::StaleSourceGeneration);
        }
        Ok(())
    }

    fn next_session_version(&self) -> Result<i64, SessionTransitionError> {
        self.session_version
            .checked_add(1)
            .filter(|version| *version > 0)
            .ok_or(SessionTransitionError::SessionVersionExhausted)
    }

    fn end(&mut self, now: OffsetDateTime) -> Result<(), SessionTransitionError> {
        let session_version = self.next_session_version()?;
        self.status = SessionStatus::Ended;
        self.availability = Availability::Offline;
        self.grace_deadline_at = None;
        self.ended_at = Some(now);
        self.session_version = session_version;
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum SessionTransitionError {
    #[error("session has already ended")]
    AlreadyEnded,
    #[error("session state does not allow this transition")]
    InvalidTransition,
    #[error("media playback has not been verified")]
    PlaybackNotVerified,
    #[error("the preparation deadline has elapsed")]
    PreparingTimedOut,
    #[error("the reconnect grace deadline has elapsed")]
    ReconnectGraceExpired,
    #[error("the reconnect grace transition requires its current owner's monotonic clock")]
    MonotonicGraceClockRequired,
    #[error("the reconnect deadline has not elapsed")]
    DeadlineNotReached,
    #[error("session version cannot advance further")]
    SessionVersionExhausted,
    #[error("callback belongs to a stale source generation")]
    StaleSourceGeneration,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session() -> StreamSession {
        StreamSession::new(
            SessionId::new(),
            StreamId::new(),
            String::from("channel-1"),
            1,
            7,
            OffsetDateTime::UNIX_EPOCH,
        )
    }

    #[test]
    fn new_session_starts_offline_and_preparing_with_a_deadline() {
        let session = session();

        assert_eq!(session.status, SessionStatus::Preparing);
        assert_eq!(session.availability, Availability::Offline);
        assert_eq!(session.session_version, 1);
        assert_eq!(
            session.preparing_deadline_at,
            OffsetDateTime::UNIX_EPOCH + PREPARING_TIMEOUT
        );
        assert_eq!(session.grace_deadline_at, None);
        assert_eq!(session.started_at, None);
        assert_eq!(session.ended_at, None);
    }

    #[test]
    fn verified_playback_makes_preparing_session_live_before_deadline() {
        let mut session = session();
        let now = OffsetDateTime::UNIX_EPOCH + Duration::from_secs(10);

        let result = session.mark_playable(7, now, true, Duration::from_secs(29), None);

        assert_eq!(result, Ok(()));
        assert_eq!(session.status, SessionStatus::Live);
        assert_eq!(session.availability, Availability::Playable);
        assert_eq!(session.session_version, 2);
        assert_eq!(session.started_at, Some(now));
        assert_eq!(session.grace_deadline_at, None);
    }

    #[test]
    fn unverified_or_stale_playback_does_not_change_session() {
        let mut session = session();

        assert_eq!(
            session.mark_playable(7, OffsetDateTime::UNIX_EPOCH, false, Duration::ZERO, None),
            Err(SessionTransitionError::PlaybackNotVerified)
        );
        assert_eq!(
            session.mark_playable(8, OffsetDateTime::UNIX_EPOCH, true, Duration::ZERO, None),
            Err(SessionTransitionError::StaleSourceGeneration)
        );
        assert_eq!(session.status, SessionStatus::Preparing);
        assert_eq!(session.availability, Availability::Offline);
        assert_eq!(session.session_version, 1);
        assert_eq!(session.started_at, None);
    }

    #[test]
    fn playback_at_preparing_deadline_ends_session() {
        let mut session = session();
        let now = OffsetDateTime::UNIX_EPOCH + PREPARING_TIMEOUT;

        let result = session.mark_playable(7, now, true, PREPARING_TIMEOUT, None);

        assert_eq!(result, Err(SessionTransitionError::PreparingTimedOut));
        assert_eq!(session.status, SessionStatus::Ended);
        assert_eq!(session.availability, Availability::Offline);
        assert_eq!(session.session_version, 2);
        assert_eq!(session.ended_at, Some(now));
        assert_eq!(session.started_at, None);
    }

    #[test]
    fn duplicate_source_loss_preserves_the_original_grace_deadline() {
        let mut session = session();
        assert_eq!(
            session.mark_playable(7, OffsetDateTime::UNIX_EPOCH, true, Duration::ZERO, None),
            Ok(())
        );
        let first_loss = OffsetDateTime::UNIX_EPOCH + Duration::from_secs(5);
        let duplicate_loss = OffsetDateTime::UNIX_EPOCH + Duration::from_secs(20);

        assert_eq!(session.source_lost(7, first_loss), Ok(()));
        let original_deadline = session.grace_deadline_at;
        assert_eq!(session.source_lost(7, duplicate_loss), Ok(()));

        assert_eq!(session.status, SessionStatus::ReconnectGrace);
        assert_eq!(session.availability, Availability::Reconnecting);
        assert_eq!(session.session_version, 3);
        assert_eq!(session.grace_deadline_at, original_deadline);
        assert_eq!(
            session.grace_deadline_at,
            Some(first_loss + RECONNECT_GRACE)
        );
    }

    #[test]
    fn reconnect_requires_its_monotonic_clock_and_preserves_first_start_time() {
        let mut session = session();
        let started_at = OffsetDateTime::UNIX_EPOCH + Duration::from_secs(2);
        assert_eq!(
            session.mark_playable(7, started_at, true, Duration::ZERO, None),
            Ok(())
        );
        assert_eq!(session.source_lost(7, started_at), Ok(()));
        let deadline = session.grace_deadline_at;

        assert_eq!(
            session.mark_playable(
                7,
                started_at + Duration::from_secs(3),
                true,
                Duration::ZERO,
                None,
            ),
            Err(SessionTransitionError::MonotonicGraceClockRequired)
        );
        assert_eq!(session.grace_deadline_at, deadline);
        assert_eq!(session.session_version, 3);

        assert_eq!(
            session.mark_playable(
                7,
                started_at + Duration::from_secs(4),
                true,
                Duration::ZERO,
                Some(RECONNECT_GRACE - Duration::from_secs(1)),
            ),
            Ok(())
        );
        assert_eq!(session.status, SessionStatus::Live);
        assert_eq!(session.availability, Availability::Playable);
        assert_eq!(session.session_version, 4);
        assert_eq!(session.started_at, Some(started_at));
        assert_eq!(session.grace_deadline_at, None);
    }

    #[test]
    fn playback_at_reconnect_grace_deadline_ends_session() {
        let mut session = session();
        assert_eq!(
            session.mark_playable(7, OffsetDateTime::UNIX_EPOCH, true, Duration::ZERO, None),
            Ok(())
        );
        assert_eq!(session.source_lost(7, OffsetDateTime::UNIX_EPOCH), Ok(()));
        let now = OffsetDateTime::UNIX_EPOCH + RECONNECT_GRACE;

        let result = session.mark_playable(7, now, true, Duration::ZERO, Some(RECONNECT_GRACE));

        assert_eq!(result, Err(SessionTransitionError::ReconnectGraceExpired));
        assert_eq!(session.status, SessionStatus::Ended);
        assert_eq!(session.availability, Availability::Offline);
        assert_eq!(session.session_version, 4);
        assert_eq!(session.ended_at, Some(now));
        assert_eq!(session.grace_deadline_at, None);
    }

    #[test]
    fn reconnect_grace_cannot_expire_early_but_can_expire_at_deadline() {
        let mut session = session();
        assert_eq!(
            session.mark_playable(7, OffsetDateTime::UNIX_EPOCH, true, Duration::ZERO, None),
            Ok(())
        );
        assert_eq!(session.source_lost(7, OffsetDateTime::UNIX_EPOCH), Ok(()));
        let deadline = session.grace_deadline_at;

        assert_eq!(
            session.expire_reconnect_grace(
                OffsetDateTime::UNIX_EPOCH + Duration::from_secs(29),
                RECONNECT_GRACE - Duration::from_secs(1),
            ),
            Err(SessionTransitionError::DeadlineNotReached)
        );
        assert_eq!(session.status, SessionStatus::ReconnectGrace);
        assert_eq!(session.grace_deadline_at, deadline);

        let ended_at = OffsetDateTime::UNIX_EPOCH + RECONNECT_GRACE;
        assert_eq!(
            session.expire_reconnect_grace(ended_at, RECONNECT_GRACE),
            Ok(())
        );
        assert_eq!(session.status, SessionStatus::Ended);
        assert_eq!(session.session_version, 4);
        assert_eq!(session.ended_at, Some(ended_at));
    }

    #[test]
    fn stopping_an_ended_session_is_idempotent() {
        let mut session = session();
        let ended_at = OffsetDateTime::UNIX_EPOCH + Duration::from_secs(1);
        assert_eq!(session.stop(ended_at), Ok(()));
        assert_eq!(session.stop(ended_at + Duration::from_secs(1)), Ok(()));

        assert_eq!(session.status, SessionStatus::Ended);
        assert_eq!(session.session_version, 2);
        assert_eq!(session.ended_at, Some(ended_at));
    }

    #[test]
    fn version_overflow_does_not_partially_mutate_a_transition() {
        let mut session = session();
        session.session_version = i64::MAX;

        assert_eq!(
            session.stop(OffsetDateTime::UNIX_EPOCH),
            Err(SessionTransitionError::SessionVersionExhausted)
        );
        assert_eq!(
            session.mark_playable(7, OffsetDateTime::UNIX_EPOCH, true, Duration::ZERO, None),
            Err(SessionTransitionError::SessionVersionExhausted)
        );
        assert_eq!(session.status, SessionStatus::Preparing);
        assert_eq!(session.availability, Availability::Offline);
        assert_eq!(session.session_version, i64::MAX);
        assert_eq!(session.started_at, None);
        assert_eq!(session.ended_at, None);
    }
}
