import type { P2ReplayMessage } from '@contracts/p2';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ReplayChatPanel } from './ReplayChatPanel';

function message(sequence: number, mediaOffsetMs: number, text: string): P2ReplayMessage {
  return {
    messageId: `msg_${sequence}`,
    sessionId: 'ses_fixture',
    sequence,
    author: { userId: 'usr_fixture', handle: 'fixture_01', displayName: 'Ana', avatarUri: null },
    text,
    serverCreatedAtUtc: '2026-10-31T20:00:00Z',
    streamOffsetMs: mediaOffsetMs,
    streamGeneration: 1,
    mediaOffsetMs,
    moderationVersion: 1,
  };
}

describe('ReplayChatPanel', () => {
  const messages = [message(2, 9000, 'después'), message(1, 2000, 'antes')];

  it('muestra en orden solo los mensajes que el video ya alcanzó', () => {
    render(<ReplayChatPanel messages={messages} positionMs={5000} />);
    const log = screen.getByRole('log', { name: 'Mensajes del chat' });
    expect(log).toHaveTextContent('antes');
    expect(log).not.toHaveTextContent('después');
    expect(screen.getByText('0:02')).toBeInTheDocument();
  });

  it('es un panel lateral de solo lectura', () => {
    render(<ReplayChatPanel messages={messages} positionMs={10000} />);
    expect(screen.getByRole('complementary', { name: 'Chat de la grabación' })).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(
      screen.getByText('Repetición del chat: no se pueden enviar mensajes.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('log')).toHaveTextContent(/antes.*después/s);
  });
});
