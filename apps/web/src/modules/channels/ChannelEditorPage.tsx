import type { FormEvent } from 'react';
import { useState } from 'react';
import type { ChannelBootstrap } from '@contracts/p1';

import artwork from '@/public/images/signal-grid.svg';

import { fieldErrors } from '@/src/accessibility/fieldErrors';
import { useErrorFocus } from '@/src/accessibility/useErrorFocus';
import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { Textarea } from '@/src/components/atoms/Textarea';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { errorMessage, request, upload } from '@/src/shared/api/http';
import { useLifetime } from '@/src/shared/api/useLifetime';
import { useResource } from '@/src/shared/api/useResource';
import { codePointLength } from '@/src/shared/format';
import { useSession } from '@/src/shell/session/useSession';

import styles from './ChannelEditorPage.module.css';

export function ChannelEditorPage() {
  const { user } = useSession();
  const resource = useResource(`channel-editor:${user?.userId ?? ''}`, (signal) =>
    request<ChannelBootstrap>(`/api/channels/by-owner/${encodeURIComponent(user?.userId ?? '')}`, {
      signal,
    }),
  );
  if (resource.loading) return <p role="status">Cargando canal…</p>;
  if (!resource.data) return <Notice tone="error">{resource.error}</Notice>;
  return <Editor key={resource.data.channel.channelId} initial={resource.data} />;
}
function Editor({ initial }: { initial: ChannelBootstrap }) {
  const [channel, setChannel] = useState(initial.channel);
  const [description, setDescription] = useState(channel.description);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState('');
  const [failure, setFailure] = useState('');
  const [saved, setSaved] = useState(false);
  const [pending, setPending] = useState(false);
  const focusError = useErrorFocus(pending);
  const lifetime = useLifetime();
  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaved(false);
    setError('');
    setFailure('');
    if (codePointLength(description) > 500) {
      setError('La descripción admite hasta 500 caracteres.');
      focusError('channel-description');
      return;
    }
    const signal = lifetime();
    setPending(true);
    try {
      const image = file
        ? await upload(`/api/channels/${channel.channelId}/banner-uploads`, file, signal)
        : null;
      const updated = await request<ChannelBootstrap['channel']>(
        `/api/channels/${channel.channelId}`,
        {
          method: 'PATCH',
          body: { description, ...(image ? { bannerUploadId: image.uploadId } : {}) },
          coreMutation: true,
          signal,
        },
      );
      setChannel(updated);
      setFile(null);
      setSaved(true);
    } catch (error) {
      if (!signal.aborted) {
        setFailure(errorMessage(error));
        const next = fieldErrors(error, ['description'] as const);
        if (next.description) {
          setError(next.description);
          focusError('channel-description');
        }
      }
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }
  return (
    <div className={styles.editor}>
      <h1>Personalización del canal</h1>
      <p>@{initial.handle}</p>
      <div className={styles.banner}>
        <img
          src={channel.bannerUri || artwork}
          alt="Portada de tu canal"
          width="1280"
          height="480"
        />
        <div>
          <h2>{initial.profile.displayName}</h2>
          <p>@{initial.handle}</p>
        </div>
      </div>
      <Panel>
        <form
          onSubmit={(event) => {
            void submit(event);
          }}
          className="stack"
        >
          <fieldset disabled={pending} className="stack">
            <legend>Acerca de tu canal</legend>
            {failure ? <Notice tone="error">{failure}</Notice> : null}
            <FormField
              id="channel-description"
              label="Descripción del canal"
              hint={`${codePointLength(description)} / 500 caracteres`}
              error={error || undefined}
            >
              <Textarea
                id="channel-description"
                value={description}
                rows={5}
                onChange={(event) => {
                  setDescription(event.target.value);
                  setSaved(false);
                  setError('');
                }}
                aria-describedby={error ? 'channel-description-error' : 'channel-description-hint'}
                aria-invalid={!!error}
              />
            </FormField>
            <FormField id="banner-upload" label="Portada" hint="JPEG, PNG o GIF; hasta 10 MB.">
              <Input
                id="banner-upload"
                type="file"
                accept="image/jpeg,image/png,image/gif"
                aria-describedby="banner-upload-hint"
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
            </FormField>
            <Button type="submit">Guardar canal</Button>
            {saved ? <Notice tone="success">Canal actualizado.</Notice> : null}
          </fieldset>
        </form>
      </Panel>
      <ActionLink to="/studio">Volver al estudio</ActionLink>
    </div>
  );
}
