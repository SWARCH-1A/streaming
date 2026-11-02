import { MemoryRouter } from 'react-router';
import type { P2ExploreScreen } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import type * as Http from '@/src/shared/api/http';
import { HttpError, request } from '@/src/shared/api/http';

import { DiscoveryPage } from './DiscoveryPage';

vi.mock('@/src/shared/api/http', async (importOriginal) => ({
  ...(await importOriginal<typeof Http>()),
  request: vi.fn(),
}));
vi.mock('@/src/modules/discovery/components/StreamPreview', () => ({
  StreamPreview: () => <span>vista previa</span>,
}));

const vod = schema.$defs.P2Vod.examples[0];
const liveStream = {
  streamId: 'str_fixture',
  sessionId: 'ses_fixture',
  previewUri: '/hls/ses_fixture/preview.jpg',
  channel: {
    channelId: 'chn_fixture',
    handle: 'fixture',
    displayName: 'Canal de prueba',
    avatarUri: null,
  },
  title: 'Directo de prueba',
  category: { id: 'cat_fixture', name: 'Educación' },
  tags: [],
  status: 'LIVE',
  availability: 'PLAYABLE',
  viewerCount: 5,
  viewerCountFresh: true,
  viewerCountObservedAtUtc: '2026-10-30T18:00:00Z',
  startedAtUtc: '2026-10-30T17:00:00Z',
  metadataVersion: 1,
  sessionVersion: 1,
  statusFresh: true,
};
const taxonomy = { categories: [{ id: 'cat_fixture', name: 'Educación' }], tags: [] };
const failure = new HttpError(503, {
  code: 'UPSTREAM_UNAVAILABLE',
  message: 'Servicio no disponible.',
  requestId: 'proxy',
});

function serve(options: { live: 'ok' | 'fail'; vods: P2ExploreScreen['vods'] | 'fail' }) {
  vi.mocked(request).mockImplementation((path: string) => {
    if (path === '/api/taxonomy') return Promise.resolve(taxonomy);
    if (path === '/api/discovery/graphql')
      return options.live === 'fail'
        ? Promise.reject(failure)
        : Promise.resolve({
            data: {
              streams: {
                items: [liveStream],
                nextCursor: null,
                generatedAtUtc: '2026-10-30T18:00:00Z',
                statusFresh: true,
              },
            },
          });
    if (path.startsWith('/api/screens/explore'))
      return options.vods === 'fail'
        ? Promise.reject(failure)
        : Promise.resolve({
            vods: options.vods,
            discovery: { status: 'EMPTY' },
            meta: { partial: false, requestId: 'req_fixture' },
          });
    return Promise.reject(new Error(`ruta inesperada ${path}`));
  });
}
function mount() {
  return render(
    <MemoryRouter initialEntries={['/?q=clase&category=cat_fixture']}>
      <DiscoveryPage />
    </MemoryRouter>,
  );
}
beforeEach(() => vi.useRealTimers());
afterEach(() => vi.resetAllMocks());

it('si Grabaciones falla, las transmisiones en vivo siguen mostrándose y el error queda en su sección', async () => {
  serve({
    live: 'ok',
    vods: {
      status: 'UNAVAILABLE',
      error: { code: 'VOD_UNAVAILABLE', retryable: true, requestId: 'req_1' },
    },
  });
  mount();
  expect(await screen.findByRole('heading', { name: 'Directo de prueba' })).toBeInTheDocument();
  const recordings = (await screen.findByRole('heading', { name: 'Grabaciones' })).closest(
    'section',
  );
  expect(await within(recordings as HTMLElement).findByRole('alert')).toHaveTextContent(
    'VOD_UNAVAILABLE',
  );
  const livePart = screen
    .getByRole('heading', { name: 'Transmisiones en vivo' })
    .closest('section');
  expect(within(livePart as HTMLElement).queryByRole('alert')).not.toBeInTheDocument();
});

it('si falla la consulta en vivo, Grabaciones conserva sus resultados', async () => {
  serve({
    live: 'fail',
    vods: { status: 'AVAILABLE', data: { items: [vod as never], nextCursor: null } },
  });
  mount();
  expect(
    await screen.findByRole('link', { name: (vod as { title: string }).title }),
  ).toBeInTheDocument();
  expect(screen.getByRole('alert')).toHaveTextContent('UPSTREAM_UNAVAILABLE');
  expect(screen.queryByRole('heading', { name: 'Transmisiones en vivo' })).not.toBeInTheDocument();
  const calls = vi.mocked(request).mock.calls.map(([path]) => String(path));
  expect(
    calls.some((path) => path.includes('title=clase') && path.includes('categoryId=cat_fixture')),
  ).toBe(true);
});
