import type { FormEvent } from 'react';
import { useState, useSyncExternalStore } from 'react';
import { Link, useNavigate } from 'react-router';

import { useErrorFocus } from '@/src/accessibility/useErrorFocus';
import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { useSession } from '@/src/shell/session/useSession';

import { ACCESS_CODE, joinParty } from './api';
import { rememberParty } from './recent';
import { useCommand } from './useCommand';

function subscribeHash(listener: () => void) {
  window.addEventListener('hashchange', listener);
  return () => window.removeEventListener('hashchange', listener);
}

/**
 * Join by access code. A shared link carries the code in the URL fragment, which the browser never
 * sends to a server; it is read on the client and the join replaces this history entry, so the
 * secret does not stay in the history after entering. The route is public so that an invitation opened
 * without a session explains how to continue instead of being redirected (which would drop the link).
 */
export function WatchPartyJoin() {
  const { user, status } = useSession();
  const navigate = useNavigate();
  const fragment = useSyncExternalStore(
    subscribeHash,
    () => window.location.hash.slice(1),
    () => '',
  );
  const [typed, setTyped] = useState<string | null>(null);
  const code = typed ?? fragment;
  const [fieldError, setFieldError] = useState('');
  const command = useCommand();
  const focusError = useErrorFocus(command.pending);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const clean = code.trim();
    if (!ACCESS_CODE.test(clean)) {
      setFieldError('El código tiene 43 caracteres (letras, números, guion y guion bajo).');
      focusError('party-code');
      return;
    }
    setFieldError('');
    const party = await command.run(`join:${clean}`, (key) => joinParty(clean, key));
    if (!party) {
      focusError('party-code');
      return;
    }
    if (user) rememberParty(user.userId, { partyId: party.partyId, title: party.title });
    await navigate(`/watch-parties/${party.partyId}`, { replace: true });
  }

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Salas de visualización conjunta</p>
        <h1>Unirme a una sala</h1>
        <p className="muted">
          Pega el código que te compartió quien creó la sala. Por seguridad, un código incorrecto y
          una sala inexistente muestran el mismo mensaje.
        </p>
      </div>
      {!user ? (
        <Panel className="stack">
          {status === 'loading' ? (
            <p role="status">Comprobando sesión…</p>
          ) : (
            <Notice tone="info">
              Necesitas una cuenta para entrar a una sala. <Link to="/login">Inicia sesión</Link> o{' '}
              <Link to="/register">crea una cuenta</Link> y después vuelve a abrir el enlace de
              invitación: el código sigue en el enlace.
            </Notice>
          )}
        </Panel>
      ) : (
        <Panel className="stack">
          <form
            className="stack"
            noValidate
            onSubmit={(event) => {
              void submit(event);
            }}
          >
            <fieldset disabled={command.pending} className="stack">
              <legend className="sr-only">Código de acceso</legend>
              <FormField
                id="party-code"
                label="Código de acceso"
                hint="Si abriste un enlace, el código ya está escrito."
                error={fieldError || undefined}
              >
                <Input
                  id="party-code"
                  value={code}
                  autoComplete="off"
                  spellCheck={false}
                  required
                  aria-invalid={!!fieldError}
                  aria-describedby={`party-code-hint${fieldError ? ' party-code-error' : ''}`}
                  onChange={(event) => {
                    setTyped(event.target.value);
                    setFieldError('');
                  }}
                />
              </FormField>
            </fieldset>
            {command.error ? <Notice tone="error">{command.error}</Notice> : null}
            <Button type="submit" loading={command.pending}>
              Unirme a la sala
            </Button>
          </form>
        </Panel>
      )}
      {user ? <Link to="/watch-parties">← Volver a mis salas</Link> : null}
    </div>
  );
}
