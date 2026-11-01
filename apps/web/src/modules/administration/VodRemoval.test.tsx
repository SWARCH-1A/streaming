import type { P2Vod } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { HttpError, request } from '@/src/shared/api/http';

import { VodRemoval } from './VodRemoval';

vi.mock('@/src/shared/api/http', async (original) => ({
  ...(await original<{ HttpError: typeof HttpError; errorMessage: (error: unknown) => string }>()),
  request: vi.fn(),
}));
const vod = { ...(schema.$defs.P2Vod.examples[0] as P2Vod), version: 7 };
const accepted = {
  operationId: 'op_fixture',
  status: 'PENDING',
  statusUri: '/api/vods/operations/op_fixture',
};
beforeEach(() => {
  vi.mocked(request).mockReset();
});
afterEach(cleanup);
async function lookup() {
  fireEvent.change(screen.getByLabelText('Identificador del VOD'), {
    target: { value: vod.vodId },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Consultar grabación' }));
  await screen.findByRole('heading', { name: vod.title });
  await waitFor(() => expect(screen.getByLabelText('Motivo de la retirada')).toHaveFocus());
}
function confirm() {
  fireEvent.change(screen.getByLabelText('Motivo de la retirada'), {
    target: { value: 'Retirada de prueba' },
  });
  fireEvent.click(screen.getByRole('checkbox'));
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar retirada' }));
}
it('usa la versión consultada, exige motivo y distingue aceptación de limpieza', async () => {
  vi.mocked(request)
    .mockResolvedValueOnce(vod)
    .mockResolvedValueOnce(accepted)
    .mockResolvedValueOnce({
      operationId: accepted.operationId,
      status: 'COMPLETED',
      errorCode: '',
      replayCleanup: 'DELEGATED_TO_CHAT',
    });
  render(<VodRemoval />);
  await lookup();
  fireEvent.click(screen.getByRole('checkbox'));
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar retirada' }));
  expect(screen.getByLabelText('Motivo de la retirada')).toHaveAccessibleDescription(
    /Indica el motivo/,
  );
  expect(screen.getByLabelText('Motivo de la retirada')).toHaveFocus();
  fireEvent.change(screen.getByLabelText('Motivo de la retirada'), {
    target: { value: 'Retirada de prueba' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar retirada' }));
  await screen.findByText(/Retirada aceptada/);
  expect(screen.queryByText(/Limpieza de VOD completada/)).not.toBeInTheDocument();
  expect(request).toHaveBeenCalledWith(
    '/api/admin/content-removals',
    expect.objectContaining({
      method: 'POST',
      coreMutation: true,
      body: {
        resourceType: 'VOD',
        resourceId: vod.vodId,
        expectedVersion: 7,
        reason: 'Retirada de prueba',
      },
    }),
  );
  await waitFor(() => expect(screen.getByLabelText('Resultado de la retirada')).toHaveFocus());
  fireEvent.click(screen.getByRole('button', { name: 'Consultar estado de la retirada' }));
  await screen.findByText(/Limpieza de VOD completada/);
});
it('reintenta el mismo comando tras una respuesta incierta y bloquea su edición', async () => {
  vi.mocked(request)
    .mockResolvedValueOnce(vod)
    .mockRejectedValueOnce(new Error('Timeout'))
    .mockResolvedValueOnce(accepted);
  render(<VodRemoval />);
  await lookup();
  confirm();
  await screen.findByText('Timeout');
  expect(screen.getByLabelText('Motivo de la retirada')).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Consultar grabación' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Reintentar retirada' }));
  await screen.findByText(/Retirada aceptada/);
  const writes = vi.mocked(request).mock.calls.filter(([, options]) => options?.method === 'POST');
  expect(writes).toHaveLength(2);
  expect(writes[0]![1]).toEqual(writes[1]![1]);
});
it('devuelve el foco a la consulta y usa la nueva versión tras un conflicto', async () => {
  vi.mocked(request)
    .mockResolvedValueOnce(vod)
    .mockRejectedValueOnce(
      new HttpError(409, {
        code: 'VOD_VERSION_CONFLICT',
        message: 'Conflicto',
        requestId: 'fixture',
      }),
    )
    .mockResolvedValueOnce({ ...vod, version: 8 })
    .mockResolvedValueOnce(accepted);
  render(<VodRemoval />);
  await lookup();
  screen.getByRole('button', { name: 'Confirmar retirada' }).focus();
  confirm();
  await screen.findByText(/Consulta de nuevo su versión/);
  expect(screen.queryByRole('button', { name: 'Confirmar retirada' })).not.toBeInTheDocument();
  await waitFor(() => expect(screen.getByLabelText('Identificador del VOD')).toHaveFocus());
  await lookup();
  expect(screen.getByLabelText('Motivo de la retirada')).toHaveValue('');
  confirm();
  await screen.findByText(/Retirada aceptada/);
  const writes = vi.mocked(request).mock.calls.filter(([, options]) => options?.method === 'POST');
  expect(writes[1]![1]?.body).toEqual({
    resourceType: 'VOD',
    resourceId: vod.vodId,
    expectedVersion: 8,
    reason: 'Retirada de prueba',
  });
});
it.each([401, 403, 503])('muestra el fallo %s de consulta sin ofrecer retirada', async (status) => {
  vi.mocked(request).mockRejectedValue(
    new HttpError(status, { code: 'UNAVAILABLE', message: 'No disponible', requestId: 'fixture' }),
  );
  render(<VodRemoval />);
  fireEvent.change(screen.getByLabelText('Identificador del VOD'), {
    target: { value: vod.vodId },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Consultar grabación' }));
  await screen.findByRole('alert');
  expect(screen.queryByRole('button', { name: 'Confirmar retirada' })).not.toBeInTheDocument();
});
it('rechaza una URL y un borrador sin mutaciones', async () => {
  render(<VodRemoval />);
  fireEvent.change(screen.getByLabelText('Identificador del VOD'), {
    target: { value: 'https://example.test/vod/id' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Consultar grabación' }));
  expect(request).not.toHaveBeenCalled();
  vi.mocked(request).mockResolvedValue({ ...vod, publicationState: 'DRAFT' });
  fireEvent.change(screen.getByLabelText('Identificador del VOD'), {
    target: { value: vod.vodId },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Consultar grabación' }));
  await screen.findByText(/solo admite grabaciones publicadas/);
  expect(screen.queryByRole('button', { name: 'Confirmar retirada' })).not.toBeInTheDocument();
});
