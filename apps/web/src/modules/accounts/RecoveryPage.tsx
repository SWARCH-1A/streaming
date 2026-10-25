import type { FormEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import type { P2RecoveryAccepted, P2RecoveryConfirm, P2RecoveryRequest } from '@contracts/p2';

import { useErrorFocus } from '@/src/accessibility/useErrorFocus';
import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { errorMessage, request } from '@/src/shared/api/http';
import { useLifetime } from '@/src/shared/api/useLifetime';
import { codePointLength } from '@/src/shared/format';

import styles from './AuthPage.module.css';

type Fields = 'email' | 'newPassword' | 'confirmation';

export function RecoveryPage({ mode }: { mode: 'request' | 'confirm' }) {
  const confirming = mode === 'confirm';
  const location = useLocation();
  const navigate = useNavigate();
  const [link, setLink] = useState(() => ({
    fragment: location.hash,
    token: confirming ? (new URLSearchParams(location.hash.slice(1)).get('token') ?? '') : '',
    version: 0,
  }));

  if (confirming && location.hash !== link.fragment) {
    // URL cleanup retains the captured token; an incoming fragment starts a fresh form.
    setLink({
      fragment: location.hash,
      token: location.hash
        ? (new URLSearchParams(location.hash.slice(1)).get('token') ?? '')
        : link.token,
      version: link.version + (location.hash ? 1 : 0),
    });
  }

  useEffect(() => {
    if (confirming && location.hash) {
      // A fragment is not sent to the server. Remove it from browser history once captured in memory.
      void navigate(location.pathname, { replace: true });
    }
  }, [confirming, location.hash, location.pathname, navigate]);

  return (
    <RecoveryForm
      key={`${mode}:${link.version}`}
      mode={mode}
      token={link.token}
      onConfirmed={() => setLink((previous) => ({ ...previous, token: '' }))}
    />
  );
}

function RecoveryForm({
  mode,
  token,
  onConfirmed,
}: {
  mode: 'request' | 'confirm';
  token: string;
  onConfirmed: () => void;
}) {
  const confirming = mode === 'confirm';
  const [values, setValues] = useState({ email: '', newPassword: '', confirmation: '' });
  const [errors, setErrors] = useState<Partial<Record<Fields, string>>>({});
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState('');
  const [done, setDone] = useState('');
  const key = useRef(crypto.randomUUID());
  const lifetime = useLifetime();
  const focusError = useErrorFocus(pending);
  const validToken = /^[A-Za-z0-9_-]{43}$/.test(token);

  function change(field: Fields, value: string) {
    key.current = crypto.randomUUID();
    setValues((previous) => ({ ...previous, [field]: value }));
    setErrors((previous) => ({ ...previous, [field]: undefined }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const next: Partial<Record<Fields, string>> = {};
    if (confirming) {
      const length = codePointLength(values.newPassword);
      if (length < 12 || length > 128) next.newPassword = 'Usa de 12 a 128 caracteres.';
      if (values.confirmation !== values.newPassword)
        next.confirmation = 'Las contraseñas deben coincidir.';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.email.trim())) {
      next.email = 'Escribe un correo electrónico válido.';
    }
    setErrors(next);
    const first = Object.keys(next)[0];
    if (first) {
      focusError(first);
      return;
    }
    if (confirming && !validToken) return;
    const signal = lifetime();
    setPending(true);
    setFailure('');
    try {
      const body: P2RecoveryRequest | P2RecoveryConfirm = confirming
        ? { recoveryToken: token, newPassword: values.newPassword }
        : { email: values.email.trim() };
      if (confirming) {
        await request<void>('/api/identity/recoveries/confirm', {
          method: 'POST',
          body,
          headers: { 'Idempotency-Key': key.current },
          coreMutation: true,
          signal,
        });
        signal.throwIfAborted();
        onConfirmed();
        setValues({ email: '', newPassword: '', confirmation: '' });
        window.dispatchEvent(new Event('session-expired'));
        setDone('Contraseña actualizada. Inicia sesión con tu nueva contraseña.');
      } else {
        const response = await request<P2RecoveryAccepted>('/api/identity/recovery-requests', {
          method: 'POST',
          body,
          headers: { 'Idempotency-Key': key.current },
          coreMutation: true,
          signal,
        });
        signal.throwIfAborted();
        setDone(response.message);
        setValues({ email: '', newPassword: '', confirmation: '' });
      }
    } catch (error) {
      if (!signal.aborted) setFailure(errorMessage(error));
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }

  return (
    <Panel className={`${styles.form} ${styles.recovery}`}>
      <p className="eyebrow">Identidad & acceso</p>
      <h1>{confirming ? 'Restablece tu contraseña' : 'Recupera tu cuenta'}</h1>
      <p className="muted">
        {confirming
          ? 'Elige una contraseña nueva para volver a entrar.'
          : 'Escribe el correo con el que creaste tu cuenta.'}
      </p>
      {failure ? <Notice tone="error">{failure}</Notice> : null}
      {done ? (
        <div className="stack">
          <Notice tone="success">{done}</Notice>
          <Link to="/login">Ir a iniciar sesión</Link>
        </div>
      ) : confirming && !validToken ? (
        <Notice tone="error">
          El enlace no es válido. Solicita un correo de recuperación nuevo.
        </Notice>
      ) : (
        <form
          className="stack"
          noValidate
          onSubmit={(event) => {
            void submit(event);
          }}
        >
          <fieldset className="stack" disabled={pending}>
            <legend className="sr-only">
              {confirming ? 'Nueva contraseña' : 'Correo de recuperación'}
            </legend>
            {confirming ? (
              <>
                <FormField
                  id="newPassword"
                  label="Nueva contraseña"
                  error={errors.newPassword}
                  hint="De 12 a 128 caracteres. Se respetan los espacios y símbolos."
                >
                  <Input
                    id="newPassword"
                    type="password"
                    autoComplete="new-password"
                    value={values.newPassword}
                    onChange={(event) => change('newPassword', event.target.value)}
                    aria-invalid={!!errors.newPassword}
                    aria-describedby={errors.newPassword ? 'newPassword-error' : 'newPassword-hint'}
                    required
                  />
                </FormField>
                <FormField
                  id="confirmation"
                  label="Repite la contraseña"
                  error={errors.confirmation}
                >
                  <Input
                    id="confirmation"
                    type="password"
                    autoComplete="new-password"
                    value={values.confirmation}
                    onChange={(event) => change('confirmation', event.target.value)}
                    aria-invalid={!!errors.confirmation}
                    aria-describedby={errors.confirmation ? 'confirmation-error' : undefined}
                    required
                  />
                </FormField>
              </>
            ) : (
              <FormField id="email" label="Correo electrónico" error={errors.email}>
                <Input
                  id="email"
                  type="email"
                  autoComplete="email"
                  maxLength={400}
                  value={values.email}
                  onChange={(event) => change('email', event.target.value)}
                  aria-invalid={!!errors.email}
                  aria-describedby={errors.email ? 'email-error' : undefined}
                  required
                />
              </FormField>
            )}
            <Button type="submit" loading={pending}>
              {confirming ? 'Guardar nueva contraseña' : 'Enviar correo de recuperación'}
            </Button>
          </fieldset>
        </form>
      )}
      <p className={styles.alternate}>
        {confirming ? (
          <Link to="/forgot-password">Solicitar otro enlace de recuperación</Link>
        ) : (
          <Link to="/login">Volver a iniciar sesión</Link>
        )}
      </p>
    </Panel>
  );
}
