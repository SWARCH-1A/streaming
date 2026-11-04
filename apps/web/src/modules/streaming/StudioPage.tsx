import { useState } from 'react';
import type {
  ChannelBootstrap,
  IngestKey,
  PublicSession,
  StreamConfig,
  StreamCreated,
  StreamPatched,
} from '@contracts/p1';

import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { BroadcastPlaceholder } from '@/src/components/molecules/BroadcastPlaceholder';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { LiveChatPanel } from '@/src/modules/chat/entry';
import { HlsPlayer } from '@/src/modules/streaming/components/HlsPlayer';
import { LiveMetadataForm } from '@/src/modules/streaming/components/LiveMetadataForm';
import { ObsIngestSettings } from '@/src/modules/streaming/components/ObsIngestSettings';
import { useCatalog } from '@/src/modules/taxonomy/entry';
import { errorMessage, HttpError, request } from '@/src/shared/api/http';
import { useLifetime } from '@/src/shared/api/useLifetime';
import { useResource } from '@/src/shared/api/useResource';
import { useSession } from '@/src/shell/session/useSession';

import styles from './StudioPage.module.css';

export function StudioPage() {
  const { user } = useSession();
  const lifetime = useLifetime();
  const [epoch, setEpoch] = useState(0),
    [secret, setSecret] = useState(''),
    [failure, setFailure] = useState(''),
    [pending, setPending] = useState(false);
  const catalog = useCatalog();
  const channel = useResource(
    `studio-channel:${user?.userId ?? ''}`,
    (signal) =>
      request<ChannelBootstrap>(`/api/channels/by-owner/${user?.userId ?? ''}`, { signal }),
    4000,
  );
  const channelId = channel.data?.channel.channelId;
  const configuration = useResource(
    `studio:${channelId ?? ''}:${epoch}`,
    async (signal) => {
      if (!channelId) return null;
      try {
        return await request<StreamConfig>(`/api/channels/${channelId}/streams`, { signal });
      } catch (error) {
        if (error instanceof HttpError && error.status === 404) return null;
        throw error;
      }
    },
    4000,
  );
  const config = configuration.data;
  const session = useResource(
    `studio-session:${config?.sessionId ?? ''}`,
    async (signal) =>
      config?.sessionId
        ? request<PublicSession>(`/api/streams/sessions/${config.sessionId}`, { signal })
        : null,
    4000,
  );
  async function command(action: 'rotate' | 'stop') {
    if (!config) return;
    const signal = lifetime();
    setPending(true);
    setFailure('');
    try {
      if (action === 'rotate') {
        const result = await request<IngestKey>(
          `/api/streams/${config.streamId}/ingest-keys/rotate`,
          { method: 'POST', signal },
        );
        setSecret(result.streamKey);
      } else if (config.sessionId) {
        await request(`/api/streams/sessions/${config.sessionId}`, { method: 'DELETE', signal });
        setSecret('');
      }
      setEpoch((previous) => previous + 1);
    } catch (error) {
      if (!signal.aborted) setFailure(errorMessage(error));
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }
  if (channel.loading) return <p role="status">Cargando estudio…</p>;
  if (!channelId) return <Notice tone="error">{channel.error}</Notice>;
  const availability = session.data?.availability ?? config?.availability;
  const status = session.data?.status ?? config?.status ?? 'OFFLINE';
  const signalLabel =
    status === 'ENDED'
      ? 'Emisión finalizada'
      : availability === 'PLAYABLE'
        ? 'En vivo'
        : availability === 'RECONNECTING'
          ? 'Reconectando'
          : status === 'PREPARING'
            ? 'Conectando'
            : 'Offline';
  const seconds = Math.floor((session.data?.timelinePositionMs ?? 0) / 1000);
  const elapsed = [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map((value) => String(value).padStart(2, '0'))
    .join(':');
  return (
    <div className={styles.page}>
      <header className={styles.heading}>
        <h1>Estudio de emisión</h1>
      </header>
      {configuration.error ? <Notice tone="error">{configuration.error}</Notice> : null}
      {failure ? <Notice tone="error">{failure}</Notice> : null}
      <Panel className={styles.statusStrip}>
        <div className={styles.signal}>
          <Icon name="broadcast" />
          <span>Estado de señal</span>
        </div>
        <div className={styles.states} role="status" aria-label={`Estado de señal: ${signalLabel}`}>
          {['Offline', 'Conectando', 'En vivo', 'Reconectando', 'Emisión finalizada'].map(
            (label) => (
              <span
                key={label}
                className={
                  label === signalLabel
                    ? label === 'En vivo'
                      ? styles.liveState
                      : styles.activeState
                    : undefined
                }
              >
                {label}
              </span>
            ),
          )}
        </div>
        <div className={styles.metrics}>
          <div>
            <span>Tiempo al aire</span>
            <strong>{elapsed}</strong>
          </div>
          <div>
            <span>Espectadores</span>
            <strong>{session.data?.viewerCount ?? 0}</strong>
          </div>
          <Button
            variant="danger"
            disabled={pending || !config?.sessionId || config.status === 'ENDED'}
            onClick={() => {
              void command('stop');
            }}
          >
            Terminar emisión
          </Button>
        </div>
      </Panel>
      <div className={styles.grid}>
        <Panel className="stack">
          <section className="stack" aria-label="Vista previa de la emisión">
            <div className={styles.heading}>
              <h2 className={styles.monitorTitle}>
                <Icon name="screen" />
                Monitor de retorno
              </h2>
              <Badge tone={availability === 'PLAYABLE' ? 'live' : 'neutral'}>{signalLabel}</Badge>
            </div>
            <div className={styles.previewChatGrid}>
              <div className={styles.videoColumn}>
                {session.data ? (
                  <HlsPlayer
                    key={session.data.sessionId}
                    session={session.data}
                    title={config?.title ?? 'tu emisión'}
                    poster={channel.data?.channel.bannerUri}
                  />
                ) : (
                  <BroadcastPlaceholder
                    title="Tu próxima emisión empieza aquí"
                    description="Prepara el título y la categoría. Cuando llegue la señal, podrás verla en este espacio."
                    image={channel.data?.channel.bannerUri}
                  />
                )}
                <div className={styles.metrics}>
                  <span>@{channel.data?.handle}</span>
                  <span>{session.data?.viewerCount ?? 0} espectadores</span>
                  <span>Señal de reproducción</span>
                </div>
              </div>
              <section className={styles.chatColumn} aria-label="Chat de la emisión">
                {session.data ? (
                  <LiveChatPanel
                    key={`${session.data.sessionId}:${user?.userId ?? 'anonymous'}`}
                    sessionId={session.data.sessionId}
                    readOnly={
                      session.data.status === 'ENDED' || session.data.status === 'PREPARING'
                    }
                    channelId={channelId}
                    moderator={!!user && user.userId === channel.data?.channel.ownerUserId}
                  />
                ) : (
                  <div className={styles.chatPlaceholder}>
                    <Icon name="chat" size={24} />
                    <h3>Chat en vivo</h3>
                    <p>Estará disponible cuando inicies la transmisión.</p>
                  </div>
                )}
              </section>
            </div>
          </section>
        </Panel>
        <aside className="stack" aria-label="Metadatos de emisión">
          {catalog.error ? <Notice tone="error">{catalog.error}</Notice> : null}
          {catalog.data && !configuration.loading && !configuration.error ? (
            <LiveMetadataForm
              key={config?.streamId ?? 'new'}
              initial={config ?? { title: '', categoryId: '', tagIds: [] }}
              catalog={catalog.data}
              labels={channel.data?.stream ?? null}
              creating={!config}
              save={async (values, patch, key, signal) => {
                if (config)
                  await request<StreamPatched>(`/api/streams/${config.streamId}`, {
                    method: 'PATCH',
                    body: patch,
                    signal,
                  });
                else {
                  const result = await request<StreamCreated>(
                    `/api/channels/${channelId}/streams`,
                    {
                      method: 'POST',
                      body: values,
                      headers: { 'Idempotency-Key': key },
                      signal,
                    },
                  );
                  setSecret(result.streamKey ?? '');
                  setEpoch((previous) => previous + 1);
                }
              }}
            />
          ) : null}
        </aside>
        <section className="stack" aria-label="Conexión de emisión">
          <Panel className={`stack ${styles.connection}`}>
            {config ? (
              <>
                <ObsIngestSettings
                  rtmpUrl={config.rtmpUrl}
                  streamKey={secret}
                  onDiscard={() => setSecret('')}
                />
                <div className={styles.actions}>
                  <Button
                    variant="secondary"
                    disabled={pending || !['OFFLINE', 'ENDED'].includes(config.status)}
                    onClick={() => {
                      void command('rotate');
                    }}
                  >
                    Rotar clave
                  </Button>
                </div>
              </>
            ) : (
              <>
                <p className="eyebrow">Antes de salir en vivo</p>
                <h2>Prepara tu directo</h2>
                <ol className={styles.steps}>
                  <li>Elige un título, una categoría y las etiquetas de tu emisión.</li>
                  <li>Copia el servidor y la clave en tu programa de emisión.</li>
                  <li>Inicia la transmisión. La señal aparecerá en la vista previa.</li>
                </ol>
                {configuration.loading ? <p role="status">Cargando configuración…</p> : null}
              </>
            )}
          </Panel>
          <p className={styles.note}>
            La vista previa muestra la señal que reciben tus espectadores.
          </p>
        </section>
      </div>
    </div>
  );
}
