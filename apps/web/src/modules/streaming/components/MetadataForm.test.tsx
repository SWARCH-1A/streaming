import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { MetadataForm } from './MetadataForm';

describe('editor de metadatos', () => {
  it('aplica el máximo de cinco etiquetas y permite retirarlas', async () => {
    render(<MetadataForm onSave={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Inglés' }));
    await userEvent.click(screen.getByRole('button', { name: 'Competitivo' }));
    expect(screen.getByRole('button', { name: 'Casual' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Español' }));
    expect(screen.getByRole('button', { name: 'Casual' })).toBeEnabled();
  });
  it('no guarda un título vacío y muestra el error asociado', async () => {
    const save = vi.fn();
    render(<MetadataForm onSave={save} />);
    await userEvent.clear(screen.getByLabelText('Título de la transmisión'));
    await userEvent.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(save).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Título de la transmisión')).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('1 a 100');
  });
});
