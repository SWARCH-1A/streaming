import { useEffect, useState } from 'react';

import artwork from '@/public/images/signal-grid.svg';

export function StreamPreview({ uri, sessionId }: { uri: string; sessionId: string }) {
  const [version, setVersion] = useState(0);
  const [failed, setFailed] = useState(false);
  const valid =
    /^ses_[a-zA-Z0-9_-]{1,64}$/.test(sessionId) && uri === `/hls/${sessionId}/preview.jpg`;
  useEffect(() => {
    const timer = setInterval(
      () => {
        setFailed(false);
        setVersion((value) => value + 1);
      },
      failed ? 5000 : 15000,
    );
    return () => clearInterval(timer);
  }, [failed]);
  return (
    <>
      <img src={artwork} alt="" width="640" height="360" />
      {valid && !failed ? (
        <img
          src={`${uri}?v=${version}`}
          alt=""
          width="640"
          height="360"
          loading="lazy"
          onError={() => setFailed(true)}
        />
      ) : null}
    </>
  );
}
