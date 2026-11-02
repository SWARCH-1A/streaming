import type { P2SubtitleTrack, P2Vod } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { request } from '@/src/shared/api/http';

import { SubtitleManager } from './SubtitleManager';

vi.mock('@/src/shared/api/http', () => ({
  request: vi.fn(),
  errorMessage: (e: Error) => e.message,
}));
const vod = schema.$defs.P2Vod.examples[0] as P2Vod;
const track = {
  ...(schema.$defs.P2SubtitleTrack.examples[0] as P2SubtitleTrack),
  label: 'Español',
};
beforeEach(() => {
  vi.mocked(request).mockReset();
  vi.mocked(request).mockResolvedValue({ items: [], nextCursor: null });
});
afterEach(cleanup);

it('asocia ayudas y enfoca el primer campo inválido sin enviar la solicitud', () => {
  render(<SubtitleManager vod={vod} />);
  const file = screen.getByLabelText('Archivo WebVTT');
  expect(file).toHaveAccessibleDescription(/UTF-8/);
  fireEvent.change(screen.getByLabelText('Idioma'), { target: { value: 'es ' } });
  fireEvent.submit(screen.getByRole('button', { name: 'Cargar pista' }).closest('form')!);
  expect(screen.getByLabelText('Idioma')).toHaveFocus();
  expect(screen.getByLabelText('Idioma')).toHaveAttribute('aria-invalid', 'true');
  expect(screen.getByLabelText('Idioma')).toHaveAccessibleDescription(/sin espacios/);
  expect(request).not.toHaveBeenCalled();
});

it('rechaza un archivo vacío y lleva el foco al campo de archivo', () => {
  render(<SubtitleManager vod={vod} />);
  fireEvent.change(screen.getByLabelText('Archivo WebVTT'), {
    target: { files: [new File([], 'empty.vtt')] },
  });
  fireEvent.submit(screen.getByRole('button', { name: 'Cargar pista' }).closest('form')!);
  expect(screen.getByLabelText('Archivo WebVTT')).toHaveFocus();
  expect(screen.getByLabelText('Archivo WebVTT')).toHaveAccessibleDescription(/no esté vacío/);
  expect(request).not.toHaveBeenCalled();
});

it('identifica la pista retirada y restaura el foco después de eliminar su botón', async () => {
  let removed = false;
  vi.mocked(request).mockImplementation((_path, options) => {
    if (options?.method === 'DELETE') {
      removed = true;
      return Promise.resolve(undefined);
    }
    return Promise.resolve({ items: removed ? [] : [track], nextCursor: null });
  });
  const { container } = render(<SubtitleManager vod={vod} />);
  const details = container.querySelector('details')!;
  details.open = true;
  fireEvent.click(await screen.findByRole('button', { name: 'Retirar pista Español (es)' }));
  await screen.findByText('Pista Español retirada.');
  expect(container.querySelector('summary')).toHaveFocus();
  expect(request).toHaveBeenCalledWith(
    expect.stringContaining(`/subtitles/${track.trackId}`),
    expect.objectContaining({
      method: 'DELETE',
      coreMutation: true,
    }),
  );
  const removal = vi.mocked(request).mock.calls.find(([, options]) => options?.method === 'DELETE');
  expect(removal?.[1]?.headers?.['If-Match']).toBe(`"${track.trackVersion}"`);
});

it('descarta los datos y errores al cambiar la versión temporal del VOD', () => {
  const { rerender } = render(<SubtitleManager vod={vod} />);
  fireEvent.change(screen.getByLabelText('Nombre de la pista'), { target: { value: 'Anterior' } });
  fireEvent.submit(screen.getByRole('button', { name: 'Cargar pista' }).closest('form')!);
  rerender(<SubtitleManager vod={{ ...vod, mappingVersion: vod.mappingVersion + 1 }} />);
  expect(screen.getByLabelText('Nombre de la pista')).toHaveValue('Español');
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

it('conserva la clave de retirada cuando la respuesta es incierta', async () => {
  vi.mocked(request).mockImplementation((_path, options) => {
    if (options?.method === 'DELETE') return Promise.reject(new Error('Servicio no disponible'));
    return Promise.resolve({ items: [track], nextCursor: null });
  });
  const { container } = render(<SubtitleManager vod={vod} />);
  container.querySelector('details')!.open = true;
  const remove = await screen.findByRole('button', { name: 'Retirar pista Español (es)' });
  fireEvent.click(remove);
  await screen.findByText('Servicio no disponible');
  await waitFor(() => expect(remove).toBeEnabled());
  fireEvent.click(remove);
  await waitFor(() =>
    expect(vi.mocked(request).mock.calls.filter(([, o]) => o?.method === 'DELETE')).toHaveLength(2),
  );
  const calls = vi.mocked(request).mock.calls.filter(([, o]) => o?.method === 'DELETE');
  expect(calls[0]![1]?.headers?.['Idempotency-Key']).toBe(
    calls[1]![1]?.headers?.['Idempotency-Key'],
  );
});
