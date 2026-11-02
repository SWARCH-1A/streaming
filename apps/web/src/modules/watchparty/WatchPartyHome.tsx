import type { FormEvent } from 'react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';

import { useErrorFocus } from '@/src/accessibility/useErrorFocus';
import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { useSession } from '@/src/shell/session/useSession';

import { createParty } from './api';
import { hideCode, revealCode } from './codeVault';
import { forgetParty, rememberParty, useRecentParties } from './recent';
import { useCommand } from './useCommand';
import styles from './WatchParty.module.css';

const MAX_TITLE = 100;

export function WatchPartyHome() {
  const { user } = useSession();
  const userId = user?.userId;
  const navigate = useNavigate();
  const recent = useRecentParties(userId);
  const [title, setTitle] = useState('');
  const [titleError, setTitleError] = useState('');
  const command = useCommand();
  const focusError = useErrorFocus(command.pending);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const clean = title.trim();
    if (!clean || [...clean].length > MAX_TITLE) {
      setTitleError(
        clean
          ? `El título admite hasta ${MAX_TITLE} caracteres.`
          : 'Escribe un título para la sala.',
      );
      focusError('party-title');
      return;
    }
    setTitleError('');
    const party = await command.run(`create:${clean}`, (key) => createParty(clean, key));
    if (!party) {
      focusError('party-title');
      return;
    }
    // The code is only in this response: keep it in memory so the room can show it once.
    if (party.accessCode) revealCode(party.partyId, party.accessCode);
    else hideCode(party.partyId);
    if (userId) rememberParty(userId, { partyId: party.partyId, title: party.title });
    await navigate(`/watch-parties/${party.partyId}`);
  }

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Mira varias emisiones a la vez</p>
        <h1>Salas de visualización conjunta</h1>
        <p className="muted">
          Reúne hasta 4 transmisiones en vivo en una sola sala y compártela con un código de acceso.
          Cada persona ve las emisiones por su cuenta: no hay sincronía ni chat propio.
        </p>
      </div>
      <div className={styles.columns}>
        <Panel className="stack">
          <h2>Crear una sala</h2>
          <form
            className="stack"
            noValidate
            onSubmit={(event) => {
              void submit(event);
            }}
          >
            <fieldset disabled={command.pending} className="stack">
              <legend className="sr-only">Datos de la sala</legend>
              <FormField
                id="party-title"
                label="Título de la sala"
                hint="Hasta 100 caracteres."
                error={titleError || undefined}
              >
                <Input
                  id="party-title"
                  value={title}
                  maxLength={MAX_TITLE}
                  autoComplete="off"
                  required
                  aria-invalid={!!titleError}
                  aria-describedby={`party-title-hint${titleError ? ' party-title-error' : ''}`}
                  onChange={(event) => {
                    setTitle(event.target.value);
                    setTitleError('');
                  }}
                />
              </FormField>
            </fieldset>
            {command.error ? <Notice tone="error">{command.error}</Notice> : null}
            <Button type="submit" loading={command.pending}>
              Crear sala
            </Button>
          </form>
        </Panel>
        <Panel className="stack">
          <h2>Unirme con un código</h2>
          <p className="muted">
            Si te compartieron un enlace, ábrelo: el código viaja en el enlace y no se envía al
            servidor hasta que confirmas.
          </p>
          <Link to="/watch-parties/join">Tengo un código de acceso →</Link>
        </Panel>
      </div>
      <section aria-labelledby="recent-parties" className="stack">
        <h2 id="recent-parties">Salas recientes en este navegador</h2>
        {recent.length ? (
          <ul className={styles.recent}>
            {recent.map((party) => (
              <li key={party.partyId}>
                <Link to={`/watch-parties/${party.partyId}`}>{party.title}</Link>
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Quitar ${party.title} de las salas recientes`}
                  onClick={() => userId && forgetParty(userId, party.partyId)}
                >
                  Quitar
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">
            Aún no hay salas recientes. Solo se guarda el título y el identificador, nunca el código
            de acceso.
          </p>
        )}
      </section>
    </div>
  );
}
