import type { P2AuditPage, P2SecurityAuditPayload } from '@contracts/p2/index';

export type AuditEntry = P2AuditPage['items'][number];
export type AuditRange = '24h' | '1h' | '7d' | 'custom';
export interface AuditFilters {
  range: AuditRange;
  producer: '' | 'core' | 'vod';
  outcome: '' | P2SecurityAuditPayload['outcome'];
  action: '' | P2SecurityAuditPayload['action'];
  actorId: string;
  targetId: string;
  requestId: string;
  from: string;
  to: string;
}

export const auditActions = {
  ADMIN_ACCESS: 'Acceso administrativo',
  ACCOUNT_STATUS: 'Cambio de estado de cuenta',
  VOD_PATCH: 'Edición de grabación',
  VOD_PUBLISH: 'Publicación de grabación',
  VOD_DELETE: 'Eliminación de grabación',
  VOD_REMOVE: 'Retirada administrativa',
} satisfies Record<P2SecurityAuditPayload['action'], string>;

export function defaultAuditFilters(): AuditFilters {
  return {
    range: '24h',
    producer: '',
    outcome: '',
    action: '',
    actorId: '',
    targetId: '',
    requestId: '',
    from: '',
    to: '',
  };
}

export function auditQuery(filters: AuditFilters, now = new Date()): string {
  const params = new URLSearchParams({ limit: '50' });
  for (const key of ['actorId', 'targetId', 'requestId'] as const) {
    const value = filters[key].trim();
    const pattern = key === 'requestId' ? /^[A-Za-z0-9._-]{1,80}$/ : /^[A-Za-z0-9_-]{1,128}$/;
    if (value && !pattern.test(value))
      throw new Error(
        key === 'requestId'
          ? 'El ID de solicitud admite hasta 80 letras, números, puntos, guiones o guiones bajos.'
          : 'Los ID de usuario y recurso admiten hasta 128 letras, números, guiones o guiones bajos.',
      );
    if (value) params.set(key, value);
  }
  for (const key of ['producer', 'outcome', 'action'] as const)
    if (filters[key]) params.set(key, filters[key]);

  let from: Date, to: Date;
  if (filters.range === 'custom') {
    from = new Date(filters.from);
    to = new Date(filters.to);
    if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime()))
      throw new Error('Indica una fecha y hora de inicio y de fin válidas.');
    if (from >= to) throw new Error('La fecha de inicio debe ser anterior a la fecha de fin.');
  } else {
    const hours = { '1h': 1, '24h': 24, '7d': 168 }[filters.range];
    to = now;
    from = new Date(now.getTime() - hours * 60 * 60 * 1000);
  }
  params.set('from', from.toISOString());
  params.set('to', to.toISOString());
  return params.toString();
}

export function auditPayload(entry: AuditEntry): P2SecurityAuditPayload {
  // request() validates the complete event payload against the generated JSON Schema.
  return entry.event.payload as P2SecurityAuditPayload;
}

export function auditTime(value: string): string {
  return new Intl.DateTimeFormat('es', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(new Date(value));
}
