import type { UserAuditMock } from './mocks';

/**
 * The audit report of one user for the Activity screen specs and the review screenshots: example.com / documentation
 * addresses, a fixed "now" (the specs pin the page's clock to it, so every time on screen is the same on every run;
 * the specs run in Asia/Almaty, UTC+5, where it is 14:50).
 */
export const ACTIVITY_NOW = '2026-11-04T09:50:00Z';
const NOW_MS = Date.parse(ACTIVITY_NOW);

/** `minutes` before now, as the API writes an instant. */
const ago = (minutes: number, seconds = 0) => new Date(NOW_MS - minutes * 60_000 - seconds * 1000).toISOString().replace('.000Z', 'Z');
/** A wall-clock time in Almaty (UTC+5), as the API writes the instant. */
const almaty = (day: number, time: string) => {
  const [hour, minute, second] = time.split(':').map(Number);
  return new Date(Date.UTC(2026, 10, day, hour, minute, second) - 5 * 3_600_000).toISOString().replace('.000Z', 'Z');
};

const firefoxWindows = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0';
const chromeMac = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const shopApp = 'ExampleShop/2.3 (iPhone; iOS 18.1)';

export const reuseTraceId = '4bf92f3577b34da6a3ce929d0e0e4736';
export const reuseEventId = '0193c2e4-0000-7000-8000-000000000005';

type Raw = Record<string, unknown>;
function event(fields: Raw): Raw {
  return {
    source: 'auth',
    nodeId: 'auth-1',
    outcome: 'success',
    severity: 'info',
    actorType: 'user',
    details: {},
    ...fields,
  };
}

function overview(): Raw[] {
  const web = { clientId: 'web-portal', ip: '198.51.100.7', country: 'KZ', userAgent: chromeMac };
  const mobile = { clientId: 'mobile-app', ip: '198.51.100.23', country: 'KZ', userAgent: shopApp };
  const stranger = { clientId: 'mobile-app', ip: '203.0.113.44', country: 'NL', userAgent: firefoxWindows };
  // Ids rise with time, as UUIDv7 does: the list is newest first, so they are written in that order and numbered from the end.
  const list: Raw[] = [
    event({ ...web, at: almaty(4, '14:41:13'), kind: 'token.issued', sessionId: 's_8Qm2LpA9kT4', traceId: 'a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1', details: { grant: 'authorization_code', scope: ['openid', 'profile'], accessJti: 'j1', binding: 'none', idToken: true, clientAuth: 'none' } }),
    event({ ...web, at: almaty(4, '14:41:12'), kind: 'login.completed', sessionId: 's_8Qm2LpA9kT4', traceId: 'a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1', details: { kind: 'login', amr: ['pwd', 'otp'], priorSession: false } }),
    event({ ...mobile, at: almaty(4, '13:02:44'), kind: 'token.issued', sessionId: 's_Zr31vN0cB7d', details: { grant: 'refresh_token', scope: ['openid', 'offline_access'], accessJti: 'j2', binding: 'dpop', idToken: false, clientAuth: 'none' } }),
    event({ at: almaty(4, '02:15:03'), source: 'central', nodeId: 'central-1', kind: 'admin.user-password.action:reset', actorType: 'admin', actorId: 'm.omarov', clientId: 'admin-console', ip: '10.0.4.12', severity: 'notice', targetType: 'user', details: {} }),
    event({ ...stranger, id: reuseEventId, at: almaty(4, '02:14:07'), kind: 'token.reuse_detected', outcome: 'failure', severity: 'high', reason: 'invalid_grant', sessionId: 's_8Qm2LpA9kT4', traceId: reuseTraceId, details: { tokenType: 'refresh_token', refreshPrefix: 'x7Qm2LpA9', familyId: 'fam_01JC…9M' } }),
    event({ ...stranger, at: almaty(4, '02:14:06'), kind: 'token.refused', outcome: 'failure', severity: 'notice', reason: 'invalid_grant', traceId: reuseTraceId, details: { grant: 'refresh_token', detail: 'refresh_token_reused' } }),
    event({ ...stranger, clientId: 'web-portal', at: almaty(4, '02:12:41'), kind: 'login.step', outcome: 'failure', severity: 'notice', reason: 'wrong_code', details: { step: 'otp', kind: 'login', conversationId: 'c-2' } }),
    event({ ...stranger, clientId: 'web-portal', at: almaty(4, '02:12:15'), kind: 'login.step', outcome: 'failure', severity: 'notice', reason: 'wrong_code', details: { step: 'otp', kind: 'login', conversationId: 'c-1' } }),
    event({ ...web, at: almaty(4, '01:55:30'), kind: 'session.ended', details: { cause: 'rp_logout', clients: 1 } }),
    event({ ...web, at: almaty(3, '23:20:09'), kind: 'account.passkey_enrolled', severity: 'notice', details: { credentialPrefix: 'AQIDBA', deviceType: 'multiDevice' } }),
  ];
  // Newest first, so the ids fall with the position, as UUIDv7 ids do with age.
  return list.map((item, index) => ({ id: `0193c2e4-0000-7000-8000-${String(100 - index).padStart(12, '0')}`, ...item }));
}

const summary = (events: Raw[]) => ({
  byKind: [
    { kind: 'login.completed', outcome: 'success', count: 34 },
    { kind: 'login.completed', outcome: 'denied', count: 1 },
    { kind: 'login.step', outcome: 'failure', count: 3 },
    { kind: 'token.issued', outcome: 'success', count: 61 },
    { kind: 'token.refused', outcome: 'failure', count: 1 },
  ],
  byClient: [{ clientId: 'mobile-app', logins: 12, tokens: 40 }, { clientId: 'web-portal', logins: 22, tokens: 21 }],
  ips: [
    { ip: '198.51.100.7', firstSeen: '2026-10-28T10:12:00Z', lastSeen: ago(9), count: 41, country: 'KZ' },
    { ip: '198.51.100.23', firstSeen: '2026-10-29T04:30:00Z', lastSeen: '2026-11-04T08:02:44Z', count: 18, country: 'KZ' },
    { ip: '203.0.113.44', firstSeen: '2026-11-03T21:12:15Z', lastSeen: '2026-11-03T21:14:07Z', count: 4, country: 'NL' },
    { ip: '10.0.4.12', firstSeen: '2026-11-03T21:15:03Z', lastSeen: '2026-11-03T21:15:03Z', count: 1 },
  ],
  userAgents: [
    { userAgent: chromeMac, firstSeen: '2026-10-28T10:12:00Z', lastSeen: ago(9), count: 41 },
    { userAgent: shopApp, firstSeen: '2026-10-29T04:30:00Z', lastSeen: '2026-11-04T08:02:44Z', count: 18 },
    { userAgent: firefoxWindows, firstSeen: '2026-11-03T21:12:15Z', lastSeen: '2026-11-03T21:14:07Z', count: 4 },
  ],
  sessions: { created: 34, ended: 31 },
  countries: [
    { country: 'KZ', firstSeen: '2026-10-28T10:12:00Z', lastSeen: ago(9), count: 59 },
    { country: 'NL', firstSeen: '2026-11-03T21:12:15Z', lastSeen: '2026-11-03T21:14:07Z', count: 4 },
  ],
  factors: [
    { kind: 'account.password_changed', at: '2026-11-03T21:15:03Z' },
    { kind: 'account.passkey_enrolled', at: '2026-11-03T18:20:09Z' },
  ],
  high: events.filter(item => item.severity === 'high'),
  adminActions: [{ at: '2026-11-03T21:15:03Z', actorId: 'm.omarov', kind: 'admin.user-password.action:reset', changed: [] }],
});

/** Ten events across two days, one rated high risk, one administrator action, one failed sign-in attempt pair. */
export function activityAudit(overrides: Partial<UserAuditMock> = {}): UserAuditMock {
  const events = overview();
  return { events, summary: summary(events), now: ACTIVITY_NOW, ...overrides };
}

/** The same report with a long history behind it: `extra` older token events in pages. */
export function pagedActivityAudit(extra = 140, overrides: Partial<UserAuditMock> = {}): UserAuditMock {
  const base = activityAudit();
  const older: Raw[] = Array.from({ length: extra }, (_, index) => ({
    id: `0193c2e4-0000-7000-8000-${String(10_000 - index).padStart(12, '0')}`,
    at: new Date(Date.parse('2026-11-03T18:00:00Z') - index * 60_000).toISOString().replace('.000Z', 'Z'),
    source: 'auth', nodeId: 'auth-1', kind: 'token.issued', outcome: 'success', severity: 'info', actorType: 'user',
    clientId: 'web-portal', ip: '198.51.100.7', country: 'KZ', userAgent: chromeMac,
    details: { grant: 'refresh_token', scope: ['openid'], accessJti: `j${index}`, binding: 'none', idToken: false, clientAuth: 'none' },
  }));
  return { ...base, events: [...base.events, ...older], ...overrides };
}

/** Auth did not answer: central's own rows alone, and a summary that holds only the administrator actions. */
export function partialActivityAudit(): UserAuditMock {
  const base = activityAudit();
  const admin = base.events.filter(item => String(item.kind).startsWith('admin.'));
  return { ...base, events: admin, partial: ['auth'], summary: { adminActions: (base.summary as { adminActions: unknown }).adminActions } };
}

export const emptyActivityAudit = (): UserAuditMock => ({
  events: [],
  summary: { byKind: [], byClient: [], ips: [], userAgents: [], sessions: { created: 0, ended: 0 }, countries: [], factors: [], high: [], adminActions: [] },
  now: ACTIVITY_NOW,
});
