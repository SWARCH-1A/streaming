import { randomUUID } from 'node:crypto';

import { HandlerError, type IncomingMessage } from './broker.ts';
import { CoreError, type CorePort, type RecoveryDelivery } from './core.ts';
import type { Db } from './db.ts';
import type { Log } from './log.ts';
import type { Mailer, SendOutcome } from './mail.ts';

interface Attempt {
  recovery_id: string;
  attempt: number;
  phase: 'CLAIMING' | 'SENDING' | 'RESULT_PENDING' | 'REPORTED' | 'EXPIRED';
  outcome: SendOutcome | null;
  claim_key: string;
  result_key: string | null;
}

/**
 * credential.recovery.delivery.requested.v1 (ADR-017). Only ids and the phase are stored; the address and
 * reset link exist in memory between Core's claim and the SMTP call. Phases make every restart safe:
 * - CLAIMING: claim again with the same Idempotency-Key and attempt;
 * - SENDING: the email may have left, so the attempt is reported as UNKNOWN instead of sent twice;
 * - RESULT_PENDING: resend the stored outcome with the same key.
 * The next attempt is claimed only after Core accepted a RETRYABLE_FAILURE for the previous one.
 */
export function recoveryHandler(db: Db, core: CorePort, mailer: Mailer, log: Log) {
  return async (message: IncomingMessage): Promise<string> => {
    const { recoveryId, deliveryId } = parse(message);
    const row = (await db.query<Attempt>('SELECT * FROM recovery_attempts WHERE delivery_id=$1', [deliveryId])).rows[0];
    if (row && row.recovery_id !== recoveryId) throw new HandlerError('RECOVERY_ID_CONFLICT', true);
    if (row?.phase === 'EXPIRED' || (row?.phase === 'REPORTED' && row.outcome !== 'RETRYABLE_FAILURE')) return `ALREADY_${row.phase}`;

    if (row?.phase === 'SENDING') return report(row, 'UNKNOWN');
    if (row?.phase === 'RESULT_PENDING') return report(row, row.outcome!);

    let attempt: Attempt;
    if (!row || row.phase === 'REPORTED') {
      attempt = { recovery_id: recoveryId, attempt: row ? row.attempt + 1 : 1, phase: 'CLAIMING', outcome: null,
        claim_key: randomUUID(), result_key: null };
      await db.query(
        `INSERT INTO recovery_attempts (delivery_id, recovery_id, attempt, phase, claim_key) VALUES ($1,$2,$3,'CLAIMING',$4)
         ON CONFLICT (delivery_id) DO UPDATE SET attempt=EXCLUDED.attempt, phase='CLAIMING', outcome=NULL,
           claim_key=EXCLUDED.claim_key, result_key=NULL, updated_at_utc=now()`,
        [deliveryId, recoveryId, attempt.attempt, attempt.claim_key]);
    } else {
      attempt = row;
    }

    let delivery: RecoveryDelivery;
    try {
      delivery = await core.claimRecovery(recoveryId, deliveryId, attempt.attempt, attempt.claim_key);
    } catch (error) {
      if (error instanceof CoreError && (error.status === 410 || error.status === 404)) {
        await setPhase(deliveryId, 'EXPIRED', null);
        return 'EXPIRED';
      }
      if (error instanceof CoreError && !error.retryable) throw new HandlerError(`CLAIM_${error.status}`, true);
      throw new HandlerError('CORE_UNAVAILABLE', false);
    }

    await setPhase(deliveryId, 'SENDING', null);
    const outcome = await mailer.send({
      to: delivery.recipient,
      subject: 'Recupera tu contraseña de Vault tv',
      text: `Recibimos una solicitud para cambiar la contraseña de tu cuenta.\n\nUsa este enlace antes de ${delivery.expiresAtUtc}:\n${delivery.resetUrl}\n\nSi no fuiste tú, ignora este correo; tu contraseña no cambia.`,
    });
    await db.query("UPDATE recovery_attempts SET phase='RESULT_PENDING', outcome=$2, result_key=$3, updated_at_utc=now() WHERE delivery_id=$1",
      [deliveryId, outcome, randomUUID()]);
    const pending = (await db.query<Attempt>('SELECT * FROM recovery_attempts WHERE delivery_id=$1', [deliveryId])).rows[0]!;
    return report(pending, outcome);

    async function report(current: Attempt, status: SendOutcome): Promise<string> {
      const key = current.result_key ?? randomUUID();
      if (!current.result_key || current.outcome !== status) {
        await db.query("UPDATE recovery_attempts SET phase='RESULT_PENDING', outcome=$2, result_key=$3, updated_at_utc=now() WHERE delivery_id=$1",
          [deliveryId, status, key]);
      }
      try {
        await core.reportRecovery(recoveryId, deliveryId, current.attempt, status, key);
      } catch (error) {
        if (error instanceof CoreError && (error.status === 404 || error.status === 410)) {
          await setPhase(deliveryId, 'EXPIRED', status);
          return 'EXPIRED';
        }
        if (error instanceof CoreError && !error.retryable) throw new HandlerError(`RESULT_${error.status}`, true);
        throw new HandlerError('CORE_UNAVAILABLE', false);
      }
      await setPhase(deliveryId, 'REPORTED', status);
      log.info('recuperacion reportada', { deliveryId, attempt: current.attempt, outcome: status });
      if (status === 'RETRYABLE_FAILURE') throw new HandlerError('SMTP_RETRYABLE_FAILURE', false);
      return status;
    }
  };

  async function setPhase(deliveryId: string, phase: string, outcome: SendOutcome | null): Promise<void> {
    await db.query('UPDATE recovery_attempts SET phase=$2, outcome=COALESCE($3, outcome), updated_at_utc=now() WHERE delivery_id=$1',
      [deliveryId, phase, outcome]);
  }
}

function parse(message: IncomingMessage): { recoveryId: string; deliveryId: string } {
  if (message.contentType !== 'application/json') throw new HandlerError('INVALID_EVENT', true);
  let event: Record<string, unknown>;
  try {
    event = JSON.parse(message.body) as Record<string, unknown>;
  } catch {
    throw new HandlerError('INVALID_EVENT', true);
  }
  const p = event.payload as Record<string, unknown> | undefined;
  const ok = event.eventType === 'credential.recovery.delivery.requested.v1' && event.producer === 'core' && p !== undefined &&
    typeof p.recoveryId === 'string' && /^rec_[A-Za-z0-9_-]{32}$/.test(p.recoveryId) &&
    typeof p.deliveryId === 'string' && /^del_[A-Za-z0-9_-]{32}$/.test(p.deliveryId) &&
    event.aggregateId === `recovery:${p.recoveryId}`;
  if (!ok) throw new HandlerError('INVALID_EVENT', true);
  return { recoveryId: p.recoveryId as string, deliveryId: p.deliveryId as string };
}
