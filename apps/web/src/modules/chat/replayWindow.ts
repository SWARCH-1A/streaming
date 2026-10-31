import type { P2ChatReplayItem } from '@contracts/p2';

/** Ventana de replay: 30 s de video, pedida por bloques alineados para reutilizar la caché. */
export const REPLAY_WINDOW_MS = 30_000;
/** Faltando este tiempo para el final de la ventana se pide la siguiente. */
export const REPLAY_PREFETCH_MS = 5_000;

export function windowStart(positionMs: number) {
  return Math.max(0, Math.floor(positionMs / REPLAY_WINDOW_MS) * REPLAY_WINDOW_MS);
}

/** Ventanas necesarias en la posición: la actual y, cerca de su fin, la siguiente. */
export function windowsFor(positionMs: number): number[] {
  const start = windowStart(positionMs);
  return start + REPLAY_WINDOW_MS - positionMs <= REPLAY_PREFETCH_MS
    ? [start, start + REPLAY_WINDOW_MS]
    : [start];
}

/**
 * Mensajes visibles en la posición del video: los ya ocurridos dentro de las ventanas cargadas,
 * en orden de video. Al adelantar o retroceder, la lista sigue a la nueva posición.
 */
export function visibleAt(
  windows: ReadonlyMap<number, readonly P2ChatReplayItem[]>,
  positionMs: number,
  max = 50,
): P2ChatReplayItem[] {
  const items: P2ChatReplayItem[] = [];
  for (const [start, loaded] of windows)
    if (start <= positionMs)
      for (const item of loaded) if (item.vodOffsetMs <= positionMs) items.push(item);
  return items.sort((a, b) => a.vodOffsetMs - b.vodOffsetMs || a.sequence - b.sequence).slice(-max);
}
