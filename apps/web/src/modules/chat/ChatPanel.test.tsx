import { MemoryRouter } from 'react-router';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { SessionContext } from '@/src/shell/session/SessionContext';

import { ChatPanel } from './ChatPanel';

function setup({ signedIn = true, readOnly = false, unavailable = false } = {}) {
  render(
    <SessionContext
      value={{
        user: signedIn ? { handle: 'demo_01', displayName: 'Demo', bio: '' } : null,
        signIn: vi.fn(),
        signOut: vi.fn(),
        updateProfile: vi.fn(),
      }}
    >
      <MemoryRouter>
        <ChatPanel readOnly={readOnly} unavailable={unavailable} />
      </MemoryRouter>
    </SessionContext>,
  );
}
describe('chat local', () => {
  it.each([{ signedIn: false }, { readOnly: true }, { unavailable: true }])(
    'deshabilita envío sin sesión, al finalizar o ante fallo: %o',
    (props) => {
      setup(props);
      expect(screen.getByRole('button', { name: 'Enviar' })).toBeDisabled();
      expect(screen.getByLabelText('Mensaje al canal')).toBeDisabled();
    },
  );
  it('renderiza texto seguro y limita mensajes a uno por segundo', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(10000);
    setup();
    fireEvent.change(screen.getByLabelText('Mensaje al canal'), {
      target: { value: '<script>alert(1)</script>' },
    });
    await userEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    expect(screen.getByText('<script>alert(1)</script>')).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
    fireEvent.change(screen.getByLabelText('Mensaje al canal'), {
      target: { value: 'otro mensaje' },
    });
    await userEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Espera un segundo');
  });
  it('permite pausar anuncios y autodesplazamiento', async () => {
    setup();
    await userEvent.click(screen.getByRole('button', { name: 'Pausar autodesplazamiento' }));
    expect(screen.getByRole('log')).toHaveAttribute('aria-live', 'off');
    expect(screen.getByRole('button', { name: 'Reanudar autodesplazamiento' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});
