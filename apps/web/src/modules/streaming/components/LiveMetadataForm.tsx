import type { FormEvent } from 'react';
import { useRef, useState } from 'react';
import type { BootstrapStream, StreamPatchRequest, Taxonomy } from '@contracts/p1';

import { fieldErrors } from '@/src/accessibility/fieldErrors';
import { useErrorFocus } from '@/src/accessibility/useErrorFocus';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { Select } from '@/src/components/atoms/Select';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import type { StreamMetadata } from '@/src/modules/streaming/streaming.types';
import { errorMessage } from '@/src/shared/api/http';
import { useLifetime } from '@/src/shared/api/useLifetime';
import { codePointLength } from '@/src/shared/format';

interface Props {
  initial: StreamMetadata;
  catalog: Taxonomy;
  labels: BootstrapStream | null;
  creating: boolean;
  save: (
    values: StreamMetadata,
    patch: StreamPatchRequest,
    key: string,
    signal: AbortSignal,
  ) => Promise<void>;
}
export function LiveMetadataForm({ initial, catalog, labels, creating, save }: Props) {
  const [values, setValues] = useState(initial);
  const baseline = useRef(initial),
    intent = useRef(crypto.randomUUID());
  const [pending, setPending] = useState(false),
    [error, setError] = useState(''),
    [saved, setSaved] = useState(false);
  const [fields, setFields] = useState<Partial<Record<keyof StreamMetadata, string>>>({});
  const focusError = useErrorFocus(pending);
  function reportFields(next: typeof fields) {
    setFields(next);
    if (next.title) focusError('stream-title');
    else if (next.categoryId) focusError('stream-category');
    else if (next.tagIds) focusError('stream-tags');
  }
  const lifetime = useLifetime();
  function change(next: StreamMetadata) {
    setValues(next);
    intent.current = crypto.randomUUID();
    setSaved(false);
    setFields({});
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    setSaved(false);
    const normalized = { ...values, title: values.title.trim() };
    const next: typeof fields = {};
    if (!normalized.title || codePointLength(normalized.title) > 100)
      next.title = 'Escribe un título de 1 a 100 caracteres.';
    if (!normalized.categoryId) next.categoryId = 'Selecciona una categoría.';
    if (normalized.tagIds.length > 5) next.tagIds = 'Selecciona hasta cinco etiquetas.';
    reportFields(next);
    if (Object.keys(next).length) return;
    const patch: StreamPatchRequest = {};
    if (normalized.title !== baseline.current.title) patch.title = normalized.title;
    if (normalized.categoryId !== baseline.current.categoryId)
      patch.categoryId = normalized.categoryId;
    if (JSON.stringify(normalized.tagIds) !== JSON.stringify(baseline.current.tagIds))
      patch.tagIds = normalized.tagIds;
    if (!creating && !Object.keys(patch).length) {
      setSaved(true);
      return;
    }
    const signal = lifetime();
    setPending(true);
    try {
      await save(normalized, patch, intent.current, signal);
      signal.throwIfAborted();
      baseline.current = normalized;
      setValues(normalized);
      setSaved(true);
    } catch (error) {
      if (!signal.aborted) {
        setError(errorMessage(error));
        reportFields(fieldErrors(error, ['title', 'categoryId', 'tagIds'] as const));
      }
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }
  return (
    <Panel className="stack">
      <h2 style={{ display: 'flex', gap: '0.65rem', alignItems: 'center' }}>
        <Icon name="settings" />
        Metadatos de la emisión
      </h2>
      <form
        className="stack"
        noValidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <fieldset disabled={pending} className="stack">
          <legend className="sr-only">Metadatos de la emisión</legend>
          {error ? <Notice tone="error">{error}</Notice> : null}
          <FormField
            id="stream-title"
            label="Título de la transmisión"
            hint={`${codePointLength(values.title)} / 100 caracteres`}
            error={fields.title}
          >
            <Input
              id="stream-title"
              value={values.title}
              onChange={(event) => change({ ...values, title: event.target.value })}
              required
              aria-invalid={!!fields.title}
              aria-describedby={
                fields.title ? 'stream-title-error stream-title-hint' : 'stream-title-hint'
              }
            />
          </FormField>
          <FormField id="stream-category" label="Categoría obligatoria" error={fields.categoryId}>
            <Select
              id="stream-category"
              value={values.categoryId}
              onChange={(event) => change({ ...values, categoryId: event.target.value })}
              required
              aria-invalid={!!fields.categoryId}
              aria-describedby={fields.categoryId ? 'stream-category-error' : undefined}
            >
              <option value="">Selecciona una categoría</option>
              {values.categoryId &&
              !catalog.categories.some((item) => item.id === values.categoryId) ? (
                <option value={values.categoryId}>
                  {labels?.category.id === values.categoryId
                    ? labels.category.name
                    : values.categoryId}{' '}
                  (no activa)
                </option>
              ) : null}
              {catalog.categories.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                </option>
              ))}
            </Select>
          </FormField>
          <fieldset
            id="stream-tags"
            tabIndex={-1}
            aria-describedby={fields.tagIds ? 'stream-tags-error' : undefined}
          >
            <legend>Etiquetas de búsqueda ({values.tagIds.length}/5)</legend>
            <div className="row">
              {[
                ...catalog.tags,
                ...values.tagIds
                  .filter((id) => !catalog.tags.some((item) => item.id === id))
                  .map((id) => ({
                    id,
                    name: `${labels?.tags.find((item) => item.id === id)?.name ?? id} (no activa)`,
                    active: false,
                  })),
              ].map((item) => (
                <Button
                  key={item.id}
                  size="sm"
                  variant={values.tagIds.includes(item.id) ? 'primary' : 'secondary'}
                  aria-pressed={values.tagIds.includes(item.id)}
                  disabled={
                    !values.tagIds.includes(item.id) && (values.tagIds.length >= 5 || !item.active)
                  }
                  onClick={() =>
                    change({
                      ...values,
                      tagIds: values.tagIds.includes(item.id)
                        ? values.tagIds.filter((id) => id !== item.id)
                        : [...values.tagIds, item.id],
                    })
                  }
                >
                  {item.name}
                </Button>
              ))}
            </div>
            {fields.tagIds ? (
              <p id="stream-tags-error" role="alert">
                {fields.tagIds}
              </p>
            ) : null}
          </fieldset>
          <Button type="submit">{creating ? 'Configurar emisión' : 'Guardar metadatos'}</Button>
          {saved ? <Notice tone="success">Metadatos guardados.</Notice> : null}
        </fieldset>
      </form>
    </Panel>
  );
}
