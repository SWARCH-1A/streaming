import { useEffect, useState } from 'react';
import type { ChatMessage } from '@contracts/p1';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Select } from '@/src/components/atoms/Select';
import type { BlockChoice } from '@/src/modules/chat/moderationApi';
import {
  BLOCK_OPTIONS,
  blockUser,
  removeMessage,
  removeUserMessages,
  unblockUser,
} from '@/src/modules/chat/moderationApi';
import { errorMessage } from '@/src/shared/api/http';

import styles from './ModerationActions.module.css';

interface Props {
  channelId: string;
  message: ChatMessage;
  onResult: (text: string, tone: 'success' | 'error') => void;
}

/**
 * Acciones del propietario del canal sobre un mensaje: retirar, retirar los del autor y bloquear.
 * Quedan plegadas tras un botón a la derecha del mensaje para no cargar todo el historial.
 */
export function ModerationActions({ channelId, message, onResult }: Props) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [choice, setChoice] = useState<BlockChoice>('PT10M');
  const name = message.author.displayName;
  const selectId = `block-${message.messageId}`;
  const panelId = `moderation-${message.messageId}`;
  useEffect(() => {
    if (!open) return;
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, [open]);
  async function run(action: () => Promise<string>) {
    setBusy(true);
    try {
      onResult(await action(), 'success');
      setOpen(false);
    } catch (error) {
      onResult(errorMessage(error), 'error');
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Button
        variant="ghost"
        size="icon"
        className={styles.toggle}
        aria-label={`Moderar mensaje de ${name}`}
        title="Moderar"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((previous) => !previous)}
      >
        <Icon name="shield" size={16} />
      </Button>
      {open ? (
        <div
          id={panelId}
          className={styles.actions}
          role="group"
          aria-label={`Opciones de moderación para ${name}`}
        >
          <Button
            variant="ghost"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                await removeMessage(message.sessionId, message.messageId);
                return 'Mensaje eliminado.';
              })
            }
          >
            Eliminar mensaje
          </Button>
          <Button
            variant="ghost"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                const op = await removeUserMessages(
                  channelId,
                  message.sessionId,
                  message.author.userId,
                );
                return `Se eliminaron ${op.removedCount} mensajes de ${name} en esta emisión.`;
              })
            }
          >
            Eliminar sus mensajes
          </Button>
          <label htmlFor={selectId}>Duración del bloqueo</label>
          <Select
            id={selectId}
            value={choice}
            disabled={busy}
            onChange={(event) => setChoice(event.target.value as BlockChoice)}
          >
            {BLOCK_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </Select>
          <Button
            variant="secondary"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                const block = await blockUser(channelId, message.author.userId, choice);
                return block.expiresAtUtc
                  ? `${name} quedó bloqueado hasta el ${new Date(block.expiresAtUtc).toLocaleString('es', { dateStyle: 'medium', timeStyle: 'short' })}.`
                  : `${name} quedó bloqueado de forma permanente.`;
              })
            }
          >
            Bloquear
          </Button>
          <Button
            variant="ghost"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                await unblockUser(channelId, message.author.userId);
                return `Se retiró el bloqueo de ${name}.`;
              })
            }
          >
            Desbloquear
          </Button>
        </div>
      ) : null}
    </>
  );
}
