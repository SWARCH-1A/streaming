import { MemoryRouter } from 'react-router';
import type { Taxonomy } from '@contracts/p1';
import type { P2ExploreScreen, P2Vod } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type * as Http from '@/src/shared/api/http';
import { HttpError, request } from '@/src/shared/api/http';

import { VodSection } from './VodSection';

vi.mock('@/src/shared/api/http', async (importOriginal) => ({
  ...(await importOriginal<typeof Http>()),
  request: vi.fn(),
}));

const vod = (schema.$defs.P2Vod.examples[0] ?? {}) as P2Vod;
const taxonomy = {
  categories: [{ id: 'cat_fixture', name: 'Educación' }],
  tags: [{ id: 'tag_a', name: 'Arquitectura' }],
} as unknown as Taxonomy;
const meta = { partial: false, requestId: 'req_fixture' };
const live = { status: 'EMPTY' } as const;

function screenWith(vods: P2ExploreScreen['vods'], partial = false): P2ExploreScreen {
  return { vods, discovery: live, meta: { ...meta, partial } };
}
function page(items: P2Vod[], nextCursor: string | null = null): P2ExploreScreen {
  return screenWith({ status: items.length ? 'AVAILABLE' : 'EMPTY', data: { items, nextCursor } });
}
function mount(props: Partial<Parameters<typeof VodSection>[0]> = {}) {
  return render(
    <MemoryRouter>
      <VodSection q="" categoryId="" tagId="" taxonomy={taxonomy} {...props} />
    </MemoryRouter>,
  );
}
afterEach(() => vi.resetAllMocks());

describe('búsqueda de grabaciones', () => {
  it('pide el agregado de exploración con los mismos IDs exactos de los filtros en vivo', async () => {
    vi.mocked(request).mockResolvedValue(page([{ ...vod, tagIds: ['tag_a'] }]));
    mount({ q: 'clase', categoryId: 'cat_fixture', tagId: 'tag_a' });
    expect(screen.getByRole('status')).toHaveTextContent('Cargando grabaciones');
    expect(await screen.findByRole('link', { name: vod.title })).toHaveAttribute(
      'href',
      `/vod/${vod.vodId}`,
    );
    const url = new URL(String(vi.mocked(request).mock.calls[0]?.[0]), 'https://x.example');
    expect(url.pathname).toBe('/api/screens/explore');
    expect(Object.fromEntries(url.searchParams)).toEqual({
      limit: '12',
      title: 'clase',
      categoryId: 'cat_fixture',
      tagId: 'tag_a',
    });
    expect(screen.getByText('Educación')).toBeInTheDocument();
    expect(screen.getByText(/Arquitectura/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('1 grabación');
  });

  it('sin resultados muestra un estado vacío', async () => {
    vi.mocked(request).mockResolvedValue(page([]));
    mount();
    expect(
      await screen.findByRole('heading', { name: 'No encontramos grabaciones' }),
    ).toBeInTheDocument();
  });

  it('si la sección VOD no está disponible solo ella lo señala y permite reintentar', async () => {
    vi.mocked(request)
      .mockResolvedValueOnce(
        screenWith(
          {
            status: 'UNAVAILABLE',
            error: { code: 'VOD_UNAVAILABLE', retryable: true, requestId: 'req_9' },
          },
          true,
        ),
      )
      .mockResolvedValueOnce(page([vod]));
    mount();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Las grabaciones no están disponibles ahora');
    expect(alert).toHaveTextContent('las transmisiones en vivo siguen funcionando');
    expect(alert).toHaveTextContent('VOD_UNAVAILABLE · req_9');
    await userEvent.click(within(alert).getByRole('button', { name: 'Reintentar grabaciones' }));
    expect(await screen.findByRole('link', { name: vod.title })).toBeInTheDocument();
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('acceso denegado y fallo de red del propio agregado también quedan acotados a la sección', async () => {
    vi.mocked(request).mockResolvedValueOnce(
      screenWith({
        status: 'DENIED',
        error: { code: 'VOD_DENIED', retryable: false, requestId: 'req_8' },
      }),
    );
    const first = mount();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'No tienes acceso a las grabaciones',
    );
    first.unmount();
    vi.mocked(request).mockRejectedValueOnce(
      new HttpError(503, {
        code: 'UPSTREAM_UNAVAILABLE',
        message: 'Servicio no disponible.',
        requestId: 'proxy',
      }),
    );
    mount();
    expect(await screen.findByRole('alert')).toHaveTextContent('UPSTREAM_UNAVAILABLE');
  });

  it('carga más por el cursor propio del VOD y conserva lo ya mostrado si esa página falla', async () => {
    const second = { ...vod, vodId: 'vod_segunda', title: 'Segunda clase' };
    vi.mocked(request)
      .mockResolvedValueOnce(page([vod], 'cursor_vod'))
      .mockRejectedValueOnce(
        new HttpError(503, { code: 'UPSTREAM_UNAVAILABLE', message: 'x', requestId: 'req_7' }),
      )
      .mockResolvedValueOnce(page([second]));
    mount();
    await screen.findByRole('link', { name: vod.title });
    const more = screen.getByRole('button', { name: 'Cargar más grabaciones' });
    await userEvent.click(more);
    expect(await screen.findByRole('alert')).toHaveTextContent('UPSTREAM_UNAVAILABLE');
    expect(screen.getByRole('link', { name: vod.title })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cargar más grabaciones' }));
    await screen.findByRole('link', { name: 'Segunda clase' });
    expect(
      new URL(String(vi.mocked(request).mock.calls[2]?.[0]), 'https://x.example').searchParams.get(
        'cursor',
      ),
    ).toBe('cursor_vod');
    expect(
      screen.queryByRole('button', { name: 'Cargar más grabaciones' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: vod.title })).toBeInTheDocument();
  });

  it('una búsqueda de más de 140 caracteres no consulta VOD y lo explica', async () => {
    mount({ q: 'x'.repeat(141) });
    expect(screen.getByRole('status')).toHaveTextContent('más de 140 caracteres');
    await waitFor(() => expect(request).not.toHaveBeenCalled());
  });
});
