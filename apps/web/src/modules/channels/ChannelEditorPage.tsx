import type { FormEvent } from 'react';
import { useState } from 'react';

import banner from '@/public/images/studio-1.jpg';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Button } from '@/src/components/atoms/Button';
import { Textarea } from '@/src/components/atoms/Textarea';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { codePointLength } from '@/src/shared/format';
import { useSession } from '@/src/shell/session/useSession';

import styles from './ChannelEditorPage.module.css';

export function ChannelEditorPage() {
  const { user } = useSession();
  const [description, setDescription] = useState(
    'Un espacio para compartir tecnología, videojuegos y conversaciones en directo.',
  );
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (codePointLength(description) > 500) {
      setError('La descripción admite hasta 500 caracteres.');
      document.getElementById('channel-description')?.focus();
      return;
    }
    setError('');
    setSaved(true);
  }
  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Mi canal</p>
        <h1>Personalización del canal</h1>
      </div>
      <div className={styles.banner}>
        <img src={banner} alt="" />
        <div>
          <h2>{user?.displayName ?? 'Alex Mercer'}</h2>
          <p>@{user?.handle ?? 'alex_mercer'}</p>
        </div>
      </div>
      <Panel className={styles.editor}>
        <h2>Acerca de tu canal</h2>
        <Notice>
          Vista de demostración. La portada es un ejemplo; su carga se habilitará al conectar el
          servicio.
        </Notice>
        <form className="stack" onSubmit={submit} noValidate>
          <FormField
            id="channel-description"
            label="Descripción del canal"
            hint={`${codePointLength(description)} / 500 caracteres`}
            error={error || undefined}
          >
            <Textarea
              id="channel-description"
              rows={5}
              value={description}
              onChange={(event) => {
                setDescription(event.target.value);
                setSaved(false);
                setError('');
              }}
              aria-invalid={!!error}
              aria-describedby={error ? 'channel-description-error' : 'channel-description-hint'}
            />
          </FormField>
          <Button type="submit">Guardar descripción de ejemplo</Button>
          {saved ? (
            <Notice tone="success">Descripción guardada en esta vista de demostración.</Notice>
          ) : null}
        </form>
        <div className="row">
          <ActionLink to="/profile" variant="secondary">
            Editar perfil
          </ActionLink>
          <ActionLink to="/studio" variant="ghost">
            Volver al estudio
          </ActionLink>
        </div>
      </Panel>
    </div>
  );
}
