import { useEffect, useRef, useState } from 'react';
import type { P2Accepted, P2ContentRemoval, P2OperationStatus, P2Vod } from '@contracts/p2';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { Textarea } from '@/src/components/atoms/Textarea';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { HttpError, request } from '@/src/shared/api/http';

import { readableAdminError } from './administrationError';
import styles from './AdministrationPage.module.css';

export function VodRemoval() {
  const [id, setId] = useState('');
  const [vod, setVod] = useState<P2Vod | null>(null);
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState('');
  const [fieldError, setFieldError] = useState('');
  const [accepted, setAccepted] = useState<P2Accepted | null>(null);
  const [status, setStatus] = useState<P2OperationStatus | null>(null);
  const command = useRef<{ key: string; body: P2ContentRemoval } | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const idField = useRef<HTMLInputElement>(null);
  const focusLookup = useRef(false);
  const reasonField = useRef<HTMLTextAreaElement>(null);
  const result = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (vod) reasonField.current?.focus();
  }, [vod]);
  useEffect(() => {
    if (accepted) result.current?.focus();
  }, [accepted]);
  useEffect(() => {
    if (!busy && !vod && focusLookup.current) {
      focusLookup.current = false;
      idField.current?.focus();
    }
  }, [busy, vod]);

  async function lookup(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || uncertain) return;
    setError('');
    setVod(null);
    setAccepted(null);
    setStatus(null);
    setReason('');
    setConfirmed(false);
    setFieldError('');
    command.current = null;
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(id.trim())) {
      setError('Introduce el identificador del VOD publicado, no su URL.');
      event.currentTarget.querySelector<HTMLInputElement>('input')?.focus();
      return;
    }
    setBusy(true);
    try {
      const value = await request<P2Vod>(`/api/vods/${encodeURIComponent(id.trim())}`);
      if (
        value.publicationState !== 'PUBLISHED' ||
        value.processingState !== 'READY' ||
        value.restriction !== 'NONE'
      )
        throw new Error('Esta consulta solo admite grabaciones publicadas y disponibles.');
      setVod(value);
    } catch (cause) {
      setError(readableAdminError(cause));
    } finally {
      setBusy(false);
    }
  }

  async function remove(event: React.FormEvent) {
    event.preventDefault();
    if (!vod || busy) return;
    if (!reason.trim()) {
      setFieldError('Indica el motivo de la retirada.');
      reasonField.current?.focus();
      return;
    }
    if (!confirmed) return;
    if (!uncertain)
      command.current = {
        key: crypto.randomUUID(),
        body: {
          resourceType: 'VOD',
          resourceId: vod.vodId,
          expectedVersion: vod.version,
          reason: reason.trim(),
        },
      };
    if (!command.current) return;
    setBusy(true);
    setError('');
    setFieldError('');
    try {
      const response = await request<P2Accepted>('/api/admin/content-removals', {
        method: 'POST',
        coreMutation: true,
        body: command.current.body,
        headers: { 'Idempotency-Key': command.current.key },
      });
      setAccepted(response);
      setUncertain(false);
      setVod(null);
    } catch (cause) {
      const ambiguous = !(cause instanceof HttpError) || cause.status >= 500;
      setUncertain(ambiguous);
      setError(
        cause instanceof HttpError && cause.detail.code === 'VOD_VERSION_CONFLICT'
          ? 'La grabación cambió. Consulta de nuevo su versión antes de confirmar.'
          : readableAdminError(cause),
      );
      if (!ambiguous && cause instanceof HttpError && cause.status === 409) {
        focusLookup.current = true;
        setVod(null);
      }
    } finally {
      setBusy(false);
    }
  }

  async function checkStatus() {
    if (!accepted || busy) return;
    setBusy(true);
    setError('');
    try {
      const value = await request<P2OperationStatus>(
        `/api/vods/operations/${encodeURIComponent(accepted.operationId)}`,
      );
      setStatus(value);
    } catch (cause) {
      setError(readableAdminError(cause));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className={`${styles.panel} ${styles.vodPanel}`} aria-labelledby="admin-vod-heading">
      <div className={styles.sectionHeader}>
        <span className={styles.sectionIcon}>
          <Icon name="play" size={20} />
        </span>
        <div className={styles.sectionCopy}>
          <p className={styles.eyebrow}>Moderación de contenido</p>
          <h2 id="admin-vod-heading" ref={heading} tabIndex={-1}>
            Retirar una grabación
          </h2>
          <p className={styles.vodDescription}>
            Busca una grabación publicada, revisa su título y confirma la retirada. Las grabaciones
            privadas no aparecen aquí.
          </p>
        </div>
      </div>
      <form
        onSubmit={(event) => {
          void lookup(event);
        }}
        className={styles.lookupForm}
      >
        <div className={styles.lookupField}>
          <FormField
            id="admin-vod-id"
            label="Identificador del VOD"
            hint="Copia el identificador de la dirección de la grabación, sin incluir la URL."
          >
            <Input
              id="admin-vod-id"
              ref={idField}
              value={id}
              onChange={(event) => setId(event.target.value)}
              disabled={busy || uncertain}
              maxLength={128}
              aria-describedby="admin-vod-id-hint"
            />
          </FormField>
        </div>
        <Button className={styles.lookupAction} type="submit" disabled={busy || uncertain}>
          <Icon name="search" size={17} />
          Consultar grabación
        </Button>
      </form>
      {vod ? (
        <form
          className={styles.removalForm}
          onSubmit={(event) => {
            void remove(event);
          }}
        >
          <h3>{vod.title}</h3>
          <p>
            La retirada bloquea nuevas entregas. La limpieza de archivos se procesa después; no
            elimina copias ya descargadas.
          </p>
          <FormField
            id="admin-vod-reason"
            label="Motivo de la retirada"
            hint="Obligatorio. No incluyas credenciales ni información sensible."
            error={fieldError || undefined}
          >
            <Textarea
              id="admin-vod-reason"
              ref={reasonField}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              maxLength={500}
              disabled={busy || uncertain}
              aria-invalid={!!fieldError}
              aria-describedby={`admin-vod-reason-hint${fieldError ? ' admin-vod-reason-error' : ''}`}
            />
          </FormField>
          <label className={styles.confirmRemoval}>
            <Input
              type="checkbox"
              checked={confirmed}
              onChange={(event) => setConfirmed(event.target.checked)}
              disabled={busy || uncertain}
            />{' '}
            Confirmo la retirada de esta grabación.
          </label>
          {uncertain ? (
            <Notice tone="warning">
              No se pudo confirmar el resultado. Reintenta la misma operación para consultar su
              resultado sin duplicarla.
            </Notice>
          ) : null}
          <div className={styles.actions}>
            <Button type="submit" disabled={!confirmed || busy}>
              {uncertain ? 'Reintentar retirada' : 'Confirmar retirada'}
            </Button>
            {!uncertain ? (
              <Button
                disabled={busy}
                onClick={() => {
                  setVod(null);
                  heading.current?.focus();
                }}
              >
                Cancelar retirada
              </Button>
            ) : null}
          </div>
        </form>
      ) : null}
      {accepted ? (
        <div
          ref={result}
          tabIndex={-1}
          className={styles.resultPanel}
          aria-label="Resultado de la retirada"
        >
          <Notice>
            Retirada aceptada. El acceso está restringido; la limpieza todavía debe comprobarse.
          </Notice>
          <Button
            onClick={() => {
              void checkStatus();
            }}
            disabled={busy}
          >
            Consultar estado de la retirada
          </Button>
          {status ? (
            <Notice tone={status.status === 'FAILED' ? 'warning' : 'info'}>
              {
                {
                  PENDING: 'Limpieza pendiente.',
                  RUNNING: 'Limpieza en curso.',
                  FAILED: 'La limpieza requiere atención; el contenido continúa retirado.',
                  COMPLETED: 'Limpieza de VOD completada.',
                }[status.status]
              }{' '}
              El replay se gestiona en Chat.
            </Notice>
          ) : null}
        </div>
      ) : null}
      {busy ? (
        <p className={styles.loading} role="status">
          Procesando solicitud…
        </p>
      ) : null}
      {error ? <Notice tone="error">{error}</Notice> : null}
    </section>
  );
}
