import type { PublicSession } from '@contracts/p1';

import { Notice } from '@/src/components/molecules/Notice';
import { HlsPlayer } from '@/src/modules/streaming/entry';
import { request } from '@/src/shared/api/http';

import { usePolling } from './usePolling';

/**
 * One independent player of a room. It only exists while Core reports its source as PLAYABLE for this
 * session: the parent unmounts it as soon as the state changes, the source is removed, the room closes
 * or the page is left, and HlsPlayer then stops hls.js and releases its viewer lease.
 */
export function PartySourcePlayer({
  sessionId,
  title,
  streamId,
}: {
  sessionId: string;
  title: string;
  streamId: string;
}) {
  const resource = usePolling(
    `party-session:${sessionId}`,
    (signal) =>
      request<PublicSession>(`/api/streams/sessions/${encodeURIComponent(sessionId)}`, { signal }),
    4000,
  );
  const session = resource.data;
  if (session && (session.sessionId !== sessionId || session.streamId !== streamId))
    return <Notice tone="warning">La sesión de esta transmisión cambió. Actualizando…</Notice>;
  if (!session)
    return resource.loading ? (
      <p role="status">Cargando reproductor…</p>
    ) : (
      <Notice tone="error">
        No se pudo cargar la transmisión. {resource.error ? <span>{resource.error}</span> : null}
      </Notice>
    );
  return (
    <>
      <HlsPlayer key={session.sessionId} session={session} title={title} />
      {resource.error ? (
        <Notice tone="warning">
          No se pudo actualizar el estado de esta transmisión; se mantiene la última conocida.
        </Notice>
      ) : null}
    </>
  );
}
