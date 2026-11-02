import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';

import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { EmptyState } from '@/src/components/molecules/EmptyState';
import { Notice } from '@/src/components/molecules/Notice';
import { useSession } from '@/src/shell/session/useSession';

import { AccessCodePanel } from './AccessCodePanel';
import type { Party } from './api';
import {
  addSource,
  closeParty,
  getParty,
  MAX_SOURCES,
  PARTY_ID,
  removeSource,
  rotateAccessCode,
} from './api';
import { hideCode, peekCode, revealCode } from './codeVault';
import { OwnerControls } from './OwnerControls';
import { PartySourceCard } from './PartySourceCard';
import { forgetParty, rememberParty } from './recent';
import { useCommand } from './useCommand';
import { usePolling } from './usePolling';
import styles from './WatchParty.module.css';

const POLL_MS = 5000;
const stateText: Record<string, string> = {
  PLAYABLE: 'en vivo',
  RECONNECTING: 'reconectando',
  OFFLINE: 'offline',
  ENDED: 'finalizada',
  SUSPENDED: 'no disponible',
  UNKNOWN: 'sin confirmar',
};

/** Tells assistive technology when a source changes state, without re-announcing unchanged polls. */
function changes(previous: Record<string, string>, party: Party) {
  const messages: string[] = [];
  for (const source of party.sources) {
    const before = previous[source.streamId];
    if (before !== undefined && before !== source.state)
      messages.push(`${source.title ?? 'Una fuente'}: ${stateText[source.state] ?? source.state}`);
  }
  return messages.join('. ');
}

/** `initial` is the room the server rendered for this request (SSR); the browser keeps polling from there. */
export function WatchPartyRoom({ initial }: { initial?: Party | null } = {}) {
  const { partyId = '' } = useParams();
  return (
    <Room key={partyId} partyId={partyId} initial={initial?.partyId === partyId ? initial : null} />
  );
}

function Room({ partyId, initial }: { partyId: string; initial: Party | null }) {
  const { user } = useSession();
  const userId = user?.userId;
  const valid = PARTY_ID.test(partyId);
  const room = usePolling(
    `party:${partyId}`,
    (signal) => getParty(partyId, signal),
    POLL_MS,
    // A room that was already closed when the server rendered it never changes again.
    valid && initial?.status !== 'CLOSED',
    (next) => next.status === 'CLOSED',
    initial ?? undefined,
  );
  const party = room.data;
  const command = useCommand();
  const [code, setCode] = useState<string | null>(() => peekCode(partyId));
  const [notice, setNotice] = useState('');
  const [seen, setSeen] = useState<{ states: Record<string, string>; message: string }>({
    states: {},
    message: '',
  });

  if (party) {
    const states = Object.fromEntries(
      party.sources.map((source) => [source.streamId, source.state]),
    );
    if (JSON.stringify(states) !== JSON.stringify(seen.states))
      setSeen({ states, message: changes(seen.states, party) });
  }

  const title = party?.title;
  useEffect(() => {
    if (userId && title) rememberParty(userId, { partyId, title });
  }, [userId, partyId, title]);

  async function apply(action: string, execute: (key: string) => Promise<Party>) {
    setNotice('');
    const next = await command.run(action, execute);
    if (next) room.replace(next);
    return next;
  }
  async function rotate() {
    const next = await apply('rotate', (key) => rotateAccessCode(partyId, key));
    if (!next) return;
    if (next.accessCode) {
      revealCode(partyId, next.accessCode);
      setCode(next.accessCode);
    } else {
      // The rotation did apply: whatever code this tab still shows no longer admits anyone.
      hideCode(partyId);
      setCode(null);
      setNotice(
        'Esa rotación ya se había aplicado y el código no se puede volver a mostrar. Rótalo otra vez para obtener uno nuevo.',
      );
    }
  }
  async function close() {
    const next = await apply('close', (key) => closeParty(partyId, key));
    if (next) {
      hideCode(partyId);
      setCode(null);
    }
  }
  function hide() {
    hideCode(partyId);
    setCode(null);
  }

  if (!valid)
    return (
      <EmptyState
        title="Sala no encontrada"
        description="El enlace no corresponde a una sala."
        icon="alert"
        action={<Link to="/watch-parties">Volver a las salas</Link>}
      />
    );
  if (room.loading) return <p role="status">Cargando sala…</p>;
  if (!party)
    return (
      <div className="stack">
        <h1>No se pudo abrir la sala</h1>
        <Notice tone="error">{room.error}</Notice>
        <div className="row">
          <Button variant="secondary" onClick={room.refresh}>
            Reintentar
          </Button>
          <Button variant="ghost" onClick={() => userId && forgetParty(userId, partyId)}>
            Quitar de salas recientes
          </Button>
          <Link to="/watch-parties">Volver a las salas</Link>
        </div>
      </div>
    );

  const open = party.status === 'OPEN';
  const owner = party.owner.displayName ?? party.owner.handle ?? 'quien la creó';
  return (
    <div className="stack">
      <div className={styles.heading}>
        <div>
          <p className="eyebrow">Sala de visualización conjunta</p>
          <h1>{party.title}</h1>
          <p className="muted">
            Creada por {owner} · {party.memberCount}{' '}
            {party.memberCount === 1 ? 'persona' : 'personas'} · {party.sources.length} de{' '}
            {MAX_SOURCES} transmisiones
          </p>
        </div>
        <Badge tone={open ? 'success' : 'neutral'} dot>
          {open ? 'ABIERTA' : 'CERRADA'}
        </Badge>
      </div>
      <p role="status" className="sr-only">
        {seen.message}
      </p>
      {!open ? (
        <Notice tone="info">
          La sala está cerrada: ya no admite cambios ni nuevos miembros y no entrega transmisiones.
        </Notice>
      ) : null}
      {room.error ? (
        <Notice tone="warning">
          No se pudo actualizar la sala; se muestra la última información conocida. {room.error}
        </Notice>
      ) : null}
      {notice ? <Notice tone="info">{notice}</Notice> : null}
      {party.isOwner && code ? <AccessCodePanel code={code} onHide={hide} /> : null}
      {party.isOwner && open ? (
        <OwnerControls
          party={party}
          pending={command.pending}
          error={command.error}
          onAdd={(streamId) => {
            void apply(`add:${streamId}`, (key) => addSource(partyId, streamId, key));
          }}
          onRotate={() => {
            void rotate();
          }}
          onClose={() => {
            void close();
          }}
        />
      ) : null}
      {!party.isOwner && command.error ? <Notice tone="error">{command.error}</Notice> : null}
      {!party.isOwner && open ? (
        <p className="muted">
          Eres miembro de esta sala: puedes verla, pero solo {owner} la modifica.
        </p>
      ) : null}
      <section aria-labelledby="party-sources" className="stack">
        <h2 id="party-sources">Transmisiones</h2>
        {party.sources.length ? (
          <div className={styles.grid} data-count={party.sources.length}>
            {party.sources.map((source) => (
              <PartySourceCard
                key={source.streamId}
                source={source}
                roomOpen={open}
                canRemove={party.isOwner && open}
                busy={command.pending}
                onRemove={(item) => {
                  void apply(`remove:${item.streamId}`, (key) =>
                    removeSource(partyId, item.streamId, key),
                  );
                }}
              />
            ))}
          </div>
        ) : (
          <EmptyState
            title="La sala aún no tiene transmisiones"
            description={
              party.isOwner && open
                ? 'Añade una transmisión en vivo desde «Administrar la sala».'
                : 'Quien creó la sala todavía no ha añadido transmisiones.'
            }
            icon="screen"
          />
        )}
      </section>
      <Link to="/watch-parties">← Mis salas</Link>
    </div>
  );
}
