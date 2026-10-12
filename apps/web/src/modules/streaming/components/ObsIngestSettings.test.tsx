import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ObsIngestSettings } from './ObsIngestSettings';

const rtmpUrl = 'rtmps://localhost:11936/live';

describe('configuración de emisión', () => {
  it('copia un servidor limpio y únicamente la clave opaca de emisión', async () => {
    const user = userEvent.setup();
    const streamKey = `sk_${'a'.repeat(64)}`;
    render(<ObsIngestSettings rtmpUrl={rtmpUrl} streamKey={streamKey} onDiscard={vi.fn()} />);
    const server = screen.getByLabelText('Servidor');
    const key = screen.getByLabelText('Clave de retransmisión (solo se muestra ahora)');
    expect(server).toHaveValue('rtmps://localhost:11936/live');
    expect(key).toHaveAttribute('type', 'password');
    expect(key).toHaveValue(streamKey);
    expect(server).toHaveAccessibleDescription(/Desactiva la autenticación/);
    expect(key).toHaveAccessibleDescription(/La clave es privada/);
    const publish = new URL(`rtmps://localhost:11936/live/${streamKey}`);
    expect(publish.pathname).toBe(`/live/${streamKey}`);
    expect(publish.search).toBe('');
    expect(
      screen.getByText(/Desactiva la autenticación por usuario y contraseña/),
    ).toBeInTheDocument();

    const clipboard = vi.spyOn(navigator.clipboard, 'writeText');
    await user.click(screen.getByRole('button', { name: 'Copiar servidor' }));
    expect(clipboard).toHaveBeenLastCalledWith('rtmps://localhost:11936/live');
    await user.click(screen.getByRole('button', { name: 'Copiar clave' }));
    expect(clipboard).toHaveBeenLastCalledWith(streamKey);
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
    expect(screen.getByLabelText(/Clave de retransmisión/)).toHaveAttribute('type', 'text');
    await user.click(screen.getByRole('button', { name: 'Ocultar clave' }));
    expect(screen.getByLabelText(/Clave de retransmisión/)).toHaveAttribute('type', 'password');
    await user.click(screen.getByRole('button', { name: 'Descartar clave de esta vista' }));
    expect(onDiscard).toHaveBeenCalledOnce();
    rerender(<ObsIngestSettings rtmpUrl={rtmpUrl} streamKey="" onDiscard={onDiscard} />);
    expect(screen.queryByLabelText(/Clave de retransmisión/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Servidor')).toHaveFocus();
    expect(screen.getByLabelText('Servidor')).toHaveValue('rtmps://localhost:11936/live');
    expect(screen.getByText(/La clave anterior no se puede consultar/)).toBeInTheDocument();
  });

  it('ofrece copia manual si el portapapeles falla', async () => {
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('Clipboard denied'));
    render(<ObsIngestSettings rtmpUrl={rtmpUrl} streamKey="fixture-only" onDiscard={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'Copiar clave' }));
    expect(screen.getByRole('status')).toHaveTextContent('Selecciona el campo y copia');
  });
});
