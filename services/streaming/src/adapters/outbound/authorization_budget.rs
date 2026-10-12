use sqlx::{Postgres, Transaction};
use std::time::Instant;

pub(super) fn valid(deadline: Option<Instant>) -> Result<(), sqlx::Error> {
    if deadline.is_some_and(|d| Instant::now() >= d) {
        Err(sqlx::Error::Protocol("authorization expired".to_owned()))
    } else {
        Ok(())
    }
}

pub(super) async fn configure(
    tx: &mut Transaction<'_, Postgres>,
    deadline: Option<Instant>,
) -> Result<(), sqlx::Error> {
    valid(deadline)?;
    if let Some(deadline) = deadline {
        let ms = deadline
            .saturating_duration_since(Instant::now())
            .as_millis()
            .max(1);
        sqlx::query("SELECT set_config('statement_timeout', $1, true), set_config('lock_timeout', $1, true)")
            .bind(format!("{ms}ms")).execute(&mut **tx).await?;
    }
    Ok(())
}
