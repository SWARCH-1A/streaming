import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router';
import type { P2Follow } from '@contracts/p2';

import { Button } from '@/src/components/atoms/Button';
import { ChannelAlerts } from '@/src/modules/notifications/entry';
import { errorMessage, request } from '@/src/shared/api/http';
import { useSession } from '@/src/shell/session/useSession';

interface FollowButtonProps {
  channelId: string;
  ownerUserId: string;
  /** Follow state already read by the SSR loader for this user, if any. */
  initial: P2Follow | null;
}

/** PUT/DELETE /api/channels/{id}/followers/me (SPEC-03). The follower is always the session user. */
export function FollowButton({ channelId, ownerUserId, initial }: FollowButtonProps) {
  const { user, status } = useSession();
  const { pathname } = useLocation();
  const [follow, setFollow] = useState<P2Follow | null>(initial);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const userId = user?.userId;

  useEffect(() => {
    if (!userId || follow?.channelId === channelId) return;
    const controller = new AbortController();
    request<P2Follow>(`/api/channels/${encodeURIComponent(channelId)}/followers/me`, {
      signal: controller.signal,
    }).then(setFollow, () => undefined);
    return () => controller.abort();
  }, [userId, channelId, follow?.channelId]);

  if (status === 'loading') return null;
  if (!user)
    return (
      <Link to="/login" state={{ from: pathname }}>
        Inicia sesión para seguir este canal
      </Link>
    );
  if (user.userId === ownerUserId) return null;

  const following = follow?.following ?? false;
  async function toggle() {
    setPending(true);
    setError(null);
    try {
      // One key per click: a network retry of the same click cannot apply twice.
      const next = await request<P2Follow>(
        `/api/channels/${encodeURIComponent(channelId)}/followers/me`,
        {
          method: following ? 'DELETE' : 'PUT',
          headers: { 'Idempotency-Key': crypto.randomUUID() },
          coreMutation: true,
        },
      );
      setFollow(next);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="stack" style={{ gap: '0.25rem' }}>
      <Button
        variant={following ? 'secondary' : 'primary'}
        loading={pending}
        aria-pressed={following}
        onClick={() => void toggle()}
      >
        {following ? 'Siguiendo' : 'Seguir'}
      </Button>
      {following ? <ChannelAlerts channelId={channelId} /> : null}
      {error ? (
        <p role="alert" className="field-error">
          {error}
        </p>
      ) : null}
    </div>
  );
}
