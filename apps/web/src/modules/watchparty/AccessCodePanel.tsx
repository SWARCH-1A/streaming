import { useState } from 'react';

import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';

import { joinLink } from './api';

/** Shows a freshly issued access code. Core never returns it again; only this tab's memory holds it. */
export function AccessCodePanel({ code, onHide }: { code: string; onHide: () => void }) {
  const [copied, setCopied] = useState<'' | 'code' | 'link' | 'failed'>('');
  const link = joinLink(window.location.origin, code);
  async function copy(kind: 'code' | 'link') {
    try {
      await navigator.clipboard.writeText(kind === 'code' ? code : link);
      setCopied(kind);
    } catch {
      setCopied('failed');
    }
  }
  return (
    <Panel className="stack" role="region" aria-labelledby="access-code-title">
      <h2 id="access-code-title">Código de acceso</h2>
      <Notice tone="warning">
        Cópialo ahora: no se volverá a mostrar. Quien tenga el código puede entrar a la sala; si lo
        pierdes o se filtra, rótalo para invalidar el anterior.
      </Notice>
      <label htmlFor="access-code-value">
        Código
        <Input id="access-code-value" readOnly value={code} onFocus={(e) => e.target.select()} />
      </label>
      <label htmlFor="access-link-value">
        Enlace para compartir
        <Input id="access-link-value" readOnly value={link} onFocus={(e) => e.target.select()} />
      </label>
      <div className="row">
        <Button
          variant="secondary"
          onClick={() => {
            void copy('code');
          }}
        >
          Copiar código
        </Button>
        <Button
          variant="secondary"
          onClick={() => {
            void copy('link');
          }}
        >
          Copiar enlace
        </Button>
        <Button variant="ghost" onClick={onHide}>
          Ya lo guardé, ocultar
        </Button>
      </div>
      <p role="status" className="muted">
        {copied === 'code'
          ? 'Código copiado.'
          : copied === 'link'
            ? 'Enlace copiado.'
            : copied === 'failed'
              ? 'No se pudo copiar automáticamente: selecciónalo y cópialo a mano.'
              : ''}
      </p>
    </Panel>
  );
}
