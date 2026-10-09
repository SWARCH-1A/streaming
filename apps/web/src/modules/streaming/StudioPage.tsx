import { useState } from 'react';
import type {
  ChannelBootstrap,
  IngestKey,
  PublicSession,
  StreamConfig,
  StreamCreated,
  StreamPatched,
} from '@contracts/p1';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { HlsPlayer } from '@/src/modules/streaming/components/HlsPlayer';
import { LiveMetadataForm } from '@/src/modules/streaming/components/LiveMetadataForm';
import { useCatalog } from '@/src/modules/taxonomy/entry';
import { errorMessage, HttpError, request } from '@/src/shared/api/http';
import { useLifetime } from '@/src/shared/api/useLifetime';
import { useResource } from '@/src/shared/api/useResource';
import { useSession } from '@/src/shell/session/useSession';

export function StudioPage() {
  const { user } = useSession();
  const lifetime = useLifetime();
  const [epoch, setEpoch] = useState(0),
    [secret, setSecret] = useState(''),
    [failure, setFailure] = useState(''),
    [pending, setPending] = useState(false),
    [visible, setVisible] = useState(false);
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
  return (
    <div className="stack">
      <h1>Estudio de emisión</h1>
      <nav className="row" aria-label="Herramientas del creador">
        <ActionLink to="/studio/channel">Mi canal & portada</ActionLink>
        <ActionLink to="/profile" variant="secondary">
          Mi perfil
        </ActionLink>
        <ActionLink to={`/channels/${channel.data?.handle ?? ''}`} variant="ghost">
          Ver canal
        </ActionLink>
      </nav>
      {configuration.error ? <Notice tone="error">{configuration.error}</Notice> : null}
      {failure ? <Notice tone="error">{failure}</Notice> : null}
      {config ? (
        <Panel className="stack">
          <Badge>
            {config.availability} · {config.status}
          </Badge>
          <p>ID de emisión: {config.streamId}</p>
          <p>{session.data?.viewerCount ?? 0} espectadores</p>
          {session.data ? (
            <HlsPlayer key={session.data.sessionId} session={session.data} title={config.title} />
          ) : null}
          <label htmlFor="ingest-url">Servidor RTMP</label>
          <Input id="ingest-url" value={config.rtmpUrl} readOnly />
          {secret ? (
            <>
              <label htmlFor="ingest-key">
                Clave de emisión (solo se muestra en esta respuesta)
              </label>
              <Input id="ingest-key" value={secret} type={visible ? 'text' : 'password'} readOnly />
              <Button
                variant="secondary"
                aria-pressed={visible}
                onClick={() => setVisible((previous) => !previous)}
              >
                {visible ? 'Ocultar clave' : 'Mostrar clave'}
              </Button>
              <Button variant="ghost" onClick={() => setSecret('')}>
                Descartar clave de esta vista
              </Button>
            </>
          ) : (
            <Notice>
              La clave anterior no se puede consultar. Rótala cuando la emisión esté offline o
              finalizada.
            </Notice>
          )}
          <Button
            variant="secondary"
            disabled={pending || !['OFFLINE', 'ENDED'].includes(config.status)}
            onClick={() => {
              void command('rotate');
            }}
          >
            Rotar clave
          </Button>
          <Button
            variant="danger"
            disabled={pending || !config.sessionId || config.status === 'ENDED'}
            onClick={() => {
              void command('stop');
            }}
          >
            Terminar emisión
          </Button>
        </Panel>
      ) : configuration.loading ? (
        <p role="status">Cargando configuración…</p>
      ) : null}
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
              const result = await request<StreamCreated>(`/api/channels/${channelId}/streams`, {
                method: 'POST',
                body: values,
                headers: { 'Idempotency-Key': key },
                signal,
              });
              setSecret(result.streamKey ?? '');
              setEpoch((previous) => previous + 1);
            }
          }}
        />
      ) : null}
    </div>
  );
}
