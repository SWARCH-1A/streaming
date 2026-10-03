import { MemoryRouter } from 'react-router';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { SessionProvider } from '@/src/shell/session/SessionProvider';

import { AuthPage } from './AuthPage';

function setup() {
  render(
    <SessionProvider>
      <MemoryRouter>
        <AuthPage mode="register" />
      </MemoryRouter>
    </SessionProvider>,
  );
}
describe('formulario de registro', () => {
  it('asocia errores a campos y lleva el foco al primero', async () => {
    setup();
    await userEvent.click(screen.getByRole('button', { name: 'Crear cuenta de demostración' }));
    expect(screen.getByLabelText('Correo electrónico')).toHaveFocus();
    expect(screen.getByLabelText('Correo electrónico')).toHaveAttribute(
      'aria-describedby',
      'email-error',
    );
    expect(screen.getAllByRole('alert')).toHaveLength(3);
  });
  it('valida sin crear sesión y limpia la contraseña', async () => {
    setup();
    await userEvent.type(screen.getByLabelText('Correo electrónico'), 'demo@example.test');
    await userEvent.type(screen.getByLabelText('Handle'), 'demo_01');
    await userEvent.type(screen.getByLabelText('Contraseña'), 'demopassword12');
    await userEvent.click(screen.getByRole('button', { name: 'Crear cuenta de demostración' }));
    expect(
      screen.getByText('Formulario validado. Continúa con una sesión de demostración.'),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Contraseña')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir a iniciar sesión →' })).toHaveAttribute(
      'href',
      '/login',
    );
  });
});
