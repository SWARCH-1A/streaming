import { MemoryRouter } from 'react-router';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Input } from '@/src/components/atoms/Input';

import { RouteFocus } from './RouteFocus';

function View({ loaded }: { loaded: boolean }) {
  return (
    <MemoryRouter>
      <main id="main-content" tabIndex={-1}>
        {loaded ? <h1>Estudio de emisión</h1> : <p role="status">Cargando…</p>}
        <Input aria-label="Búsqueda" />
        <RouteFocus />
      </main>
    </MemoryRouter>
  );
}
beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});
describe('foco al navegar', () => {
  it('espera el título de una carga lenta y actualiza la marca del documento', async () => {
    const { rerender } = render(<View loaded={false} />);
    expect(screen.getByRole('main')).toHaveFocus();
    rerender(<View loaded />);
    await waitFor(() => expect(screen.getByRole('heading')).toHaveFocus());
    expect(document.title).toBe('Estudio de emisión · Vault tv');
  });
  it('no roba el foco si el usuario ya comenzó a escribir', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<View loaded={false} />);
    await user.type(screen.getByRole('textbox'), 'canal');
    rerender(<View loaded />);
    await waitFor(() => expect(document.title).toBe('Estudio de emisión · Vault tv'));
    expect(screen.getByRole('textbox')).toHaveFocus();
    expect(screen.getByRole('textbox')).toHaveValue('canal');
  });
});
