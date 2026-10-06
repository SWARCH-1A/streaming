use sqlx::PgPool;
use uuid::Uuid;

pub async fn execute(
    pool: &PgPool,
    args: &[String],
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    match args {
        [action] if action=="list"=> {
            let entries=sqlx::query_scalar::<_,serde_json::Value>("SELECT jsonb_build_object('eventId',event_id,'consumer',consumer,'aggregateId',aggregate_id,'eventType',event_type,'attempts',attempts,'deadLetterAtUtc',dead_letter_at,'reasonCode',last_error_code) FROM streaming_outbox WHERE dead_letter_at IS NOT NULL AND closed_at IS NULL ORDER BY dead_letter_at,event_id LIMIT 100")
                .fetch_all(pool).await?;
            println!("{}",serde_json::to_string_pretty(&entries)?);
            Ok(())
        },
        [action,id,operator,note] if matches!(action.as_str(),"redrive"|"close")=> {
            if operator.trim().is_empty() || operator.len()>128 || operator.chars().any(char::is_control)
                || note.trim().is_empty() || note.chars().count()>1000 || note.contains('\0') { return Err("operator and resolution note are required".into()); }
            let id=Uuid::parse_str(id)?;
            let mut tx=pool.begin().await?;
            let found=sqlx::query_scalar::<_,Uuid>("SELECT event_id FROM streaming_outbox WHERE event_id=$1 AND dead_letter_at IS NOT NULL AND closed_at IS NULL FOR UPDATE")
                .bind(id).fetch_optional(&mut *tx).await?;
            if found.is_none() { return Err("open delivery dead letter not found".into()); }
            let query=if action=="redrive" {
                "UPDATE streaming_outbox SET dead_letter_at=NULL,first_attempt_at=NULL,alerted_at=NULL,attempts=0,available_at=clock_timestamp(),claim_owner=NULL,claim_token=NULL,claim_expires_at=NULL WHERE event_id=$1"
            } else { "UPDATE streaming_outbox SET closed_at=clock_timestamp() WHERE event_id=$1" };
            sqlx::query(query).bind(id).execute(&mut *tx).await?;
            sqlx::query("INSERT INTO streaming_delivery_operations(operation_id,event_id,action,operator_id,resolution_note) VALUES($1,$2,$3,$4,$5)")
                .bind(Uuid::new_v4()).bind(id).bind(action.to_uppercase()).bind(operator).bind(note).execute(&mut *tx).await?;
            tx.commit().await?;
            println!("streaming delivery operation completed: {id}");
            Ok(())
        },
        _=>Err("usage: streaming-service delivery-dead-letters list | redrive|close EVENT_ID OPERATOR NOTE".into()),
    }
}
