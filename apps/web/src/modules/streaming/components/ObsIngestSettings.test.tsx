import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ObsIngestSettings } from './ObsIngestSettings';

const rtmpUrl = 'rtmps://localhost:11936/live/str_fixture';

describe('configuración OBS', () => {
  it('copia un servidor limpio y una clave que conserva path y contraseña al publicarse', async () => {
    const user = userEvent.setup();
    const streamKey = 'fixture+password&with?reserved=/characters';
    render(<ObsIngestSettings rtmpUrl={rtmpUrl} streamKey={streamKey} onDiscard={vi.fn()} />);
    const server = screen.getByLabelText('Servidor OBS');
    const key = screen.getByLabelText('Clave de retransmisión OBS (solo se muestra ahora)');
    expect(server).toHaveValue('rtmps://localhost:11936/live');
    expect(key).toHaveAttribute('type', 'password');
    const expectedKey = `str_fixture?user=publisher&pass=${encodeURIComponent(streamKey)}`;
    expect(key).toHaveValue(expectedKey);
    const publish = new URL(`rtmps://localhost:11936/live/${expectedKey}`);
    expect(publish.pathname).toBe('/live/str_fixture');
    expect(publish.searchParams.get('user')).toBe('publisher');
    expect(publish.searchParams.get('pass')).toBe(streamKey);
    expect(screen.getByText(/desactiva «Usar autenticación»/)).toBeInTheDocument();

    const clipboard = vi.spyOn(navigator.clipboard, 'writeText');
    await user.click(screen.getByRole('button', { name: 'Copiar servidor' }));
    expect(clipboard).toHaveBeenLastCalledWith('rtmps://localhost:11936/live');
    await user.click(screen.getByRole('button', { name: 'Copiar clave para OBS' }));
    expect(clipboard).toHaveBeenLastCalledWith(expectedKey);
    expect(screen.getByRole('status')).toHaveTextContent('Clave copiada.');
    expect(screen.getByRole('status')).not.toHaveTextContent(streamKey);
  });

  it('permite ocultar y descartar el secreto; conserva el servidor sin recuperar la clave', async () => {
    const user = userEvent.setup();
    const onDiscard = vi.fn();
    const { rerender } = render(
      <ObsIngestSettings rtmpUrl={rtmpUrl} streamKey="fixture-only" onDiscard={onDiscard} />,
    );
    await user.click(screen.getByRole('button', { name: 'Mostrar clave' }));
    expect(screen.getByLabelText(/Clave de retransmisión OBS/)).toHaveAttribute('type', 'text');
    await user.click(screen.getByRole('button', { name: 'Ocultar clave' }));
    expect(screen.getByLabelText(/Clave de retransmisión OBS/)).toHaveAttribute('type', 'password');
    await user.click(screen.getByRole('button', { name: 'Descartar clave de esta vista' }));
    expect(onDiscard).toHaveBeenCalledOnce();
    rerender(<ObsIngestSettings rtmpUrl={rtmpUrl} streamKey="" onDiscard={onDiscard} />);
    expect(screen.queryByLabelText(/Clave de retransmisión OBS/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Servidor OBS')).toHaveValue('rtmps://localhost:11936/live');
    expect(screen.getByText(/La clave anterior no se puede consultar/)).toBeInTheDocument();
  });

  it('ofrece copia manual si el portapapeles falla', async () => {
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('Clipboard denied'));
    render(<ObsIngestSettings rtmpUrl={rtmpUrl} streamKey="fixture-only" onDiscard={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'Copiar clave para OBS' }));
    expect(screen.getByRole('status')).toHaveTextContent('Selecciona el campo y copia');
  });
});
