import type { FormEvent } from 'react';
import { useRef, useState } from 'react';
import type { BootstrapStream, StreamPatchRequest, Taxonomy } from '@contracts/p1';

import { Button } from '@/src/components/atoms/Button';
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
  const lifetime = useLifetime();
  function change(next: StreamMetadata) {
    setValues(next);
    intent.current = crypto.randomUUID();
    setSaved(false);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    setSaved(false);
    const normalized = { ...values, title: values.title.trim() };
    if (
      !normalized.title ||
      codePointLength(normalized.title) > 100 ||
      !normalized.categoryId ||
      normalized.tagIds.length > 5
    ) {
      setError('Escribe un título de 1–100 caracteres, categoría y hasta cinco etiquetas.');
      return;
    }
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
      if (!signal.aborted) setError(errorMessage(error));
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }
  return (
    <Panel>
      <form
        className="stack"
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <fieldset disabled={pending} className="stack">
          <legend>Metadatos de la emisión</legend>
          {error ? <Notice tone="error">{error}</Notice> : null}
          <FormField
            id="stream-title"
            label="Título de la transmisión"
            hint={`${codePointLength(values.title)} / 100 caracteres`}
          >
            <Input
              id="stream-title"
              value={values.title}
              onChange={(event) => change({ ...values, title: event.target.value })}
              required
            />
          </FormField>
          <FormField id="stream-category" label="Categoría obligatoria">
            <Select
              id="stream-category"
              value={values.categoryId}
              onChange={(event) => change({ ...values, categoryId: event.target.value })}
              required
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
          <fieldset>
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
          </fieldset>
          <Button type="submit">{creating ? 'Configurar emisión' : 'Guardar metadatos'}</Button>
          {saved ? <Notice tone="success">Metadatos guardados.</Notice> : null}
        </fieldset>
      </form>
    </Panel>
  );
}
