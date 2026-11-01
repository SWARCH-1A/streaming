import { useEffect, useRef, useState } from 'react';
import type { P2AccountAdmin, P2AccountAdminPage, P2ChannelAdminPage } from '@contracts/p2/index';

import { Button } from '@/src/components/atoms/Button';
import { Textarea } from '@/src/components/atoms/Textarea';
import { FormField } from '@/src/components/molecules/FormField';
import { errorMessage, request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';

import styles from './AdministrationPage.module.css';
import { VodRemoval } from './VodRemoval';

export function AdministrationPage() {
  const [cursor, setCursor] = useState('');
  const [channelCursor, setChannelCursor] = useState('');
  const [revision, setRevision] = useState(0);
  const [selected, setSelected] = useState<P2AccountAdmin | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [reasonError, setReasonError] = useState('');
  const [notice, setNotice] = useState('');
  const operation = useRef<{ fingerprint: string; key: string } | null>(null);
  const reasonField = useRef<HTMLTextAreaElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const users = useResource(`admin-users:${cursor}:${revision}`, (signal) =>
    request<P2AccountAdminPage>(`/api/admin/users?limit=20&cursor=${encodeURIComponent(cursor)}`, {
      signal,
    }),
  );
  const channels = useResource(`admin-channels:${channelCursor}:${revision}`, (signal) =>
    request<P2ChannelAdminPage>(
      `/api/admin/channels?limit=20&cursor=${encodeURIComponent(channelCursor)}`,
      { signal },
    ),
  );
  useEffect(() => {
    if (selected) reasonField.current?.focus();
  }, [selected]);
  function choose(account: P2AccountAdmin) {
    setSelected(account);
    setReason('');
    setError('');
    setReasonError('');
    setNotice('');
    operation.current = null;
  }
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!selected || busy) return;
    if (!reason.trim()) {
      setReasonError('Indica el motivo del cambio.');
      reasonField.current?.focus();
      return;
    }
    const body = {
      status: selected.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE',
      expectedVersion: selected.accountVersion,
      reason: reason.trim(),
    };
    const fingerprint = JSON.stringify([selected.userId, body]);
    if (operation.current?.fingerprint !== fingerprint)
      operation.current = { fingerprint, key: crypto.randomUUID() };
    setBusy(true);
    setError('');
    setReasonError('');
    try {
      await request<P2AccountAdmin>(`/api/admin/users/${selected.userId}/status`, {
        method: 'PUT',
        body,
        coreMutation: true,
        headers: { 'Idempotency-Key': operation.current.key },
      });
      setSelected(null);
      setNotice('Estado guardado.');
      setRevision((n) => n + 1);
      heading.current?.focus();
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }
  function refresh() {
    setCursor('');
    setChannelCursor('');
    setSelected(null);
    setError('');
    setRevision((n) => n + 1);
  }
  return (
    <section className={styles.page}>
      <h1 ref={heading} tabIndex={-1}>
        Administración
      </h1>
      <p>Consulta usuarios y canales. Para cambiar una cuenta necesitas un rol administrativo.</p>
      <Button onClick={refresh} disabled={busy}>
        Actualizar listados
      </Button>
      {notice ? <p role="status">{notice}</p> : null}
      {users.loading ? <p role="status">Cargando usuarios…</p> : null}
      {users.error ? <p role="alert">{users.error}</p> : null}
      {users.data ? (
        <>
          <h2>Usuarios</h2>
          <ul className={styles.list}>
            {users.data.items.map((account) => (
              <li key={account.userId}>
                <span>
                  {account.handle} · {account.status === 'ACTIVE' ? 'Activa' : 'Suspendida'}
                </span>
                <Button disabled={busy} onClick={() => choose(account)}>
                  {account.status === 'ACTIVE' ? 'Suspender' : 'Reactivar'} {account.handle}
                </Button>
              </li>
            ))}
          </ul>
          {!users.data.items.length ? <p>No hay usuarios en esta página.</p> : null}
          {users.data.nextCursor ? (
            <Button
              disabled={busy}
              onClick={() => {
                setSelected(null);
                setCursor(users.data!.nextCursor!);
              }}
            >
              Siguiente página de usuarios
            </Button>
          ) : null}
        </>
      ) : null}
      {selected ? (
        <form
          onSubmit={(event) => {
            void save(event);
          }}
          className={styles.form}
        >
          <h2>
            {selected.status === 'ACTIVE' ? 'Suspender' : 'Reactivar'} {selected.handle}
          </h2>
          <FormField
            id="admin-reason"
            label="Motivo"
            hint="Obligatorio. No incluyas contraseñas ni otras credenciales."
            error={reasonError || undefined}
          >
            <Textarea
              id="admin-reason"
              ref={reasonField}
              maxLength={500}
              value={reason}
              disabled={busy}
              onChange={(event) => setReason(event.target.value)}
              aria-describedby={`admin-reason-hint${reasonError ? ' admin-reason-error' : ''}`}
              aria-invalid={!!reasonError}
            />
          </FormField>
          {error ? <p role="alert">{error}</p> : null}
          <div className={styles.actions}>
            <Button type="submit" loading={busy}>
              Confirmar cambio
            </Button>
            <Button
              disabled={busy}
              onClick={() => {
                setSelected(null);
                heading.current?.focus();
              }}
            >
              Cancelar
            </Button>
          </div>
        </form>
      ) : null}
      <VodRemoval />
      <h2>Canales</h2>
      {channels.loading ? <p role="status">Cargando canales…</p> : null}
      {channels.error ? <p role="alert">{channels.error}</p> : null}
      {channels.data ? (
        <>
          <ul className={styles.list}>
            {channels.data.items.map((channel) => (
              <li key={channel.channelId}>
                <span>
                  Canal {channel.channelId}
                  <br />
                  Propietario: {channel.ownerUserId}
                </span>
              </li>
            ))}
          </ul>
          {!channels.data.items.length ? <p>No hay canales en esta página.</p> : null}
          {channels.data.nextCursor ? (
            <Button onClick={() => setChannelCursor(channels.data!.nextCursor!)}>
              Siguiente página de canales
            </Button>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
