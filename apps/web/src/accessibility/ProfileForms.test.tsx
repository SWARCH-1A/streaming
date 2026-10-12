import { MemoryRouter } from 'react-router';
import schema from '@contracts/p1.schema.json';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ProfilePage } from '@/src/modules/accounts/ProfilePage';
import { ChannelEditorPage } from '@/src/modules/channels/ChannelEditorPage';
import type * as Http from '@/src/shared/api/http';
import { HttpError, request, upload } from '@/src/shared/api/http';

vi.mock('@/src/shell/session/useSession', () => ({
  useSession: () => ({
    user: { ...schema.$defs.Profile.examples[0], handle: 'fixture_01' },
    refresh: vi.fn(),
  }),
}));
vi.mock('@/src/shared/api/http', async (original) => ({
  ...(await original<typeof Http>()),
  request: vi.fn(),
  upload: vi.fn(),
}));

beforeEach(() => vi.clearAllMocks());
describe('perfil y canal', () => {
  it('asocia las restricciones del avatar al selector de archivo', () => {
    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>,
    );
    expect(screen.getByLabelText('Avatar')).toHaveAccessibleDescription(
      'JPEG, PNG o GIF; hasta 10 MB.',
    );
  });
  it('un fallo de subida no invalida la descripción del canal', async () => {
    const user = userEvent.setup();
    vi.mocked(request).mockResolvedValue(schema.$defs.ChannelBootstrap.examples[0]);
    vi.mocked(upload).mockRejectedValue(new Error('No se pudo subir la portada.'));
    render(
      <MemoryRouter>
        <ChannelEditorPage />
      </MemoryRouter>,
    );
    const banner = await screen.findByLabelText('Portada');
    expect(banner).toHaveAccessibleDescription('JPEG, PNG o GIF; hasta 10 MB.');
    await user.upload(banner, new File(['fixture'], 'banner.png', { type: 'image/png' }));
    await user.click(screen.getByRole('button', { name: 'Guardar canal' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo subir la portada.');
    expect(screen.getByLabelText('Descripción del canal')).toHaveAttribute('aria-invalid', 'false');
    expect(banner).toHaveProperty('files.0.name', 'banner.png');
  });
  it('asocia el error remoto del perfil y recupera el foco sin perder el texto', async () => {
    const user = userEvent.setup();
    vi.mocked(request).mockRejectedValue(
      new HttpError(422, {
        code: 'VALIDATION_ERROR',
        message: 'Perfil inválido',
        requestId: 'req_test',
        fieldErrors: { displayName: 'Revisa el nombre visible.' },
      }),
    );
    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>,
    );
    const name = screen.getByLabelText('Nombre visible');
    await user.clear(name);
    await user.type(name, 'Nombre de prueba');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await waitFor(() => expect(name).toHaveFocus());
    expect(name).toHaveAccessibleDescription('Revisa el nombre visible.');
    expect(name).toHaveValue('Nombre de prueba');
    await user.click(screen.getByLabelText('Biografía'));
    await user.type(screen.getByLabelText('Biografía'), 'Texto conservado');
    expect(screen.getByLabelText('Biografía')).toHaveFocus();
  });
});
