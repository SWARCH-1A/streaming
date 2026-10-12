import type { FormEvent } from 'react';
import { useState } from 'react';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { Select } from '@/src/components/atoms/Select';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import type { StreamMetadata } from '@/src/modules/streaming/streaming.types';
import { validateMetadata } from '@/src/modules/streaming/validateMetadata';
import { demoCatalog } from '@/src/modules/taxonomy/entry';
import { codePointLength } from '@/src/shared/format';

import styles from './MetadataForm.module.css';

interface MetadataFormProps {
  onSave: (values: StreamMetadata) => void;
}
export function MetadataForm({ onSave }: MetadataFormProps) {
  const [values, setValues] = useState<StreamMetadata>({
    title: 'Desarrollando una arquitectura de streaming',
    categoryId: 'science',
    tagIds: ['programming', 'spanish', 'educational'],
  });
  const [errors, setErrors] = useState<ReturnType<typeof validateMetadata>>({});
  const [saved, setSaved] = useState(false);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = validateMetadata(values);
    setErrors(next);
    setSaved(false);
    if (Object.keys(next).length) {
      document.getElementById(next.title ? 'stream-title' : 'stream-category')?.focus();
      return;
    }
    onSave({ ...values, title: values.title.trim() });
    setSaved(true);
  }
  function toggleTag(id: string) {
    setSaved(false);
    setValues((previous) => ({
      ...previous,
      tagIds: previous.tagIds.includes(id)
        ? previous.tagIds.filter((tag) => tag !== id)
        : [...previous.tagIds, id],
    }));
  }
  return (
    <Panel className={styles.panel}>
      <div className="row">
        <Icon name="settings" />
        <h2>Metadatos de la emisión</h2>
      </div>
      <form onSubmit={submit} className="stack" noValidate>
        <FormField
          id="stream-title"
          label="Título de la transmisión"
          hint={`${codePointLength(values.title)} / 100 caracteres`}
          error={errors.title}
        >
          <Input
            id="stream-title"
            value={values.title}
            onChange={(event) => {
              setValues((previous) => ({ ...previous, title: event.target.value }));
              setSaved(false);
            }}
            aria-invalid={!!errors.title}
            aria-describedby={errors.title ? 'stream-title-error' : 'stream-title-hint'}
          />
        </FormField>
        <FormField id="stream-category" label="Categoría obligatoria" error={errors.categoryId}>
          <Select
            id="stream-category"
            value={values.categoryId}
            onChange={(event) => {
              setValues((previous) => ({ ...previous, categoryId: event.target.value }));
              setSaved(false);
            }}
            aria-invalid={!!errors.categoryId}
            aria-describedby={errors.categoryId ? 'stream-category-error' : undefined}
          >
            <option value="">Selecciona una categoría</option>
            {demoCatalog.categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </Select>
        </FormField>
        <fieldset className={styles.tags}>
          <legend>Etiquetas de búsqueda ({values.tagIds.length}/5)</legend>
          <p className="muted">Elige hasta cinco etiquetas del catálogo.</p>
          <div className="row">
            {demoCatalog.tags.map((tag) => {
              const selected = values.tagIds.includes(tag.id);
              return (
                <Button
                  key={tag.id}
                  variant={selected ? 'primary' : 'secondary'}
                  size="sm"
                  aria-pressed={selected}
                  disabled={!selected && values.tagIds.length >= 5}
                  onClick={() => toggleTag(tag.id)}
                >
                  {tag.name}
                  {selected ? <Icon name="check" size={14} /> : null}
                </Button>
              );
            })}
          </div>
          {errors.tagIds ? <Notice tone="error">{errors.tagIds}</Notice> : null}
        </fieldset>
        <Button type="submit" size="lg">
          <Icon name="check" />
          Guardar cambios
        </Button>
        {saved ? <Notice tone="success">Metadatos guardados en esta demostración.</Notice> : null}
      </form>
    </Panel>
  );
}
