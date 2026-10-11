import { act, fireEvent, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import artwork from '@/public/images/signal-grid.svg';

import { StreamPreview } from './StreamPreview';

afterEach(() => vi.useRealTimers());
describe('fotogramas de exploración', () => {
  it('usa la imagen del canal mientras el fotograma no está disponible y vuelve a intentarlo', async () => {
    vi.useFakeTimers();
    const { container } = render(
      <StreamPreview uri="/hls/ses_demo/preview.jpg" sessionId="ses_demo" />,
    );
    const preview = container.querySelector('img[src^="/hls/"]');
    expect(preview).not.toBeNull();
    fireEvent.error(preview!);
    expect(container.querySelector('img[src^="/hls/"]')).toBeNull();
    expect(container.querySelector('img')).toHaveAttribute('src', artwork);
    await act(() => vi.advanceTimersByTime(5000));
    expect(container.querySelector('img[src^="/hls/"]')).toHaveAttribute(
      'src',
      '/hls/ses_demo/preview.jpg?v=1',
    );
  });
  it('actualiza el fotograma sin crear un reproductor ni cargar un manifiesto', async () => {
    vi.useFakeTimers();
    const { container } = render(
      <StreamPreview uri="/hls/ses_demo/preview.jpg" sessionId="ses_demo" />,
    );
    await act(() => vi.advanceTimersByTime(15000));
    expect(container.querySelector('img[src^="/hls/"]')).toHaveAttribute(
      'src',
      '/hls/ses_demo/preview.jpg?v=1',
    );
    expect(container.querySelector('video')).toBeNull();
  });
  it('rechaza una URL ajena a la sesión o al mismo origen', () => {
    const { container, rerender } = render(
      <StreamPreview uri="https://example.org/preview.jpg" sessionId="ses_demo" />,
    );
    expect(container.querySelectorAll('img')).toHaveLength(1);
    rerender(<StreamPreview uri="/hls/ses_other/preview.jpg" sessionId="ses_demo" />);
    expect(container.querySelectorAll('img')).toHaveLength(1);
    rerender(
      <StreamPreview
        uri="/hls/../../api/profile/me/preview.jpg"
        sessionId="../../api/profile/me"
      />,
    );
    expect(container.querySelectorAll('img')).toHaveLength(1);
  });
});
