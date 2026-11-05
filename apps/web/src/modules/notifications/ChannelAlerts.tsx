import { useState } from 'react';
import type { P2ChannelPreference } from '@contracts/p2';

import { Checkbox } from '@/src/components/atoms/Checkbox';
import { errorMessage, request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';

/** Per-channel email switch shown to followers; it works together with the general switch. */
export function ChannelAlerts({ channelId }: { channelId: string }) {
  const path = `/api/notifications/channels/${encodeURIComponent(channelId)}/preference`;
  const preference = useResource(`alerts:${channelId}`, (signal) =>
    request<P2ChannelPreference>(path, { signal }),
  );
  const [saved, setSaved] = useState<P2ChannelPreference | null>(null);
  const [failure, setFailure] = useState('');
  const current = saved ?? preference.data;
  if (!current) return null;

  async function change(emailEnabled: boolean) {
    if (!current) return;
    setFailure('');
    try {
      setSaved(
        await request<P2ChannelPreference>(path, {
          method: 'PUT',
          headers: { 'Idempotency-Key': crypto.randomUUID() },
          body: {
            emailEnabled,
            pushEnabled: current.pushEnabled,
            expectedVersion: current.version,
          },
          coreMutation: true,
        }),
      );
    } catch (error) {
      setFailure(errorMessage(error));
    }
  }

  return (
    <div>
      <label>
        <Checkbox
          checked={current.emailEnabled}
          onChange={(event) => void change(event.target.checked)}
        />{' '}
        Avisarme por correo cuando salga en vivo
      </label>
      {failure ? <p role="alert">{failure}</p> : null}
    </div>
  );
}
