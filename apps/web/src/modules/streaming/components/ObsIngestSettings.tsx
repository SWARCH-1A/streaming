import { useState } from 'react';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { Notice } from '@/src/components/molecules/Notice';

import styles from './ObsIngestSettings.module.css';

export function ObsIngestSettings({
  rtmpUrl,
  streamKey,
  onDiscard,
}: {
  rtmpUrl: string;
  streamKey: string;
  onDiscard: () => void;
}) {
  const [visible, setVisible] = useState(false);
  const [message, setMessage] = useState('');

  async function copy(value: string, label: string) {
    try {
      await navigator.clipboard.writeText(value);
      setMessage(label);
    } catch {
      setMessage('No se pudo copiar. Selecciona el campo y copia el valor manualmente.');
    }
  }

  return (
    <div className="stack">
      <h2 className={styles.heading}>
        <Icon name="key" />
        Credenciales de emisión
      </h2>
      <p>
        Usa estos valores como servidor y clave de retransmisión en tu programa de emisión.
        Desactiva la autenticación por usuario y contraseña.
      </p>
      <label htmlFor="ingest-url">Servidor</label>
      <div className={styles.copyRow}>
        <Input id="ingest-url" value={rtmpUrl} readOnly />
        <Button
          variant="secondary"
          onClick={() => {
            void copy(rtmpUrl, 'Servidor copiado.');
          }}
        >
          <Icon name="copy" /> Copiar servidor
        </Button>
      </div>
      {streamKey ? (
        <>
          <label htmlFor="ingest-key">Clave de retransmisión (solo se muestra ahora)</label>
          <Input id="ingest-key" value={streamKey} type={visible ? 'text' : 'password'} readOnly />
          <p>La clave es privada y solo se muestra ahora. Guárdala en un lugar seguro.</p>
          <div className="row">
            <Button
              variant="secondary"
              onClick={() => {
                void copy(streamKey, 'Clave copiada.');
              }}
            >
              Copiar clave
            </Button>
            <Button
              variant="secondary"
              aria-pressed={visible}
              onClick={() => setVisible((previous) => !previous)}
            >
              {visible ? 'Ocultar clave' : 'Mostrar clave'}
            </Button>
            <Button
              variant="ghost"
              onClick={() => {
                setVisible(false);
                setMessage('');
                onDiscard();
              }}
            >
              Descartar clave de esta vista
            </Button>
          </div>
        </>
      ) : (
        <Notice>
          La clave anterior no se puede consultar. Rótala cuando la emisión esté offline o
          finalizada.
        </Notice>
      )}
      {message ? <p role="status">{message}</p> : null}
    </div>
  );
}
