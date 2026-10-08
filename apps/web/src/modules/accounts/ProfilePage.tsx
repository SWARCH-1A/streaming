import type { FormEvent } from 'react';
import { useState } from 'react';

import banner from '@/public/images/studio-1.jpg';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Avatar } from '@/src/components/atoms/Avatar';
import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { Textarea } from '@/src/components/atoms/Textarea';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { codePointLength } from '@/src/shared/format';
import { useSession } from '@/src/shell/session/useSession';

import styles from './ProfilePage.module.css';
import { validateProfile } from './validation';

export function ProfilePage() {
  const { user, updateProfile } = useSession();
  const [displayName, setDisplayName] = useState(user?.displayName ?? 'Alex Mercer');
  const [bio, setBio] = useState(
    user?.bio ?? 'Tecnología, videojuegos y conversaciones en directo.',
  );
  const [errors, setErrors] = useState<{ displayName?: string; bio?: string }>({});
  const [saved, setSaved] = useState(false);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = validateProfile(displayName, bio);
    setErrors(next);
    setSaved(false);
    if (Object.keys(next).length) {
      document.getElementById(next.displayName ? 'displayName' : 'bio')?.focus();
      return;
    }
    updateProfile({ displayName, bio });
    setSaved(true);
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
          <Avatar name={displayName} size="lg" />
          <div>
            <h2>{displayName}</h2>
            <p>@{user?.handle ?? 'alex_mercer'}</p>
          </div>
        </div>
      </div>
      <Panel className={styles.editor}>
        <h2>Editor de perfil</h2>
        <p className="muted">Tu nombre y biografía aparecerán junto a tu canal.</p>
        {!user ? (
          <Notice>
            Estás viendo un perfil de ejemplo.{' '}
            <ActionLink to="/login" size="sm">
              Inicia una sesión de demostración
            </ActionLink>
          </Notice>
        ) : null}
        <form onSubmit={submit} className="stack" noValidate>
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
          {saved ? <Notice tone="success">Perfil actualizado en esta demostración.</Notice> : null}
        </form>
      </Panel>
    </div>
  );
}
