import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { ViewErrorBoundary } from './ViewErrorBoundary';

function BrokenView(): never {
  throw new Error('Fallo de prueba');
}
it('un error de chat conserva el resto de la vista', () => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  render(
    <>
      <p>Reproductor independiente</p>
      <ViewErrorBoundary name="El chat">
        <BrokenView />
      </ViewErrorBoundary>
    </>,
  );
  expect(screen.getByText('Reproductor independiente')).toBeInTheDocument();
  expect(screen.getByText('El chat no está disponible')).toBeInTheDocument();
});
describe('boundary', () => {
  it('muestra los hijos mientras no fallen', () => {
    render(
      <ViewErrorBoundary name="Vista">
        <p>Contenido</p>
      </ViewErrorBoundary>,
    );
    expect(screen.getByText('Contenido')).toBeInTheDocument();
  });
});
