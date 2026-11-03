import { useEffect, useRef, useState } from 'react';
import type { P2AccountAdmin, P2AccountAdminPage, P2ChannelAdminPage } from '@contracts/p2/index';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Textarea } from '@/src/components/atoms/Textarea';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';

import styles from './AdministrationPage.module.css';
import { isAdminRequired, readableAdminError } from './administrationError';
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
  const accessDenied = isAdminRequired(users.error) || isAdminRequired(channels.error);

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
      setNotice('El estado de la cuenta se guardó.');
      setRevision((n) => n + 1);
      heading.current?.focus();
    } catch (cause) {
      setError(readableAdminError(cause));
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
      <header className={styles.hero}>
        <span className={styles.heroIcon}>
          <Icon name="shield" size={28} />
        </span>
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>Centro de control</p>
          <h1 ref={heading} tabIndex={-1}>
            Administración
          </h1>
          <p>Consulta cuentas y canales, y revisa retiradas de contenido publicado.</p>
        </div>
        <Button className={styles.refresh} variant="secondary" onClick={refresh} disabled={busy}>
          <Icon name="refresh" size={17} />
          Actualizar listados
        </Button>
      </header>

      {accessDenied ? (
        <Notice tone="warning">
          <strong>Tu cuenta no tiene permisos administrativos.</strong> Los listados de usuarios y
          canales requieren un rol administrativo asignado. La autorización se conserva en el
          servidor.
        </Notice>
      ) : null}
      {notice ? <Notice tone="success">{notice}</Notice> : null}
      {users.loading || channels.loading ? (
        <p className={styles.loading} role="status">
          Cargando información de administración…
        </p>
      ) : null}
      {users.error && !isAdminRequired(users.error) ? (
        <Notice tone="error">{users.error}</Notice>
      ) : null}
      {channels.error && !isAdminRequired(channels.error) ? (
        <Notice tone="error">{channels.error}</Notice>
      ) : null}

      {users.data ? (
        <section className={styles.panel} aria-labelledby="admin-users-heading">
          <div className={styles.sectionHeader}>
            <span className={styles.sectionIcon}>
              <Icon name="users" size={21} />
            </span>
            <div className={styles.sectionCopy}>
              <p className={styles.eyebrow}>Cuentas y acceso</p>
              <h2 id="admin-users-heading">Usuarios</h2>
              <p>Revisa el estado de las cuentas y aplica cambios con un motivo.</p>
            </div>
            <span className={styles.count}>
              {users.data.items.length} en esta página
            </span>
          </div>
          {users.data.items.length ? (
            <ul className={styles.list}>
              {users.data.items.map((account) => (
                <li key={account.userId}>
                  <div className={styles.accountInfo}>
                    <span className={styles.avatar}>
                      <Icon name="user" size={18} />
                    </span>
                    <span className={styles.accountName}>{account.handle}</span>
                    <span
                      className={`${styles.status} ${account.status === 'ACTIVE' ? styles.active : styles.suspended}`}
                    >
                      {account.status === 'ACTIVE' ? 'Activa' : 'Suspendida'}
                    </span>
                  </div>
                  <Button
                    className={styles.rowAction}
                    size="sm"
                    variant={account.status === 'ACTIVE' ? 'danger' : 'secondary'}
                    disabled={busy}
                    onClick={() => choose(account)}
                  >
                    {account.status === 'ACTIVE' ? 'Suspender cuenta' : 'Reactivar cuenta'}
                  </Button>
                </li>
              ))}
            </ul>
          ) : (
            <div className={styles.empty}>
              <Icon name="users" size={24} />
              <p>No hay usuarios en esta página.</p>
            </div>
          )}
          {users.data.nextCursor ? (
            <div className={styles.pagination}>
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() => {
                  setSelected(null);
                  setCursor(users.data!.nextCursor!);
                }}
              >
                Ver siguiente página
                <Icon name="chevron" size={16} />
              </Button>
            </div>
          ) : null}
        </section>
      ) : null}

      {selected ? (
        <form
          onSubmit={(event) => {
            void save(event);
          }}
          className={`${styles.panel} ${styles.editPanel}`}
        >
          <div className={styles.sectionHeader}>
            <span className={styles.sectionIcon}>
              <Icon name="shield" size={21} />
            </span>
            <div className={styles.sectionCopy}>
              <p className={styles.eyebrow}>Cambio de estado</p>
              <h2>
                {selected.status === 'ACTIVE' ? 'Suspender' : 'Reactivar'} {selected.handle}
              </h2>
              <p>El motivo se registra para dejar trazabilidad de la decisión.</p>
            </div>
          </div>
          <FormField
            id="admin-reason"
            label="Motivo"
            hint="Obligatorio, máximo 500 caracteres. No incluyas contraseñas ni credenciales."
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
          {error ? <Notice tone="error">{error}</Notice> : null}
          <div className={styles.actions}>
            <Button type="submit" loading={busy}>
              Confirmar cambio
            </Button>
            <Button
              variant="secondary"
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

      {channels.data ? (
        <section className={styles.panel} aria-labelledby="admin-channels-heading">
          <div className={styles.sectionHeader}>
            <span className={styles.sectionIcon}>
              <Icon name="screen" size={21} />
            </span>
            <div className={styles.sectionCopy}>
              <p className={styles.eyebrow}>Contenido de la comunidad</p>
              <h2 id="admin-channels-heading">Canales</h2>
              <p>Consulta los canales registrados en la plataforma.</p>
            </div>
            <span className={styles.count}>{channels.data.items.length} en esta página</span>
          </div>
          {channels.data.items.length ? (
            <ul className={`${styles.list} ${styles.channelList}`}>
              {channels.data.items.map((channel) => (
                <li key={channel.channelId}>
                  <span className={styles.channelMark}>
                    <Icon name="broadcast" size={18} />
                  </span>
                  <div className={styles.channelInfo}>
                    <span className={styles.accountName}>Canal</span>
                    <span className={styles.identifier}>{channel.channelId}</span>
                  </div>
                  <div className={styles.channelInfo}>
                    <span className={styles.detailLabel}>Propietario</span>
                    <span className={styles.identifier}>{channel.ownerUserId}</span>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <div className={styles.empty}>
              <Icon name="screen" size={24} />
              <p>No hay canales en esta página.</p>
            </div>
          )}
          {channels.data.nextCursor ? (
            <div className={styles.pagination}>
              <Button
                variant="secondary"
                onClick={() => setChannelCursor(channels.data!.nextCursor!)}
              >
                Ver siguiente página
                <Icon name="chevron" size={16} />
              </Button>
            </div>
          ) : null}
        </section>
      ) : null}
    </section>
  );
}
