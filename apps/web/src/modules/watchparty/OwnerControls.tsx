import { useEffect, useMemo, useState } from 'react';

import { Button } from '@/src/components/atoms/Button';
import { Select } from '@/src/components/atoms/Select';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';

import type { Party } from './api';
import { MAX_SOURCES } from './api';
import { loadLiveStreams } from './liveStreams';
import { usePolling } from './usePolling';

/**
 * Owner-only controls. Members never see them, and Core rejects the same commands from anyone else.
 * Streams are offered from Discovery's live list, minus the ones already in the room.
 */
export function OwnerControls({
  party,
  pending,
  error,
  onAdd,
  onRotate,
  onClose,
}: {
  party: Party;
  pending: boolean;
  error: string;
  onAdd: (streamId: string) => void;
  onRotate: () => void;
  onClose: () => void;
}) {
  const full = party.sources.length >= MAX_SOURCES;
  const live = usePolling('party-live-options', loadLiveStreams, 15000, !full);
  const used = useMemo(() => new Set(party.sources.map((source) => source.streamId)), [party]);
  const options = (live.data ?? []).filter((item) => !used.has(item.streamId));
  const [selected, setSelected] = useState('');
  const choice = options.some((item) => item.streamId === selected) ? selected : '';
  const [confirming, setConfirming] = useState(false);
  useEffect(() => {
    if (confirming) document.getElementById('party-close-cancel')?.focus();
  }, [confirming]);

  return (
    <Panel className="stack" role="region" aria-labelledby="owner-controls-title">
      <h2 id="owner-controls-title">Administrar la sala</h2>
      <p className="muted">
        {party.sources.length} de {MAX_SOURCES} transmisiones. Solo tú puedes cambiar la sala.
      </p>
      <form
        className="stack"
        onSubmit={(event) => {
          event.preventDefault();
          if (choice) onAdd(choice);
        }}
      >
        <FormField
          id="party-add-source"
          label="Añadir una transmisión en vivo"
          hint={
            full
              ? 'La sala está llena: quita una transmisión para añadir otra.'
              : live.error && !live.data
                ? 'No se pudo cargar la lista de transmisiones en vivo.'
                : options.length
                  ? 'Se listan las transmisiones en vivo que aún no están en la sala.'
                  : 'No hay otras transmisiones en vivo ahora.'
          }
        >
          <Select
            id="party-add-source"
            value={choice}
            disabled={full || pending || !options.length}
            aria-describedby="party-add-source-hint"
            onChange={(event) => setSelected(event.target.value)}
          >
            <option value="">Elige una transmisión…</option>
            {options.map((item) => (
              <option key={item.streamId} value={item.streamId}>
                {item.title} · {item.channel.displayName}
              </option>
            ))}
          </Select>
        </FormField>
        <Button type="submit" disabled={!choice || full || pending}>
          Añadir a la sala
        </Button>
      </form>
      {error ? <Notice tone="error">{error}</Notice> : null}
      <div className="row">
        <Button variant="secondary" disabled={pending} onClick={onRotate}>
          Rotar código de acceso
        </Button>
        <Button variant="danger" disabled={pending} onClick={() => setConfirming(true)}>
          Cerrar sala
        </Button>
      </div>
      <p className="muted">
        Rotar el código invalida el anterior sin sacar a quienes ya están dentro. Cerrar la sala es
        definitivo.
      </p>
      {confirming ? (
        <Notice tone="warning">
          <p>
            ¿Cerrar la sala «{party.title}»? Nadie podrá entrar ni se entregarán más transmisiones.
            Esta acción no se puede deshacer.
          </p>
          <div className="row">
            <Button
              variant="danger"
              disabled={pending}
              onClick={() => {
                setConfirming(false);
                onClose();
              }}
            >
              Sí, cerrar la sala
            </Button>
            <Button
              id="party-close-cancel"
              variant="secondary"
              onClick={() => setConfirming(false)}
            >
              Cancelar
            </Button>
          </div>
        </Notice>
      ) : null}
    </Panel>
  );
}
