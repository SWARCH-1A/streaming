import type { FormEvent } from 'react';
import { useState } from 'react';
import type { Profile } from '@contracts/p1';

import banner from '@/public/images/studio-1.jpg';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Avatar } from '@/src/components/atoms/Avatar';
import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { Textarea } from '@/src/components/atoms/Textarea';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { errorMessage, request, upload } from '@/src/shared/api/http';
import { useLifetime } from '@/src/shared/api/useLifetime';
import { codePointLength } from '@/src/shared/format';
import { useSession } from '@/src/shell/session/useSession';

import styles from './ProfilePage.module.css';
import { validateProfile } from './validation';

export function ProfilePage() {
  const { user, refresh } = useSession();
  const lifetime = useLifetime();
  const [file, setFile] = useState<File | null>(null);
  const [failure, setFailure] = useState('');
  const [pending, setPending] = useState(false);
  const [displayName, setDisplayName] = useState(user?.displayName ?? '');
  const [bio, setBio] = useState(user?.bio ?? '');
  const [errors, setErrors] = useState<{ displayName?: string; bio?: string }>({});
  const [saved, setSaved] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = validateProfile(displayName, bio);
    setErrors(next);
    setSaved(false);
    if (Object.keys(next).length) {
      document.getElementById(next.displayName ? 'displayName' : 'bio')?.focus();
      return;
    }
    const signal = lifetime();
    setPending(true);
    setFailure('');
    try {
      const image = file ? await upload('/api/profile/me/avatar-uploads', file, signal) : null;
      await request<Profile>('/api/profile/me', {
        method: 'PATCH',
        body: { displayName, bio, ...(image ? { avatarUploadId: image.uploadId } : {}) },
        coreMutation: true,
        signal,
      });
      await refresh();
      signal.throwIfAborted();
      setFile(null);
      setSaved(true);
    } catch (error) {
      if (!signal.aborted) setFailure(errorMessage(error));
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }
  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Mi espacio</p>
        <h1>Personaliza tu perfil</h1>
      </div>
      <div className={styles.banner}>
        <img src={banner} alt="" />
        <div className="row">
          <Avatar
            name={displayName}
            {...(user?.avatarUri ? { src: user.avatarUri } : {})}
            size="lg"
          />
          <div>
            <h2>{displayName}</h2>
            <p>@{user?.handle ?? ''}</p>
          </div>
        </div>
      </div>
      <Panel className={styles.editor}>
        <h2>Editor de perfil</h2>
        <p className="muted">Tu nombre y biografía aparecerán junto a tu canal.</p>
        {!user ? (
          <Notice>
            Debes iniciar sesión.{' '}
            <ActionLink to="/login" size="sm">
              Inicia sesión
            </ActionLink>
          </Notice>
        ) : null}
        <form
          onSubmit={(event) => {
            void submit(event);
          }}
          className="stack"
          noValidate
        >
          <fieldset disabled={pending} className="stack">
            <legend className="sr-only">Perfil público</legend>
            {failure ? <Notice tone="error">{failure}</Notice> : null}
            <FormField id="avatar-upload" label="Avatar" hint="JPEG, PNG o GIF; hasta 10 MB.">
              <Input
                id="avatar-upload"
                type="file"
                accept="image/jpeg,image/png,image/gif"
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
            </FormField>
            <FormField
              id="displayName"
              label="Nombre visible"
              error={errors.displayName}
              hint="Hasta 50 caracteres."
            >
              <Input
                id="displayName"
                value={displayName}
                onChange={(event) => {
                  setDisplayName(event.target.value);
                  setSaved(false);
                }}
                aria-invalid={!!errors.displayName}
                aria-describedby={errors.displayName ? 'displayName-error' : 'displayName-hint'}
              />
            </FormField>
            <FormField
              id="bio"
              label="Biografía"
              error={errors.bio}
              hint={`${codePointLength(bio)} / 300 caracteres`}
            >
              <Textarea
                id="bio"
                value={bio}
                onChange={(event) => {
                  setBio(event.target.value);
                  setSaved(false);
                }}
                aria-invalid={!!errors.bio}
                aria-describedby={errors.bio ? 'bio-error' : 'bio-hint'}
              />
            </FormField>
            <Button type="submit" disabled={!user}>
              Guardar cambios
            </Button>
            {saved ? <Notice tone="success">Perfil actualizado.</Notice> : null}
          </fieldset>
        </form>
      </Panel>
    </div>
  );
}
