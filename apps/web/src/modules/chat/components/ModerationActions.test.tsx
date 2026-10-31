import type { ChatMessage } from '@contracts/p1';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ModerationActions } from './ModerationActions';

const message = {
  messageId: 'msg_1',
  sessionId: 'ses_1',
  sequence: 1,
  author: { userId: 'usr_ana', handle: 'ana_01', displayName: 'Ana', avatarUri: null },
  text: 'hola',
  serverCreatedAtUtc: '2026-10-31T20:00:00Z',
  streamOffsetMs: 1000,
  streamGeneration: 1,
} as ChatMessage;

describe('ModerationActions', () => {
  it('pliega las opciones tras un botón y las despliega al pulsarlo', async () => {
    render(<ModerationActions channelId="chn_1" message={message} onResult={vi.fn()} />);
    const toggle = screen.getByRole('button', { name: 'Moderar mensaje de Ana' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Eliminar mensaje' })).not.toBeInTheDocument();

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(
      screen.getByRole('group', { name: 'Opciones de moderación para Ana' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Eliminar mensaje' })).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('group')).not.toBeInTheDocument();
  });
});
