import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { IngestPanel } from './IngestPanel';

describe('configuración RTMP local', () => {
  it('bloquea rotación durante emisión y la permite offline', async () => {
    const { rerender } = render(<IngestPanel canRotate={false} />);
    expect(screen.getByRole('button', { name: 'Rotar clave de ejemplo' })).toBeDisabled();
    rerender(<IngestPanel canRotate />);
    await userEvent.click(screen.getByRole('button', { name: 'Rotar clave de ejemplo' }));
    expect(screen.getByLabelText('Clave de ejemplo')).toHaveValue('DEMO-NOT-A-REAL-KEY-2');
  });
});
