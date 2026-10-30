import { useId, useRef, useState } from 'react';
import type { P2SubtitlePage, P2SubtitleTrack, P2Vod } from '@contracts/p2';

import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { Notice } from '@/src/components/molecules/Notice';
import { errorMessage, request } from '@/src/shared/api/http';

export function SubtitleManager({ vod }: { vod: P2Vod }) {
  const id = useId();
  const [tracks, setTracks] = useState<P2SubtitleTrack[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [language, setLanguage] = useState('es');
  const [label, setLabel] = useState('Español');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const keys = useRef(new Map<string, string>());
  const key = (fingerprint: string) => {
    let value = keys.current.get(fingerprint);
    if (!value) {
      value = crypto.randomUUID();
      keys.current.set(fingerprint, value);
    }
    return value;
  };
  async function refresh(cursor?: string) {
    const page = await request<P2SubtitlePage>(
      `/api/vods/${vod.vodId}/subtitles?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
    );
    setTracks((previous) =>
      cursor
        ? [
            ...previous.filter((item) => !page.items.some((next) => next.trackId === item.trackId)),
            ...page.items,
          ]
        : page.items,
    );
    setNextCursor(page.nextCursor);
  }
  async function upload() {
    if (!file || file.size > 1048576) {
      setError('Selecciona un archivo WebVTT de hasta 1 MiB.');
      return;
    }
    setPending(true);
    try {
      const metadata = { language, label, expectedMappingVersion: vod.mappingVersion };
      const bytes = await file.arrayBuffer();
      const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)))
        .map((value) => value.toString(16).padStart(2, '0'))
        .join('');
      const body = new FormData();
      body.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
      body.append('file', new Blob([bytes], { type: 'text/vtt' }), file.name);
      await request<P2SubtitleTrack>(`/api/vods/${vod.vodId}/subtitles`, {
        method: 'POST',
        coreMutation: true,
        headers: { 'Idempotency-Key': key(JSON.stringify(metadata) + digest) },
        body,
      });
      await refresh();
      setError('');
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setPending(false);
    }
  }
  async function remove(track: P2SubtitleTrack) {
    setPending(true);
    try {
      await request<void>(`/api/vods/${vod.vodId}/subtitles/${track.trackId}`, {
        method: 'DELETE',
        coreMutation: true,
        headers: {
          'Idempotency-Key': key(`delete:${track.trackId}:${track.trackVersion}`),
          'If-Match': `"${track.trackVersion}"`,
        },
      });
      await refresh();
      setError('');
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setPending(false);
    }
  }
  return (
    <details
      onToggle={(event) => {
        if (event.currentTarget.open)
          void refresh().catch((reason: unknown) => setError(errorMessage(reason)));
      }}
    >
      <summary>Subtítulos</summary>
      <div className="stack">
        <p>
          Prepara el archivo contra esta versión del video. Para corregir una pista, carga otra y
          retira la anterior.
        </p>
        <form
          className="stack"
          onSubmit={(event) => {
            event.preventDefault();
            void upload();
          }}
        >
          <label htmlFor={`${id}-language`}>
            Idioma
            <Input
              id={`${id}-language`}
              required
              value={language}
              onChange={(event) => setLanguage(event.target.value)}
              placeholder="es, en-US…"
            />
          </label>
          <label htmlFor={`${id}-label`}>
            Nombre de la pista
            <Input
              id={`${id}-label`}
              required
              maxLength={80}
              value={label}
              onChange={(event) => setLabel(event.target.value)}
            />
          </label>
          <label htmlFor={`${id}-file`}>
            Archivo WebVTT
            <Input
              id={`${id}-file`}
              required
              type="file"
              accept=".vtt,text/vtt"
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            />
          </label>
          <Button type="submit" disabled={pending}>
            Cargar pista
          </Button>
        </form>
        {tracks.map((track) => (
          <p key={track.trackId}>
            {track.label} · {track.language}{' '}
            <Button
              variant="secondary"
              disabled={pending}
              onClick={() => {
                void remove(track);
              }}
            >
              Retirar pista
            </Button>
          </p>
        ))}
        {nextCursor ? (
          <Button
            disabled={pending}
            onClick={() => {
              setPending(true);
              void refresh(nextCursor)
                .catch((reason: unknown) => setError(errorMessage(reason)))
                .finally(() => setPending(false));
            }}
          >
            Cargar más pistas
          </Button>
        ) : null}
        {error ? <Notice tone="error">{error}</Notice> : null}
      </div>
    </details>
  );
}
