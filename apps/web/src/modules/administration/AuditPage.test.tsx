import { MemoryRouter } from 'react-router';
import type { P2AuditPage } from '@contracts/p2/index';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';

import type * as Http from '@/src/shared/api/http';
import { HttpError, request } from '@/src/shared/api/http';

import { AuditPage } from './AuditPage';

vi.mock('@/src/shared/api/http', async (original) => ({
  ...(await original<typeof Http>()),
  request: vi.fn(),
}));
function entry(position = '1'): P2AuditPage['items'][number] {
  return {
    position,
    receivedAtUtc: '2026-11-22T12:00:01Z',
    event: {
      eventId: `00000000-0000-4000-8000-${position.padStart(12, '0')}`,
      eventType: 'security.audit.vod.v1',
      schemaVersion: 1,
      aggregateId: 'audit:fixture',
      sequence: 1,
      occurredAtUtc: '2026-11-22T12:00:00Z',
      producer: 'vod',
      correlationId: `req_${position}`,
      payload: {
        actorId: 'usr_fixture',
        action: 'VOD_PATCH',
        targetType: 'VOD',
        targetId: `vod_${position}`,
        outcome: 'DENIED',
        reasonCode: 'NOT_OWNER',
      },
    },
  };
}
function mount() {
  return render(<AuditPage />, { wrapper: MemoryRouter });
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(request).mockResolvedValue({ items: [entry()], nextCursor: null });
});

it('loads one bounded page and sends draft filters only when submitted', async () => {
  const user = userEvent.setup();
  mount();
  await screen.findByText('vod_1');
  expect(request).toHaveBeenCalledTimes(1);
  const initial = new URL(vi.mocked(request).mock.calls[0]![0], 'https://fixture.test');
  expect(initial.searchParams.get('limit')).toBe('50');
  expect(
    new Date(initial.searchParams.get('to')!).getTime() -
      new Date(initial.searchParams.get('from')!).getTime(),
  ).toBe(86400000);
  await user.selectOptions(screen.getByLabelText('Servicio'), 'vod');
  await user.selectOptions(screen.getByLabelText('Resultado'), 'DENIED');
  await user.click(screen.getByText('Buscar por usuario, recurso o solicitud'));
  await user.type(screen.getByLabelText('ID de solicitud'), 'req_1');
  expect(request).toHaveBeenCalledTimes(1);
  await user.click(screen.getByRole('button', { name: 'Buscar eventos' }));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  const query = new URL(vi.mocked(request).mock.calls[1]![0], 'https://fixture.test').searchParams;
  expect(query.get('producer')).toBe('vod');
  expect(query.get('outcome')).toBe('DENIED');
  expect(query.get('requestId')).toBe('req_1');
});

it('paginates with cursors, preserves date bounds and resets paging and detail when filters change', async () => {
  vi.mocked(request).mockImplementation((path) =>
    Promise.resolve(
      path.includes('cursor=1')
        ? { items: [entry('2')], nextCursor: null }
        : { items: [entry()], nextCursor: '1' },
    ),
  );
  const user = userEvent.setup();
  mount();
  await screen.findByText('vod_1');
  await user.click(screen.getByRole('button', { name: 'Siguiente' }));
  await screen.findByText('vod_2');
  expect(screen.queryByText('vod_1')).not.toBeInTheDocument();
  expect(screen.getByText('Página 2 · Fin de los resultados')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled();
  const queries = vi
    .mocked(request)
    .mock.calls.map(([path]) => new URL(path, 'https://fixture.test').searchParams);
  expect(queries[1]!.get('cursor')).toBe('1');
  expect(queries[1]!.get('from')).toBe(queries[0]!.get('from'));
  expect(queries[1]!.get('to')).toBe(queries[0]!.get('to'));
  await user.click(screen.getByRole('button', { name: 'Anterior' }));
  await screen.findByText('vod_1');
  await user.click(screen.getByRole('button', { name: 'Ver detalle del evento 1' }));
  expect(screen.getByRole('heading', { name: 'Detalle del evento' })).toHaveFocus();
  expect(
    within(screen.getByRole('region', { name: 'Detalle del evento' })).getByText('NOT_OWNER'),
  ).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Buscar eventos' }));
  await waitFor(() =>
    expect(screen.queryByRole('heading', { name: 'Detalle del evento' })).not.toBeInTheDocument(),
  );
  expect(
    new URL(vi.mocked(request).mock.calls.at(-1)![0], 'https://fixture.test').searchParams.has(
      'cursor',
    ),
  ).toBe(false);
});

it('filters WAF blocks and names the attack without a verified identity', async () => {
  const blocked: P2AuditPage['items'][number] = {
    position: '9',
    receivedAtUtc: '2026-11-22T12:00:01Z',
    event: {
      eventId: '00000000-0000-5000-8000-000000000009',
      eventType: 'security.audit.waf.v1',
      schemaVersion: 1,
      aggregateId: 'audit:00000000-0000-5000-8000-000000000009',
      sequence: 1,
      occurredAtUtc: '2026-11-22T12:00:00Z',
      producer: 'waf',
      correlationId: '2ef5522f57c4fe9648f2d11c094825dc',
      payload: {
        actorId: null,
        action: 'WAF_BLOCK',
        targetType: 'ROUTE',
        targetId: 'api_taxonomy',
        outcome: 'DENIED',
        reasonCode: 'SQL_INJECTION',
      },
    },
  };
  vi.mocked(request).mockResolvedValue({ items: [blocked], nextCursor: null });
  const user = userEvent.setup();
  mount();
  const row = (await screen.findByText('api_taxonomy')).closest('tr')!;
  expect(within(row).getByText('WAF')).toBeInTheDocument();
  expect(within(row).getByText('Bloqueo del WAF')).toBeInTheDocument();
  expect(within(row).getByText('Inyección SQL')).toBeInTheDocument();
  expect(within(row).getByText('Identidad no verificada')).toBeInTheDocument();
  expect(within(row).getByText('Denegado')).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Servicio'), 'waf');
  await user.click(screen.getByRole('button', { name: 'Buscar eventos' }));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  const query = new URL(vi.mocked(request).mock.calls[1]![0], 'https://fixture.test').searchParams;
  expect(query.get('producer')).toBe('waf');
  await user.click(await screen.findByRole('button', { name: 'Ver detalle del evento 9' }));
  const detail = screen.getByRole('region', { name: /detalle/i });
  expect(within(detail).getByText('2ef5522f57c4fe9648f2d11c094825dc')).toBeInTheDocument();
  expect(within(detail).getByText('Tipo de ataque')).toBeInTheDocument();
});

it('returns focus to the selected event when closing its details', async () => {
  const user = userEvent.setup();
  mount();
  const button = await screen.findByRole('button', { name: 'Ver detalle del evento 1' });
  await user.click(button);
  await user.click(screen.getByRole('button', { name: 'Cerrar detalle' }));
  expect(button).toHaveFocus();
});

it('rejects an invalid date range without sending another query or discarding the current result', async () => {
  const user = userEvent.setup();
  mount();
  await screen.findByText('vod_1');
  await user.selectOptions(screen.getByLabelText('Período'), 'custom');
  fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '2026-11-22T12:00' } });
  fireEvent.change(screen.getByLabelText('Hasta'), { target: { value: '2026-11-21T12:00' } });
  await user.click(screen.getByRole('button', { name: 'Buscar eventos' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('anterior');
  expect(request).toHaveBeenCalledTimes(1);
  expect(screen.getByText('vod_1')).toBeInTheDocument();
});

it('shows denied access and never renders event details for a non-admin', async () => {
  vi.mocked(request).mockRejectedValue(
    new HttpError(403, {
      code: 'ADMIN_REQUIRED',
      message: 'Sin permisos',
      requestId: 'req_denied',
    }),
  );
  mount();
  expect(
    await screen.findByText('Tu cuenta no tiene permisos administrativos.'),
  ).toBeInTheDocument();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Buscar eventos' })).not.toBeInTheDocument();
});

it('distinguishes service failure from an empty result and allows an explicit retry', async () => {
  vi.mocked(request).mockRejectedValueOnce(
    new Error('Auditoría no disponible (AUDIT_UNAVAILABLE)'),
  );
  const user = userEvent.setup();
  mount();
  expect(await screen.findByRole('alert')).toHaveTextContent('AUDIT_UNAVAILABLE');
  expect(screen.queryByText('No hay eventos con estos filtros')).not.toBeInTheDocument();
  vi.mocked(request).mockResolvedValue({ items: [], nextCursor: null });
  await user.click(screen.getByRole('button', { name: 'Reintentar consulta' }));
  expect(await screen.findByText('No hay eventos con estos filtros')).toBeInTheDocument();
});

it('aborts the previous query and ignores a late response after changing filters', async () => {
  let finish: (value: P2AuditPage) => void = () => undefined;
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise<P2AuditPage>((resolve) => {
        finish = resolve;
      }),
  );
  const user = userEvent.setup();
  mount();
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
  const oldSignal = vi.mocked(request).mock.calls[0]![1]!.signal!;
  await user.click(screen.getByRole('button', { name: 'Restablecer filtros' }));
  await screen.findByText('vod_1');
  expect(oldSignal.aborted).toBe(true);
  await act(() => {
    finish({ items: [entry('99')], nextCursor: null });
    return Promise.resolve();
  });
  expect(screen.queryByText('vod_99')).not.toBeInTheDocument();
  expect(screen.getByText('vod_1')).toBeInTheDocument();
});

it('clears visible evidence when a later query finds the admin role revoked', async () => {
  const user = userEvent.setup();
  mount();
  await screen.findByText('vod_1');
  vi.mocked(request).mockRejectedValue(
    new HttpError(403, {
      code: 'ADMIN_REQUIRED',
      message: 'Sin permisos',
      requestId: 'req_revoked',
    }),
  );
  await user.click(screen.getByRole('button', { name: 'Buscar eventos' }));
  await screen.findByText('Tu cuenta no tiene permisos administrativos.');
  expect(screen.queryByText('vod_1')).not.toBeInTheDocument();
});
