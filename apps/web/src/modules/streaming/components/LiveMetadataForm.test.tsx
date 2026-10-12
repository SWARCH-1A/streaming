import type { BootstrapStream, Taxonomy } from '@contracts/p1';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { HttpError } from '@/src/shared/api/http';

import { LiveMetadataForm } from './LiveMetadataForm';

const catalog: Taxonomy = {
  catalogVersion: 1,
  categories: [{ id: 'category', name: 'Categoría activa', active: true }],
  tags: Array.from({ length: 6 }, (_, i) => ({
    id: `tag_${i}`,
    name: `Etiqueta ${i}`,
    active: true,
  })),
};

describe('metadatos reales accesibles', () => {
  it('asocia errores y enfoca el primero, sin robar foco al corregir otro campo', async () => {
    const user = userEvent.setup();
    const save = vi.fn();
    render(
      <LiveMetadataForm
        initial={{ title: '   ', categoryId: '', tagIds: [] }}
        catalog={catalog}
        labels={null}
        creating
        save={save}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Configurar emisión' }));
    const title = screen.getByLabelText('Título de la transmisión');
    expect(title).toHaveFocus();
    expect(title).toHaveAccessibleDescription(/Escribe un título.*100 caracteres/);
    expect(title).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Categoría obligatoria')).toHaveAccessibleDescription(
      'Selecciona una categoría.',
    );
    await user.selectOptions(screen.getByLabelText('Categoría obligatoria'), 'category');
    expect(screen.getByLabelText('Categoría obligatoria')).toHaveFocus();
    expect(save).not.toHaveBeenCalled();
  });

  it('permite cero etiquetas y bloquea una sexta selección', async () => {
    const user = userEvent.setup();
    const save = vi.fn().mockResolvedValue(undefined);
    render(
      <LiveMetadataForm
        initial={{ title: 'Directo', categoryId: 'category', tagIds: [] }}
        catalog={catalog}
        labels={null}
        creating
        save={save}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Configurar emisión' }));
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({ tagIds: [] }),
      expect.anything(),
      expect.any(String),
      expect.any(AbortSignal),
    );
    for (let i = 0; i < 5; i++)
      await user.click(screen.getByRole('button', { name: `Etiqueta ${i}` }));
    expect(screen.getByRole('button', { name: 'Etiqueta 5' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Etiqueta 0' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await user.click(screen.getByRole('button', { name: 'Etiqueta 0' }));
    expect(screen.getByRole('button', { name: 'Etiqueta 5' })).toBeEnabled();
  });

  it('conserva tombstones omitiendo categoría y etiquetas al editar solo el título', async () => {
    const user = userEvent.setup();
    const save = vi.fn().mockResolvedValue(undefined);
    const labels = {
      category: { id: 'retired', name: 'Archivo' },
      tags: [{ id: 'retired-tag', name: 'Histórica' }],
    } as BootstrapStream;
    render(
      <LiveMetadataForm
        initial={{ title: 'Anterior', categoryId: 'retired', tagIds: ['retired-tag'] }}
        catalog={catalog}
        labels={labels}
        creating={false}
        save={save}
      />,
    );
    await user.clear(screen.getByLabelText('Título de la transmisión'));
    await user.type(screen.getByLabelText('Título de la transmisión'), 'Nuevo');
    await user.click(screen.getByRole('button', { name: 'Guardar metadatos' }));
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: 'retired', tagIds: ['retired-tag'] }),
      { title: 'Nuevo' },
      expect.any(String),
      expect.any(AbortSignal),
    );
  });

  it('conserva datos y enfoca el error servidor después de habilitar el formulario', async () => {
    const user = userEvent.setup();
    const save = vi.fn().mockRejectedValue(
      new HttpError(422, {
        code: 'INVALID_CATEGORY',
        message: 'Selección inválida',
        requestId: 'req_test',
        fieldErrors: { categoryId: 'La categoría dejó de estar activa.' },
      }),
    );
    render(
      <LiveMetadataForm
        initial={{ title: 'Directo', categoryId: 'category', tagIds: [] }}
        catalog={catalog}
        labels={null}
        creating
        save={save}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Configurar emisión' }));
    const category = screen.getByLabelText('Categoría obligatoria');
    await waitFor(() => expect(category).toHaveFocus());
    expect(category).toBeEnabled();
    expect(category).toHaveAccessibleDescription('La categoría dejó de estar activa.');
    expect(screen.getByLabelText('Título de la transmisión')).toHaveValue('Directo');
  });
});
