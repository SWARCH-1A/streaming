import { demoCatalog } from '@/src/modules/taxonomy/entry';
import { codePointLength } from '@/src/shared/format';

import type { StreamMetadata } from './streaming.types';

export function validateMetadata(values: StreamMetadata) {
  const errors: { title?: string; categoryId?: string; tagIds?: string } = {};
  if (!values.title.trim() || codePointLength(values.title) > 100)
    errors.title = 'El título debe tener de 1 a 100 caracteres.';
  if (!demoCatalog.categories.some((category) => category.id === values.categoryId))
    errors.categoryId = 'Selecciona una categoría activa.';
  if (
    values.tagIds.length > 5 ||
    new Set(values.tagIds).size !== values.tagIds.length ||
    values.tagIds.some((id) => !demoCatalog.tags.some((tag) => tag.id === id))
  )
    errors.tagIds = 'Selecciona hasta cinco etiquetas diferentes del catálogo.';
  return errors;
}
