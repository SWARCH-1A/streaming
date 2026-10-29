import { useState } from 'react';
import { Link } from 'react-router';
import type { P2Notification, P2NotificationPage, P2Preferences } from '@contracts/p2';

import { Button } from '@/src/components/atoms/Button';
import { Checkbox } from '@/src/components/atoms/Checkbox';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { errorMessage, request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';
import { useSession } from '@/src/shell/session/useSession';

/** SPEC-20: inbox (refreshed every 5 s), mark as read and the general email/push switches. */
export function NotificationsPage() {
  const { user } = useSession();
  const userId = user?.userId ?? '';
  const [reload, setReload] = useState(0);
  const [extra, setExtra] = useState<P2Notification[]>([]);
  const [cursor, setCursor] = useState<string | null | undefined>(undefined);
  const [failure, setFailure] = useState('');
  const inbox = useResource(
    `inbox:${userId}:${reload}`,
    (signal) => request<P2NotificationPage>('/api/notifications?limit=20', { signal }),
    5000,
  );
  const first = inbox.data?.items ?? [];
  const next = cursor === undefined ? (inbox.data?.nextCursor ?? null) : cursor;
  const items = [
    ...first,
    ...extra.filter((n) => !first.some((item) => item.notificationId === n.notificationId)),
  ];

  async function more() {
    if (!next) return;
    try {
      const page = await request<P2NotificationPage>(
        `/api/notifications?limit=20&cursor=${encodeURIComponent(next)}`,
      );
      setExtra((current) => [...current, ...page.items]);
      setCursor(page.nextCursor);
    } catch (error) {
      setFailure(errorMessage(error));
    }
  }

  async function markRead(notification: P2Notification) {
    try {
      await request<P2Notification>(
        `/api/notifications/${encodeURIComponent(notification.notificationId)}/read`,
        { method: 'PUT', headers: { 'Idempotency-Key': crypto.randomUUID() }, coreMutation: true },
      );
      setExtra([]);
      setCursor(undefined);
      setReload((value) => value + 1);
    } catch (error) {
      setFailure(errorMessage(error));
    }
  }

  return (
    <div className="stack">
      <header>
        <p className="eyebrow">Tu actividad</p>
        <h1>Notificaciones</h1>
      </header>
      {failure ? <Notice tone="error">{failure}</Notice> : null}
      <Panel className="stack">
        <h2>Bandeja</h2>
        {inbox.loading ? <p role="status">Cargando avisos…</p> : null}
        {inbox.error ? <Notice tone="error">{inbox.error}</Notice> : null}
        {!inbox.loading && !inbox.error && !items.length ? (
          <p>Sin avisos todavía. Sigue canales y te avisaremos cuando salgan en vivo.</p>
        ) : null}
        <ul className="stack" aria-label="Avisos">
          {items.map((notification) => (
            <li key={notification.notificationId}>
              <p>
                {notification.readAtUtc ? null : <strong>Nuevo · </strong>}
                {/* SPEC-20 CA-09: a suspended channel keeps the record but offers no way into its content. */}
                {notification.available ? (
                  <Link to={notification.linkPath}>{notification.title}</Link>
                ) : (
                  <>
                    {notification.title} <em>(canal no disponible)</em>
                  </>
                )}
              </p>
              <p>
                <time dateTime={notification.createdAtUtc}>
                  {new Date(notification.createdAtUtc).toLocaleString('es-CO')}
                </time>
              </p>
              {notification.readAtUtc ? null : (
                <Button variant="ghost" size="sm" onClick={() => void markRead(notification)}>
                  Marcar como leído
                </Button>
              )}
            </li>
          ))}
        </ul>
        {next ? (
          <Button variant="secondary" onClick={() => void more()}>
            Ver más
          </Button>
        ) : null}
      </Panel>
      <MediumPreferences key={userId} />
    </div>
  );
}

function MediumPreferences() {
  const preferences = useResource('preferences', (signal) =>
    request<P2Preferences>('/api/notifications/preferences', { signal }),
  );
  const [saved, setSaved] = useState<P2Preferences | null>(null);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState('');
  const current = saved ?? preferences.data;

  async function change(field: 'emailEnabled' | 'pushEnabled', value: boolean) {
    if (!current) return;
    setPending(true);
    setFailure('');
    try {
      setSaved(
        await request<P2Preferences>('/api/notifications/preferences', {
          method: 'PUT',
          headers: { 'Idempotency-Key': crypto.randomUUID() },
          body: {
            emailEnabled: current.emailEnabled,
            pushEnabled: current.pushEnabled,
            [field]: value,
            expectedVersion: current.version,
          },
          coreMutation: true,
        }),
      );
    } catch (error) {
      setFailure(errorMessage(error));
    } finally {
      setPending(false);
    }
  }

  return (
    <Panel className="stack">
      <h2>Medios de aviso</h2>
      <p>
        La bandeja siempre está activa. El correo y el push se usan solo en los canales donde
        también los actives.
      </p>
      {preferences.error ? <Notice tone="error">{preferences.error}</Notice> : null}
      {failure ? <Notice tone="error">{failure}</Notice> : null}
      {current ? (
        <fieldset disabled={pending} className="stack">
          <legend>Medios</legend>
          <label>
            <Checkbox
              checked={current.emailEnabled}
              onChange={(event) => void change('emailEnabled', event.target.checked)}
            />{' '}
            Correo electrónico
          </label>
          <label>
            <Checkbox
              checked={current.pushEnabled}
              onChange={(event) => void change('pushEnabled', event.target.checked)}
            />{' '}
            Notificaciones push (requiere la app móvil)
          </label>
        </fieldset>
      ) : null}
    </Panel>
  );
}
