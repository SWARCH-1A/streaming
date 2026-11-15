import { useState } from 'react';
import { Link } from 'react-router';
import type { P2Notification, P2NotificationPage, P2Preferences } from '@contracts/p2';

import { Button } from '@/src/components/atoms/Button';
import { Checkbox } from '@/src/components/atoms/Checkbox';
import { Icon } from '@/src/components/atoms/Icon';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { errorMessage, request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';
import { useSession } from '@/src/shell/session/useSession';

import styles from './NotificationsPage.module.css';

const notificationDate = new Intl.DateTimeFormat('es-CO', {
  dateStyle: 'medium',
  timeStyle: 'short',
});

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
  const unreadCount = items.filter((notification) => !notification.readAtUtc).length;
  const hasInbox = inbox.data !== null;

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
    <div className={styles.page}>
      <header className={styles.hero}>
        <span className={styles.heroIcon}>
          <Icon name="bell" size={28} />
        </span>
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>Centro de avisos</p>
          <h1>Notificaciones</h1>
          <p>Entérate cuando tus canales favoritos comiencen una emisión en directo.</p>
        </div>
        <div className={styles.heroStatus} aria-label="Resumen de notificaciones">
          <strong className={styles.statusValue}>{hasInbox ? unreadCount : '—'}</strong>
          <span className={styles.statusLabel}>
            {hasInbox ? (unreadCount === 1 ? 'aviso sin leer' : 'avisos sin leer') : 'pendientes'}
          </span>
        </div>
      </header>
      {failure ? <Notice tone="error">{failure}</Notice> : null}
      <Panel className={`${styles.panel} ${styles.inboxPanel}`} aria-busy={inbox.loading}>
        <div className={styles.sectionHeader}>
          <div className={styles.sectionIdentity}>
            <span className={styles.sectionIcon}>
              <Icon name="bell" size={21} />
            </span>
            <div className={styles.sectionCopy}>
              <p className={styles.eyebrow}>Actividad reciente</p>
              <h2>Bandeja</h2>
              <p>Revisa tus avisos y vuelve directamente a la emisión que los originó.</p>
            </div>
          </div>
          {unreadCount ? (
            <span className={styles.unreadBadge}>
              {unreadCount} {unreadCount === 1 ? 'aviso sin leer' : 'avisos sin leer'}
            </span>
          ) : hasInbox ? (
            <span className={styles.allReadBadge}>
              <Icon name="check" size={14} /> Todo al día
            </span>
          ) : null}
        </div>

        {inbox.loading && !items.length ? (
          <div className={styles.loadingState} role="status">
            <span>Cargando avisos…</span>
            <div className={styles.skeletonList} aria-hidden="true">
              <span />
              <span />
              <span />
            </div>
          </div>
        ) : null}
        {inbox.error ? <Notice tone="error">{inbox.error}</Notice> : null}
        {!inbox.loading && !inbox.error && !items.length ? (
          <div className={styles.emptyState}>
            <span className={styles.emptyIcon}>
              <Icon name="bell" size={26} />
            </span>
            <h3>Tu bandeja está tranquila</h3>
            <p>Sigue canales para recibir un aviso cuando salgan en vivo.</p>
            <Link className={styles.emptyLink} to="/search">
              Explorar canales <Icon name="arrow" size={16} />
            </Link>
          </div>
        ) : null}
        {items.length ? (
          <ul className={styles.list} aria-label="Avisos">
            {items.map((notification) => {
              const unread = !notification.readAtUtc;
              return (
                <li
                  key={notification.notificationId}
                  className={`${styles.notification} ${unread ? styles.unread : ''}`}
                >
                  <span
                    className={`${styles.notificationIcon} ${notification.available ? '' : styles.unavailableIcon}`}
                  >
                    <Icon name={notification.available ? 'broadcast' : 'alert'} size={19} />
                  </span>
                  <div className={styles.notificationBody}>
                    <div className={styles.notificationMeta}>
                      <span className={styles.notificationKind}>
                        <Icon name="broadcast" size={14} /> Emisión en vivo
                      </span>
                      {unread ? (
                        <span className={styles.newBadge}>Sin leer</span>
                      ) : (
                        <span className={styles.readState}>
                          <Icon name="check" size={13} /> Leído
                        </span>
                      )}
                    </div>
                    <div className={styles.notificationTitle}>
                      {notification.available ? (
                        <Link to={notification.linkPath}>{notification.title}</Link>
                      ) : (
                        <span>{notification.title}</span>
                      )}
                    </div>
                    <div className={styles.notificationDetails}>
                      <time dateTime={notification.createdAtUtc}>
                        {notificationDate.format(new Date(notification.createdAtUtc))}
                      </time>
                      <span aria-hidden="true">·</span>
                      <span className={notification.available ? '' : styles.unavailableText}>
                        {notification.available ? 'Canal disponible' : 'Canal no disponible'}
                      </span>
                    </div>
                  </div>
                  <div className={styles.notificationActions}>
                    {notification.available ? (
                      <Link className={styles.openLink} to={notification.linkPath}>
                        Ver emisión <Icon name="arrow" size={15} />
                      </Link>
                    ) : (
                      <span className={styles.disabledAction}>
                        Sin acceso <Icon name="alert" size={14} />
                      </span>
                    )}
                    {unread ? (
                      <Button
                        className={styles.readButton}
                        variant="ghost"
                        size="sm"
                        aria-label={`Marcar como leída: ${notification.title}`}
                        onClick={() => void markRead(notification)}
                      >
                        <Icon name="check" size={15} />
                        Marcar leída
                      </Button>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ul>
        ) : null}
        {next ? (
          <div className={styles.pagination}>
            <Button variant="secondary" onClick={() => void more()}>
              Ver más notificaciones <Icon name="chevron" size={16} />
            </Button>
            <span className={styles.paginationHint}>Hay más avisos en tu historial.</span>
          </div>
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
    <Panel className={`${styles.panel} ${styles.preferencesPanel}`}>
      <div className={styles.sectionHeader}>
        <div className={styles.sectionIdentity}>
          <span className={styles.sectionIcon}>
            <Icon name="settings" size={21} />
          </span>
          <div className={styles.sectionCopy}>
            <p className={styles.eyebrow}>Preferencias</p>
            <h2>Cómo quieres recibirlas</h2>
            <p>La bandeja siempre está activa. Elige si también quieres avisos externos.</p>
          </div>
        </div>
      </div>
      {preferences.loading ? <p role="status">Cargando preferencias…</p> : null}
      {preferences.error ? <Notice tone="error">{preferences.error}</Notice> : null}
      {failure ? <Notice tone="error">{failure}</Notice> : null}
      {current ? (
        <fieldset disabled={pending} className={styles.preferenceList} aria-busy={pending}>
          <legend className="sr-only">Medios de aviso</legend>
          <label
            htmlFor="notifications-email"
            className={`${styles.preferenceOption} ${current.emailEnabled ? styles.preferenceEnabled : ''}`}
          >
            <span className={styles.preferenceIcon}>
              <Icon name="send" size={18} />
            </span>
            <span className={styles.preferenceCopy}>
              <strong>Correo electrónico</strong>
              <span>Recibe un mensaje cuando un canal seguido comience a emitir.</span>
            </span>
            <Checkbox
              id="notifications-email"
              aria-label="Activar avisos por correo electrónico"
              checked={current.emailEnabled}
              onChange={(event) => void change('emailEnabled', event.target.checked)}
            />
          </label>
          <label
            htmlFor="notifications-push"
            className={`${styles.preferenceOption} ${current.pushEnabled ? styles.preferenceEnabled : ''}`}
          >
            <span className={styles.preferenceIcon}>
              <Icon name="broadcast" size={18} />
            </span>
            <span className={styles.preferenceCopy}>
              <strong>Notificaciones push</strong>
              <span>Requiere tener la aplicación móvil configurada.</span>
            </span>
            <Checkbox
              id="notifications-push"
              aria-label="Activar notificaciones push"
              checked={current.pushEnabled}
              onChange={(event) => void change('pushEnabled', event.target.checked)}
            />
          </label>
        </fieldset>
      ) : null}
      <p className={styles.preferenceHint}>
        <Icon name="info" size={16} />
        <span>Los cambios se guardan automáticamente y se aplican a los avisos nuevos.</span>
      </p>
    </Panel>
  );
}
