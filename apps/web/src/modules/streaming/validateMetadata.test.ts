import { describe, expect, it } from 'vitest';

import { demoCatalog } from '@/src/modules/taxonomy/entry';

import { validateMetadata } from './validateMetadata';

const valid = { title: 'Directo', categoryId: 'science', tagIds: [] };
describe('metadatos', () => {
  it('acepta categoría y cero o cinco etiquetas', () => {
    expect(validateMetadata(valid)).toEqual({});
    expect(
      validateMetadata({
        ...valid,
        title: '🎮'.repeat(100),
        tagIds: demoCatalog.tags.slice(0, 5).map((tag) => tag.id),
      }),
    ).toEqual({});
  });
  it('rechaza títulos fuera del contrato de 1 a 100 caracteres', () => {
    expect(validateMetadata({ ...valid, title: ' ' }).title).toBeTruthy();
    expect(validateMetadata({ ...valid, title: '🎮'.repeat(101) }).title).toBeTruthy();
  });
  it.each([['unknown'], ['spanish', 'spanish'], demoCatalog.tags.slice(0, 6).map((tag) => tag.id)])(
    'rechaza etiquetas inválidas, duplicadas o más de cinco: %o',
    (...tagIds) => {
      expect(validateMetadata({ ...valid, tagIds }).tagIds).toBeTruthy();
    },
  );
  it('rechaza categoría fuera del catálogo', () => {
    expect(validateMetadata({ ...valid, categoryId: 'unknown' }).categoryId).toBeTruthy();
  });
});
