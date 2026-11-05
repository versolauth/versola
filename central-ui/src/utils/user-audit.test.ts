import { describe, expect, it } from 'vitest';
import {
  adminActionEvent,
  attentionEvents,
  categoryOf,
  clientsOf,
  currentCursor,
  defaultView,
  describeEvent,
  describeUserAgent,
  filterChips,
  filterEvents,
  hasActiveFilters,
  NO_FILTERS,
  pageOf,
  pageRangeLabel,
  parseActivityParams,
  parseAuditEvent,
  parseUserAuditReport,
  popCursor,
  presetWindow,
  pushCursor,
  resultOf,
  serializeActivityParams,
  summaryCards,
  type AuditEvent,
  type AuditSummary,
} from './user-audit';

function event(overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    id: 'e1', at: '2026-11-04T09:41:12Z', source: 'auth', kind: 'login.completed', outcome: 'success', severity: 'info',
    actorType: 'user', clientId: 'web-portal', ip: '198.51.100.7', country: 'KZ', details: {}, ...overrides,
  };
}

const summary: AuditSummary = {
  auth: true,
  byKind: [
    { kind: 'login.completed', outcome: 'success', count: 34 },
    { kind: 'login.step', outcome: 'success', count: 40 },
    { kind: 'login.step', outcome: 'failure', count: 3 },
    { kind: 'login.completed', outcome: 'denied', count: 1 },
    { kind: 'token.refused', outcome: 'failure', count: 9 },
  ],
  byClient: [], ips: [], userAgents: [], countries: [],
  sessions: { created: 34, ended: 30 },
  factors: [{ kind: 'account.password_changed', at: '2026-11-04T01:00:00Z' }, { kind: 'account.passkey_enrolled', at: '2026-11-03T18:20:00Z' }],
  high: [event({ id: 'h1', at: '2026-11-03T21:14:07Z', kind: 'token.reuse_detected', outcome: 'failure', severity: 'high' })],
  adminActions: [{ at: '2026-11-03T21:15:03Z', actorId: 'm.omarov', kind: 'admin.user-password.action:reset', changed: [] }],
};

describe('parsing the report', () => {
  const raw = {
    userId: 'u1',
    window: { from: '2026-10-28T09:50:00Z', to: '2026-11-04T09:50:00Z', retentionFrom: '2026-10-28T09:50:00Z' },
    events: [{ id: 'e1', at: '2026-11-04T09:41:12Z', source: 'auth', nodeId: 'n', kind: 'login.completed', outcome: 'success', severity: 'info', actorType: 'user', details: { amr: ['pwd'] }, country: 'KZ' }],
    nextCursor: 'abc',
    partial: [],
  };

  it('reads the events, the window and the cursor', () => {
    const report = parseUserAuditReport(raw);
    expect(report.events[0]).toMatchObject({ id: 'e1', kind: 'login.completed', country: 'KZ', details: { amr: ['pwd'] } });
    expect(report.nextCursor).toBe('abc');
    expect(report.summary).toBeNull();
    expect(report.truncated).toBe(false);
  });

  it('has no cursor on the last page and no country when the API sent none', () => {
    const report = parseUserAuditReport({ ...raw, nextCursor: undefined, events: [{ ...raw.events[0], country: undefined }] });
    expect(report.nextCursor).toBeNull();
    expect(report.events[0].country).toBeUndefined();
  });

  it('reads a summary with auth missing as admin actions alone', () => {
    const report = parseUserAuditReport({
      ...raw, partial: ['auth'], truncated: true,
      summary: { adminActions: [{ at: '2026-11-04T02:15:03Z', actorId: 'm.omarov', kind: 'admin.user.update', changed: ['email'] }] },
    });
    expect(report.partial).toEqual(['auth']);
    expect(report.truncated).toBe(true);
    expect(report.summary?.auth).toBe(false);
    expect(report.summary?.adminActions).toHaveLength(1);
  });

  it('reads every part of the summary', () => {
    const parsed = parseUserAuditReport({
      ...raw,
      summary: {
        byKind: [{ kind: 'login.completed', outcome: 'success', count: 3 }], byClient: [{ clientId: 'web-portal', logins: 2, tokens: 5 }],
        ips: [{ ip: '198.51.100.7', firstSeen: '2026-10-28T10:00:00Z', lastSeen: '2026-11-04T09:00:00Z', count: 4, country: 'KZ' }],
        userAgents: [{ userAgent: 'Mozilla/5.0', firstSeen: '2026-10-28T10:00:00Z', lastSeen: '2026-11-04T09:00:00Z', count: 4 }],
        sessions: { created: 3, ended: 1 }, countries: [{ country: 'KZ', firstSeen: '2026-10-28T10:00:00Z', lastSeen: '2026-11-04T09:00:00Z', count: 4 }],
        factors: [{ kind: 'account.password_changed', at: '2026-11-04T01:00:00Z' }], high: [], adminActions: [],
      },
    }).summary!;
    expect(parsed.auth).toBe(true);
    expect(parsed.ips[0].country).toBe('KZ');
    expect(parsed.sessions).toEqual({ created: 3, ended: 1 });
    expect(parsed.countries[0].count).toBe(4);
  });

  it('refuses a body that is not a report, and an event without an id', () => {
    expect(() => parseUserAuditReport(null)).toThrow(/malformed/);
    expect(() => parseUserAuditReport({ events: [], window: null })).toThrow(/malformed/);
    expect(() => parseAuditEvent({ at: 'x', kind: 'login.step' })).toThrow(/event id/);
  });
});

describe('words for an event', () => {
  it('names a sign-in step by its step and its verdict', () => {
    expect(describeEvent(event({ kind: 'login.step', outcome: 'failure', reason: 'wrong_code', details: { step: 'otp', kind: 'login' } })))
      .toEqual({ title: 'Code check failed', subtitle: 'wrong_code · sign-in' });
    expect(describeEvent(event({ kind: 'login.step', outcome: 'success', details: { step: 'password' } })).title).toBe('Password check passed');
  });

  it('describes a sign-in with the methods used and a token with its grant', () => {
    expect(describeEvent(event({ details: { amr: ['pwd', 'otp'] } }))).toEqual({ title: 'Signed in', subtitle: 'pwd, otp' });
    expect(describeEvent(event({ kind: 'token.issued', details: { grant: 'refresh_token' } }))).toEqual({ title: 'Token issued', subtitle: 'refresh token' });
    expect(describeEvent(event({ kind: 'token.refused', outcome: 'failure', reason: 'invalid_grant' }))).toEqual({ title: 'Token refused', subtitle: 'invalid_grant' });
    expect(describeEvent(event({ kind: 'token.reuse_detected', details: { tokenType: 'refresh_token' } })).title).toBe('Refresh token reused');
  });

  it('names account changes and administrator actions', () => {
    expect(describeEvent(event({ kind: 'account.passkey_enrolled' })).title).toBe('Passkey added');
    expect(describeEvent(event({ kind: 'admin.user-password.action:reset', actorId: 'm.omarov' })))
      .toEqual({ title: 'Password reset by an admin', subtitle: 'by m.omarov' });
    expect(describeEvent(event({ kind: 'admin.user-roles.update', actorId: 'a', details: { changed: ['roles'] } })))
      .toEqual({ title: 'User roles updated by an admin', subtitle: 'by a · changed roles' });
  });

  it('shows the kind itself for one it does not know', () => {
    expect(describeEvent(event({ kind: 'something.new', reason: 'why' }))).toEqual({ title: 'something.new', subtitle: 'why' });
  });

  it('puts an event in a category', () => {
    expect(categoryOf(event({ kind: 'login.step' }))).toBe('signin');
    expect(categoryOf(event({ kind: 'consent.granted' }))).toBe('signin');
    expect(categoryOf(event({ kind: 'token.issued' }))).toBe('tokens');
    expect(categoryOf(event({ kind: 'client.auth_failed' }))).toBe('tokens');
    expect(categoryOf(event({ kind: 'account.password_changed' }))).toBe('account');
    expect(categoryOf(event({ kind: 'admin.user.update', source: 'central' }))).toBe('admin');
    expect(categoryOf(event({ kind: 'account.password_reset', actorType: 'admin' }))).toBe('admin');
  });

  it('says the result in words, and High risk wins over a failure', () => {
    expect(resultOf(event())).toEqual({ label: 'Succeeded', tone: 'ok' });
    expect(resultOf(event({ outcome: 'failure' }))).toEqual({ label: 'Failed', tone: 'warn' });
    expect(resultOf(event({ outcome: 'denied' }))).toEqual({ label: 'Denied', tone: 'warn' });
    expect(resultOf(event({ kind: 'account.passkey_enrolled' }))).toEqual({ label: 'Account change', tone: 'info' });
    expect(resultOf(event({ outcome: 'failure', severity: 'high' }))).toEqual({ label: 'High risk', tone: 'danger' });
  });

  it('reads the browser and system out of a user agent, and an app out of its own string', () => {
    expect(describeUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0')).toBe('Firefox 132 · Windows');
    expect(describeUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')).toBe('Chrome 131 · macOS');
    expect(describeUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15')).toBe('Safari 17 · macOS');
    expect(describeUserAgent('Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0')).toBe('Edge 131 · Windows');
    expect(describeUserAgent('ExampleShop/4.2 (iPhone; iOS 18.1)')).toBe('ExampleShop 4.2 · iOS');
    expect(describeUserAgent('curl')).toBe('curl');
    expect(describeUserAgent(undefined)).toBe('');
  });
});

describe('the summary cards', () => {
  const zoneTime = (at: string) => `at ${at.slice(11, 16)}`;

  it('counts sign-ins as the sessions created plus the failed attempts', () => {
    const [signins, failed] = summaryCards(summary, [], false, zoneTime);
    expect(failed.value).toBe(4);
    expect(signins.value).toBe(38);
    expect(signins.detail).toBe('34 succeeded');
  });

  it('names the time of the last failure from the loaded page, and leaves it out when the page has none', () => {
    const page = [event({ kind: 'login.step', outcome: 'failure', at: '2026-11-04T02:12:41Z' })];
    expect(summaryCards(summary, page, false, zoneTime)[1].detail).toBe('last at 02:12');
    expect(summaryCards(summary, [], false, zoneTime)[1].detail).toBe('in this period');
  });

  it('names the kinds of credential changed and counts administrator actions', () => {
    const cards = summaryCards(summary, [], false, zoneTime);
    expect(cards[2].value).toBe(2);
    expect(cards[2].detail).toBe('password, passkey');
    expect(cards[3].value).toBe(1);
    expect(cards[3].detail).toContain('password reset');
  });

  it('shows unavailable in the cards that need auth when only administrator actions came back', () => {
    const partial: AuditSummary = { ...summary, auth: false, byKind: [], factors: [], high: [], sessions: { created: 0, ended: 0 } };
    const cards = summaryCards(partial, [], false, zoneTime);
    expect(cards.map(card => card.value)).toEqual([null, null, null, 1]);
    expect(cards.slice(0, 3).every(card => card.detail === 'unavailable')).toBe(true);
  });

  it('marks a cut list of administrator actions', () => {
    expect(summaryCards(summary, [], true, zoneTime)[3].detail).toContain('newest only');
  });

  it('sets the filters a click on each card means', () => {
    const cards = summaryCards(summary, [], false, zoneTime);
    expect(cards.map(card => card.filters)).toEqual([
      { category: 'signin' },
      { category: 'signin', result: 'failure' },
      { category: 'account' },
      { category: 'admin' },
    ]);
  });

  it('copes with no summary at all', () => {
    expect(summaryCards(null, [], false, zoneTime).map(card => card.value)).toEqual([null, null, null, 0]);
  });
});

describe('needs attention', () => {
  it('lists high-risk events and administrator actions, newest first', () => {
    const list = attentionEvents(summary, 'u1');
    expect(list.map(item => item.kind)).toEqual(['admin.user-password.action:reset', 'token.reuse_detected']);
    expect(list[0].targetId).toBe('u1');
    expect(list[0].actorType).toBe('admin');
  });

  it('is empty without a summary', () => {
    expect(attentionEvents(null, 'u1')).toEqual([]);
  });

  it('gives an administrator action an id that is stable for the panel', () => {
    const action = summary.adminActions[0];
    expect(adminActionEvent(action, 'u1').id).toBe(adminActionEvent(action, 'u1').id);
  });
});

describe('filtering the loaded page', () => {
  const events = [
    event({ id: 'a', kind: 'login.completed', clientId: 'web-portal', traceId: 't1' }),
    event({ id: 'b', kind: 'token.refused', outcome: 'failure', clientId: 'mobile-app', ip: '203.0.113.44', country: 'NL', reason: 'invalid_grant', traceId: 't2' }),
    event({ id: 'c', kind: 'token.reuse_detected', outcome: 'failure', severity: 'high', clientId: 'mobile-app', ip: '203.0.113.44', country: 'NL', traceId: 't2' }),
    event({ id: 'd', kind: 'admin.user-password.action:reset', actorType: 'admin', actorId: 'm.omarov', clientId: undefined, ip: '10.0.4.12', country: undefined }),
  ];
  const ids = (filters: Partial<typeof NO_FILTERS>) => filterEvents(events, { ...NO_FILTERS, ...filters }, code => (code === 'NL' ? 'Netherlands' : code)).map(item => item.id);

  it('keeps everything without filters', () => {
    expect(ids({})).toEqual(['a', 'b', 'c', 'd']);
    expect(hasActiveFilters(NO_FILTERS)).toBe(false);
  });

  it('filters by category, result and client', () => {
    expect(ids({ category: 'tokens' })).toEqual(['b', 'c']);
    expect(ids({ result: 'failure' })).toEqual(['b', 'c']);
    expect(ids({ result: 'high' })).toEqual(['c']);
    expect(ids({ result: 'success' })).toEqual(['a', 'd']);
    expect(ids({ client: 'mobile-app' })).toEqual(['b', 'c']);
  });

  it('filters by the request an event belongs to', () => {
    expect(ids({ trace: 't2' })).toEqual(['b', 'c']);
    expect(ids({ trace: 'nope' })).toEqual([]);
  });

  it('searches the event, the client, the address and the country name, ignoring case', () => {
    expect(ids({ q: 'REFUSED' })).toEqual(['b']);
    expect(ids({ q: 'web-portal' })).toEqual(['a']);
    expect(ids({ q: '203.0.113' })).toEqual(['b', 'c']);
    expect(ids({ q: 'netherlands' })).toEqual(['b', 'c']);
    expect(ids({ q: 'omarov' })).toEqual(['d']);
  });

  it('combines the filters', () => {
    expect(ids({ category: 'tokens', result: 'high', q: '203' })).toEqual(['c']);
    expect(ids({ category: 'signin', client: 'mobile-app' })).toEqual([]);
  });

  it('draws a chip for each filter that is not a pill, and shortens a request id', () => {
    expect(filterChips({ ...NO_FILTERS, category: 'tokens' })).toEqual([]);
    expect(filterChips({ q: ' 203.0.113.44 ', result: 'failure', client: 'web-portal', category: '', trace: '4bf92f3577b34da6a3ce929d0e0e4736' })).toEqual([
      { key: 'result', label: 'Result: Failed' },
      { key: 'client', label: 'Client: web-portal' },
      { key: 'q', label: 'Search: 203.0.113.44' },
      { key: 'trace', label: 'Request: 4bf92f35…' },
    ]);
  });

  it('lists the clients of the page, sorted, with the selected one even when the page has none', () => {
    expect(clientsOf(events)).toEqual(['mobile-app', 'web-portal']);
    expect(clientsOf([], 'old-client')).toEqual(['old-client']);
  });
});

describe('the cursor stack of the pager', () => {
  it('starts on page 1 with no cursor', () => {
    expect(currentCursor([])).toBeUndefined();
    expect(pageOf([])).toBe(1);
  });

  it('goes older by pushing the next cursor and newer by popping it', () => {
    const second = pushCursor([], 'c1');
    const third = pushCursor(second, 'c2');
    expect(currentCursor(third)).toBe('c2');
    expect(pageOf(third)).toBe(3);
    expect(popCursor(third)).toEqual(['c1']);
    expect(currentCursor(popCursor(third))).toBe('c1');
    expect(popCursor(popCursor(third))).toEqual([]);
  });

  it('does not mutate the stack it is given, and stays on page 1 when popped at page 1', () => {
    const stack = ['c1'] as const;
    pushCursor(stack, 'c2');
    expect(stack).toEqual(['c1']);
    expect(popCursor([])).toEqual([]);
  });

  it('numbers the events of the page in the whole walk', () => {
    expect(pageRangeLabel([], 100, 100)).toBe('Events 1–100');
    expect(pageRangeLabel(['c1'], 100, 37)).toBe('Events 101–137');
    expect(pageRangeLabel(['c1', 'c2'], 25, 25)).toBe('Events 51–75');
    expect(pageRangeLabel([], 100, 0)).toBe('No events');
  });
});

describe('the view in the URL', () => {
  const now = new Date('2026-11-04T09:50:00Z');

  it('opens on 7 days ending now when the URL says nothing', () => {
    const view = parseActivityParams({}, now);
    expect(view).toMatchObject({ range: '7d', from: '2026-10-28T09:50:00Z', to: '2026-11-04T09:50:00Z', limit: 100, cursors: [], event: '' });
    expect(view.filters).toEqual(NO_FILTERS);
    expect(view).toEqual(defaultView(now));
  });

  it('computes a preset window from now when the link carries no instants', () => {
    expect(parseActivityParams({ range: '24h' }, now)).toMatchObject({ range: '24h', from: '2026-11-03T09:50:00Z', to: '2026-11-04T09:50:00Z' });
    expect(presetWindow('24h', now).from).toBe('2026-11-03T09:50:00Z');
  });

  it('keeps the window of the link so its cursors stay valid', () => {
    const view = parseActivityParams({ range: '7d', from: '2026-10-01T00:00:00Z', to: '2026-10-08T00:00:00Z', cursors: 'a%252Fb,c' }, now);
    expect(view).toMatchObject({ from: '2026-10-01T00:00:00Z', to: '2026-10-08T00:00:00Z' });
    expect(view.cursors).toEqual(['a%2Fb', 'c']);
  });

  it('falls back to 7 days for a custom range that is not valid', () => {
    expect(parseActivityParams({ range: 'custom', from: 'nope', to: '2026-11-04T00:00:00Z' }, now).range).toBe('7d');
    expect(parseActivityParams({ range: 'custom', from: '2026-11-05T00:00:00Z', to: '2026-11-04T00:00:00Z' }, now).range).toBe('7d');
  });

  it('reads filters, page size and the open event, and ignores values it does not know', () => {
    const view = parseActivityParams({ q: 'x', result: 'failure', client: 'web-portal', cat: 'tokens', trace: 't', limit: '50', event: 'e1' }, now);
    expect(view.filters).toEqual({ q: 'x', result: 'failure', client: 'web-portal', category: 'tokens', trace: 't' });
    expect(view.limit).toBe(50);
    expect(view.event).toBe('e1');
    const bad = parseActivityParams({ result: 'weird', cat: 'weird', limit: '7' }, now);
    expect(bad.filters.result).toBe('');
    expect(bad.filters.category).toBe('');
    expect(bad.limit).toBe(100);
  });

  it('round-trips a view, including its cursors, through the parameters', () => {
    const view = {
      range: 'custom' as const, from: '2026-11-01T00:00:00Z', to: '2026-11-04T12:00:00Z',
      filters: { q: '203.0.113.44', result: 'failure' as const, client: 'mobile-app', category: 'tokens' as const, trace: 'abc' },
      limit: 25 as const, cursors: ['AAA/+=', 'b,c'], event: 'e9',
    };
    expect(parseActivityParams(serializeActivityParams(view), now)).toEqual(view);
  });

  it('leaves the defaults out of a link', () => {
    expect(serializeActivityParams(defaultView(now))).toEqual({ range: '7d', from: '2026-10-28T09:50:00Z', to: '2026-11-04T09:50:00Z' });
  });
});
