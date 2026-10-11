import { useState } from 'react';

import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { Notice } from '@/src/components/molecules/Notice';
import { obsIngest } from '@/src/modules/streaming/obsIngest';

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
  const settings = obsIngest(rtmpUrl, streamKey);

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
      <h2>Configuración para OBS</h2>
      <p>
        En Ajustes → Emisión, selecciona Servicio: Personalizado. Pega estos valores en «Servidor» y
        «Clave de retransmisión» y desactiva «Usar autenticación».
      </p>
      <label htmlFor="ingest-url">Servidor OBS</label>
      <Input id="ingest-url" value={settings.server} readOnly />
      <Button
        variant="secondary"
        onClick={() => {
          void copy(settings.server, 'Servidor copiado.');
        }}
      >
        Copiar servidor
      </Button>
      {streamKey ? (
        <>
          <label htmlFor="ingest-key">Clave de retransmisión OBS (solo se muestra ahora)</label>
          <Input
            id="ingest-key"
            value={settings.key}
            type={visible ? 'text' : 'password'}
            readOnly
          />
          <p>La clave incluye todos los datos de publicación. Cópiala completa en OBS.</p>
          <div className="row">
            <Button
              variant="secondary"
              onClick={() => {
                void copy(settings.key, 'Clave copiada.');
              }}
            >
              Copiar clave para OBS
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
