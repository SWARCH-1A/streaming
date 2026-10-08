import type { FormEvent } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Button } from './Button';

describe('Button', () => {
  it('conserva la semántica y evita enviar formularios accidentalmente', async () => {
    const submit = vi.fn((event: FormEvent<HTMLFormElement>) => event.preventDefault());
    const click = vi.fn();
    render(
      <form onSubmit={submit}>
        <Button onClick={click}>Guardar</Button>
      </form>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    expect(click).toHaveBeenCalledOnce();
    expect(submit).not.toHaveBeenCalled();
  });
  it.each([{ disabled: true }, { loading: true }])(
    'bloquea la acción cuando está deshabilitado u ocupado: %o',
    async (props) => {
      const click = vi.fn();
      render(
        <Button onClick={click} {...props}>
          Enviar
        </Button>,
      );
      const button = screen.getByRole('button', { name: 'Enviar' });
      expect(button).toBeDisabled();
      await userEvent.click(button);
      expect(click).not.toHaveBeenCalled();
      if ('loading' in props) expect(button).toHaveAttribute('aria-busy', 'true');
    },
  );
  it('permite envío explícito y activación con teclado', async () => {
    const submit = vi.fn((event: FormEvent<HTMLFormElement>) => event.preventDefault());
    render(
      <form onSubmit={submit}>
        <Button type="submit">Crear</Button>
      </form>,
    );
    await userEvent.tab();
    await userEvent.keyboard('{Enter}');
    expect(submit).toHaveBeenCalledOnce();
  });
});
