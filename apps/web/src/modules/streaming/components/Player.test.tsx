import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Player } from './Player';

describe('player de demostración', () => {
  it('permite pausar y reanudar con estado accesible', async () => {
    render(<Player poster="/poster.jpg" title="Directo" />);
    await userEvent.click(screen.getByRole('button', { name: 'Pausar vista previa' }));
    expect(screen.getByText('Vista previa en pausa')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reanudar vista previa' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
  it('informa reconexión sin simular reproducción confirmada', () => {
    render(<Player poster="/poster.jpg" title="Directo" state="RECONNECT_GRACE" />);
    expect(screen.getByText('Reconectando la señal')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pausar vista previa' })).toBeDisabled();
  });
  it('ofrece reintento independiente ante error', async () => {
    const retry = vi.fn();
    render(<Player poster="/poster.jpg" title="Directo" state="ERROR" onRetry={retry} />);
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(retry).toHaveBeenCalledOnce();
  });
});
