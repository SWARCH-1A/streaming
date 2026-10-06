use sqlx::PgPool;
use uuid::Uuid;

pub(super) async fn execute(
    pool: &PgPool,
    args: &[String],
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    match args {
        [command] if command == "list" => {
            let entries=sqlx::query_scalar::<_,serde_json::Value>("SELECT jsonb_build_object('eventId',event_id,'sessionId',payload->>'sessionId','kind',kind,'attempts',attempts,'deadLetterAt',dead_letter_at,'lastErrorCode',last_error_code) FROM media_callback_outbox WHERE dead_letter_at IS NOT NULL AND delivered_at IS NULL AND closed_at IS NULL ORDER BY dead_letter_at,event_id LIMIT 100")
                .fetch_all(pool).await?;
            println!("{}", serde_json::to_string_pretty(&entries)?);
            Ok(())
        }
        [action, id, operator, note] if matches!(action.as_str(), "redrive" | "close") => {
            let id = Uuid::parse_str(id)?;
            if operator.is_empty()
                || operator.len() > 128
                || note.trim().is_empty()
                || note.chars().count() > 500
                || operator.chars().any(char::is_control)
            {
                return Err("operator ID and resolution note are required".into());
            }
            let mut tx = pool.begin().await?;
            let found=sqlx::query_scalar::<_,Uuid>("SELECT event_id FROM media_callback_outbox WHERE event_id=$1 AND dead_letter_at IS NOT NULL AND delivered_at IS NULL AND closed_at IS NULL FOR UPDATE")
                .bind(id).fetch_optional(&mut *tx).await?;
            if found.is_none() {
                return Err("open dead letter not found".into());
            }
            let statement = if action == "redrive" {
                "UPDATE media_callback_outbox SET dead_letter_at=NULL,first_attempt_at=NULL,alerted_at=NULL,attempts=0,available_at=clock_timestamp(),claim_token=NULL,claim_until=NULL WHERE event_id=$1"
            } else {
                "UPDATE media_callback_outbox SET closed_at=clock_timestamp(),claim_token=NULL,claim_until=NULL WHERE event_id=$1"
            };
            sqlx::query(statement).bind(id).execute(&mut *tx).await?;
            sqlx::query("INSERT INTO media_callback_operations(event_id,action,operator_id,note) VALUES($1,$2,$3,$4)")
                .bind(id).bind(action.to_uppercase()).bind(operator).bind(note).execute(&mut *tx).await?;
            tx.commit().await?;
            println!("media callback operation completed: {id}");
            Ok(())
        }
        _ => Err(
            "usage: media-adapter dead-letter list | redrive|close EVENT_ID OPERATOR NOTE".into(),
        ),
    }
}
