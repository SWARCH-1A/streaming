import { useEffect, useId, useRef, useState } from 'react';
import type { P2Autopublish } from '@contracts/p2';

import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { Notice } from '@/src/components/molecules/Notice';
import { errorMessage, request } from '@/src/shared/api/http';

export function RecordingSettings({ channelId }: { channelId: string }) {
  const id = useId();
  const [settings, setSettings] = useState<P2Autopublish | null>(null);
  const [enabled, setEnabled] = useState(true);
  const [failure, setFailure] = useState('');
  const [pending, setPending] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    void request<P2Autopublish>(`/api/channels/${channelId}/vod-settings`, {
      signal: controller.signal,
    }).then(
      (value) => {
        setSettings(value);
        setEnabled(value.enabled);
      },
      (reason: unknown) => {
        if (!controller.signal.aborted) setFailure(errorMessage(reason));
      },
    );
    return () => controller.abort();
  }, [channelId]);
  const keys = useRef(new Map<string, string>());
  async function save() {
    if (!settings) return;
    const fingerprint = `${channelId}:${settings.version}:${enabled}`;
    let key = keys.current.get(fingerprint);
    if (!key) {
      key = crypto.randomUUID();
      keys.current.set(fingerprint, key);
    }
    setPending(true);
    try {
      const result = await request<P2Autopublish>(`/api/channels/${channelId}/vod-settings`, {
        method: 'PUT',
        coreMutation: true,
        headers: { 'Idempotency-Key': key },
        body: { enabled, expectedVersion: settings.version },
      });
      setSettings(result);
      setFailure('');
    } catch (reason) {
      setFailure(errorMessage(reason));
    } finally {
      setPending(false);
    }
  }
  return (
    <section className="stack" aria-label="Publicación de grabaciones">
      <h2>Publicación de grabaciones</h2>
      <p>
        Las emisiones se graban automáticamente. Esta preferencia se aplica a las emisiones que
        inicies después de guardarla.
      </p>
      {settings ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <label htmlFor={id}>
            <Input
              id={id}
              type="checkbox"
              checked={enabled}
              onChange={(event) => setEnabled(event.target.checked)}
            />{' '}
            Publicar automáticamente al finalizar y validar la grabación
          </label>
          <Button type="submit" disabled={pending}>
            Guardar preferencia
          </Button>
        </form>
      ) : (
        <p>Cargando preferencia…</p>
      )}
      {failure ? <Notice tone="error">{failure}</Notice> : null}
    </section>
  );
}
