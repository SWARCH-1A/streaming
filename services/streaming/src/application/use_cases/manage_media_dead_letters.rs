use std::sync::Arc;

use uuid::Uuid;

use crate::application::ports::media_dead_letters::{
    MediaDeadLetter, MediaDeadLetterRepository, MediaDeadLetterRepositoryError,
};

const MAX_PAGE_SIZE: i64 = 500;
const MAX_OPERATOR_ID_LENGTH: usize = 128;
const MAX_RESOLUTION_NOTE_LENGTH: usize = 1_000;

pub struct ManageMediaDeadLetters<R> {
    repository: Arc<R>,
}

impl<R> ManageMediaDeadLetters<R>
where
    R: MediaDeadLetterRepository + 'static,
{
    pub fn new(repository: Arc<R>) -> Self {
        Self { repository }
    }

    pub async fn list_open(
        &self,
        limit: i64,
    ) -> Result<DeadLetterPage, ManageMediaDeadLettersError> {
        if !(1..=MAX_PAGE_SIZE).contains(&limit) {
            return Err(ManageMediaDeadLettersError::InvalidInput);
        }
        let mut items = self.repository.list_open(limit + 1).await?;
        let has_more = i64::try_from(items.len()).is_ok_and(|length| length > limit);
        items.truncate(
            usize::try_from(limit).map_err(|_| ManageMediaDeadLettersError::InvalidInput)?,
        );
        Ok(DeadLetterPage { items, has_more })
    }

    pub async fn redrive(
        &self,
        event_id: Uuid,
        operator_id: &str,
        resolution_note: &str,
    ) -> Result<(), ManageMediaDeadLettersError> {
        let (operator_id, resolution_note) =
            validate_operator_action(operator_id, resolution_note)?;
        self.repository
            .redrive(event_id, operator_id, resolution_note)
            .await?;
        Ok(())
    }

    pub async fn close(
        &self,
        event_id: Uuid,
        operator_id: &str,
        resolution_note: &str,
    ) -> Result<(), ManageMediaDeadLettersError> {
        let (operator_id, resolution_note) =
            validate_operator_action(operator_id, resolution_note)?;
        self.repository
            .close(event_id, operator_id, resolution_note)
            .await?;
        Ok(())
    }
}

#[derive(Clone, Debug)]
pub struct DeadLetterPage {
    pub items: Vec<MediaDeadLetter>,
    pub has_more: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum ManageMediaDeadLettersError {
    #[error("invalid dead-letter management input")]
    InvalidInput,
    #[error(transparent)]
    Repository(#[from] MediaDeadLetterRepositoryError),
}

fn validate_operator_action(
    operator_id: &str,
    resolution_note: &str,
) -> Result<(String, String), ManageMediaDeadLettersError> {
    let operator_id = operator_id.trim();
    let resolution_note = resolution_note.trim();
    if operator_id.is_empty()
        || operator_id.chars().count() > MAX_OPERATOR_ID_LENGTH
        || resolution_note.is_empty()
        || resolution_note.chars().count() > MAX_RESOLUTION_NOTE_LENGTH
    {
        return Err(ManageMediaDeadLettersError::InvalidInput);
    }
    Ok((operator_id.to_owned(), resolution_note.to_owned()))
}
