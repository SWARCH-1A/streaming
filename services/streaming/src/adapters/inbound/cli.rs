use std::{ffi::OsString, num::ParseIntError};

use thiserror::Error;
use uuid::Uuid;

const DEFAULT_PAGE_SIZE: i64 = 100;

#[derive(Debug)]
pub enum OperatorCliCommand {
    Help,
    DeliveryDeadLetters {
        arguments: Vec<String>,
    },
    Migrate,
    ListDeadLetters {
        limit: i64,
    },
    RedriveDeadLetter {
        event_id: Uuid,
        operator_id: String,
        resolution_note: String,
    },
    CloseDeadLetter {
        event_id: Uuid,
        operator_id: String,
        resolution_note: String,
    },
}

#[derive(Debug, Error)]
pub enum OperatorCliParseError {
    #[error("command-line arguments must be valid UTF-8")]
    InvalidUnicode,
    #[error("invalid command syntax; run `streaming-service --help` for usage")]
    InvalidSyntax,
    #[error("invalid event ID; expected a UUID")]
    InvalidEventId(#[source] uuid::Error),
    #[error("invalid list limit; expected an integer")]
    InvalidLimit(#[source] ParseIntError),
}

pub fn parse_operator_cli(
    arguments: impl IntoIterator<Item = OsString>,
) -> Result<Option<OperatorCliCommand>, OperatorCliParseError> {
    let arguments = arguments
        .into_iter()
        .map(|argument| {
            argument
                .into_string()
                .map_err(|_| OperatorCliParseError::InvalidUnicode)
        })
        .collect::<Result<Vec<_>, _>>()?;
    if arguments.is_empty() {
        return Ok(None);
    }
    if arguments.as_slice() == ["--help"] || arguments.as_slice() == ["-h"] {
        return Ok(Some(OperatorCliCommand::Help));
    }
    if arguments.as_slice() == ["migrate"] {
        return Ok(Some(OperatorCliCommand::Migrate));
    }
    if arguments.first().map(String::as_str) == Some("delivery-dead-letters") {
        return Ok(Some(OperatorCliCommand::DeliveryDeadLetters {
            arguments: arguments[1..].to_vec(),
        }));
    }
    if arguments.first().map(String::as_str) != Some("dead-letters") {
        return Err(OperatorCliParseError::InvalidSyntax);
    }

    match arguments.get(1).map(String::as_str) {
        Some("list") => parse_list(&arguments[2..]).map(Some),
        Some("redrive") => parse_action(&arguments[2..], false).map(Some),
        Some("close") => parse_action(&arguments[2..], true).map(Some),
        _ => Err(OperatorCliParseError::InvalidSyntax),
    }
}

pub fn usage() -> &'static str {
    "Usage:\n  streaming-service delivery-dead-letters list|redrive|close [EVENT_ID OPERATOR NOTE]\n  streaming-service\n  streaming-service --help\n  streaming-service migrate\n  streaming-service dead-letters list [--limit N]\n  streaming-service dead-letters redrive <event-id> --operator <id> --reason <note>\n  streaming-service dead-letters close <event-id> --operator <id> --reason <note> --confirm-irrecoverable"
}

fn parse_list(arguments: &[String]) -> Result<OperatorCliCommand, OperatorCliParseError> {
    match arguments {
        [] => Ok(OperatorCliCommand::ListDeadLetters {
            limit: DEFAULT_PAGE_SIZE,
        }),
        [flag, value] if flag == "--limit" => {
            let limit = value.parse().map_err(OperatorCliParseError::InvalidLimit)?;
            Ok(OperatorCliCommand::ListDeadLetters { limit })
        }
        _ => Err(OperatorCliParseError::InvalidSyntax),
    }
}

fn parse_action(
    arguments: &[String],
    is_close: bool,
) -> Result<OperatorCliCommand, OperatorCliParseError> {
    let Some(event_id) = arguments.first() else {
        return Err(OperatorCliParseError::InvalidSyntax);
    };
    let event_id = Uuid::parse_str(event_id).map_err(OperatorCliParseError::InvalidEventId)?;
    let mut operator_id = None;
    let mut resolution_note = None;
    let mut confirmed_irrecoverable = false;
    let mut index = 1;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--operator" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(OperatorCliParseError::InvalidSyntax)?;
                if operator_id.replace(value.clone()).is_some() {
                    return Err(OperatorCliParseError::InvalidSyntax);
                }
                index += 2;
            }
            "--reason" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(OperatorCliParseError::InvalidSyntax)?;
                if resolution_note.replace(value.clone()).is_some() {
                    return Err(OperatorCliParseError::InvalidSyntax);
                }
                index += 2;
            }
            "--confirm-irrecoverable" if is_close && !confirmed_irrecoverable => {
                confirmed_irrecoverable = true;
                index += 1;
            }
            _ => return Err(OperatorCliParseError::InvalidSyntax),
        }
    }

    let operator_id = operator_id.ok_or(OperatorCliParseError::InvalidSyntax)?;
    let resolution_note = resolution_note.ok_or(OperatorCliParseError::InvalidSyntax)?;
    if is_close {
        if !confirmed_irrecoverable {
            return Err(OperatorCliParseError::InvalidSyntax);
        }
        Ok(OperatorCliCommand::CloseDeadLetter {
            event_id,
            operator_id,
            resolution_note,
        })
    } else if confirmed_irrecoverable {
        Err(OperatorCliParseError::InvalidSyntax)
    } else {
        Ok(OperatorCliCommand::RedriveDeadLetter {
            event_id,
            operator_id,
            resolution_note,
        })
    }
}
