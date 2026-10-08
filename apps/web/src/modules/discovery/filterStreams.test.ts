import { describe, expect, it } from 'vitest';

import type { StreamCardData } from '@/src/components/organisms/StreamCard.types';
import { demoChannels, demoStreams } from '@/src/modules/discovery/mock/streams';

import { filterChannels, filterStreams } from './filterStreams';

const empty = { query: '', categoryId: '', tagId: '' };
const base = demoStreams[0];
if (!base) throw new Error('Fixture requerido');
const sample: StreamCardData = base;

describe('descubrimiento', () => {
  it('ordena por espectadores y excluye señales no reproducibles', () => {
    const results = filterStreams(
      [
        ...demoStreams,
        { ...sample, id: 'offline', viewers: 999999, availability: 'OFFLINE' },
        { ...sample, id: 'grace', viewers: 999999, availability: 'RECONNECTING' },
      ],
      empty,
    );
    expect(results[0]?.id).toBe('cyber-city');
    expect(results).toHaveLength(demoStreams.length);
  });
  it('desempata por inicio reciente y luego ID', () => {
    const a = { ...sample, id: 'a', viewers: 10 };
    const z = { ...a, id: 'z' };
    const recent = { ...a, id: 'recent', startedAt: '2026-10-02T23:00:00Z' };
    expect(filterStreams([z, a, recent], empty).map((item) => item.id)).toEqual([
      'recent',
      'a',
      'z',
    ]);
  });
  it('combina categoría y una etiqueta por ID exacto usando AND', () => {
    const results = filterStreams(demoStreams, {
      ...empty,
      categoryId: 'science',
      tagId: 'programming',
    });
    expect(results.map((item) => item.id)).toEqual(['rust-engine']);
    expect(filterStreams(demoStreams, { ...empty, tagId: 'program' })).toEqual([]);
  });
  it('normaliza NFKC y mayúsculas sin borrar acentos', () => {
    expect(
      filterStreams(demoStreams, { ...empty, query: '  ＰＲＯＧＲＡＭＡＣＩÓＮ  ' }),
    ).toHaveLength(1);
    expect(filterStreams(demoStreams, { ...empty, query: 'programacion' })).toHaveLength(0);
  });
  it('no muta la colección original', () => {
    const ids = demoStreams.map((item) => item.id);
    filterStreams(demoStreams, empty);
    expect(demoStreams.map((item) => item.id)).toEqual(ids);
  });
  it('incluye canales offline y busca handle/nombre', () => {
    expect(filterChannels(demoChannels, 'ELENA')[0]).toMatchObject({
      handle: 'elena_algoritmos',
      status: 'OFFLINE',
    });
    expect(filterChannels(demoChannels, 'valeria_tv')[0]?.name).toBe('Valeria Silva');
  });
});
