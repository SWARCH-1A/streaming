import { expect, it } from 'vitest';

import { auditQuery, defaultAuditFilters } from './auditQuery';

it('bounds the default query to 50 events and a fixed 24-hour UTC window', () => {
  const query = new URLSearchParams(
    auditQuery(defaultAuditFilters(), new Date('2026-11-22T18:30:00Z')),
  );
  expect(Object.fromEntries(query)).toEqual({
    limit: '50',
    from: '2026-11-21T18:30:00.000Z',
    to: '2026-11-22T18:30:00.000Z',
  });
});

it('sends exact, trimmed filters and translates local custom dates to UTC', () => {
  const from = '2026-11-21T10:30',
    to = '2026-11-22T11:45';
  const query = new URLSearchParams(
    auditQuery({
      ...defaultAuditFilters(),
      range: 'custom',
      from,
      to,
      producer: 'vod',
      outcome: 'DENIED',
      action: 'VOD_PATCH',
      actorId: ' usr_fixture ',
      targetId: ' vod_fixture ',
      requestId: ' req.fixture-1 ',
    }),
  );
  expect(Object.fromEntries(query)).toEqual({
    limit: '50',
    from: new Date(from).toISOString(),
    to: new Date(to).toISOString(),
    producer: 'vod',
    outcome: 'DENIED',
    action: 'VOD_PATCH',
    actorId: 'usr_fixture',
    targetId: 'vod_fixture',
    requestId: 'req.fixture-1',
  });
});

it.each(['', 'invalid'])('rejects missing or invalid custom dates: %s', (from) => {
  expect(() =>
    auditQuery({ ...defaultAuditFilters(), range: 'custom', from, to: '2026-11-22T10:00' }),
  ).toThrow(/fecha y hora/);
});

it('rejects reversed or empty intervals and unsupported characters before making a request', () => {
  for (const to of ['2026-11-21T10:00', '2026-11-22T10:00'])
    expect(() =>
      auditQuery({ ...defaultAuditFilters(), range: 'custom', from: '2026-11-22T10:00', to }),
    ).toThrow(/anterior/);
  for (const field of ['actorId', 'targetId', 'requestId'] as const)
    expect(() => auditQuery({ ...defaultAuditFilters(), [field]: '<script>' })).toThrow(/ID/);
  expect(() => auditQuery({ ...defaultAuditFilters(), requestId: 'a'.repeat(81) })).toThrow(/80/);
});
