/**
 * The per-user audit report (`GET /users/audit`, central) as the Activity screen reads it: the typed
 * response, what the summary cards derive from it, how an event is worded, categorised and filtered,
 * the cursor stack of the pager and the part of the URL that names a view. No DOM and no fetching
 * here; `users-api.ts` fetches, `user-activity.ts` draws.
 */

export type AuditOutcome = 'success' | 'failure' | 'denied';
export type AuditSeverity = 'info' | 'notice' | 'high';

/** One row of an audit log as auth and central write it, plus the country of its address when auth knew one. */
export type AuditEvent = {
  id: string;
  at: string;
  tenantId?: string;
  source: string;
  kind: string;
  outcome: AuditOutcome;
  reason?: string;
  severity: AuditSeverity;
  actorType: string;
  actorId?: string;
  clientId?: string;
  sessionId?: string;
  ip?: string;
  userAgent?: string;
  traceId?: string;
  targetType?: string;
  targetId?: string;
  details: Record<string, unknown>;
  /** Upper-case ISO 3166-1 alpha-2; absent when the deployment has no geo database or the address is private. */
  country?: string;
};

/** What an administrator did to the user, one line of the summary. */
export type AdminAction = {
  at: string;
  actorId?: string;
  kind: string;
  targetType?: string;
  targetId?: string;
  changed: string[];
};

export type AddressSeen = { ip: string; firstSeen: string; lastSeen: string; count: number; country?: string };
export type UserAgentSeen = { userAgent: string; firstSeen: string; lastSeen: string; count: number };
export type CountrySeen = { country: string; firstSeen: string; lastSeen: string; count: number };

/** Auth's summary of the window with central's `adminActions`. Without auth's part (`auth` false) only `adminActions` is real. */
export type AuditSummary = {
  auth: boolean;
  byKind: { kind: string; outcome: string; count: number }[];
  byClient: { clientId: string; logins: number; tokens: number }[];
  ips: AddressSeen[];
  userAgents: UserAgentSeen[];
  sessions: { created: number; ended: number };
  countries: CountrySeen[];
  /** Password and passkey changes, newest first. */
  factors: { kind: string; at: string }[];
  high: AuditEvent[];
  adminActions: AdminAction[];
};

export type UserAuditReport = {
  userId: string;
  window: { from: string; to: string; retentionFrom: string };
  /** First page only. */
  summary: AuditSummary | null;
  events: AuditEvent[];
  nextCursor: string | null;
  /** `['auth']` when auth did not answer. */
  partial: string[];
  /** The administrators' changes in the summary were cut at their bound. */
  truncated: boolean;
};

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const text = (value: unknown): string | undefined => (typeof value === 'string' && value !== '' ? value : undefined);
const count = (value: unknown): number => (typeof value === 'number' && Number.isFinite(value) ? value : 0);
const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);

function required(value: unknown, what: string): string {
  const found = text(value);
  if (!found) throw new Error(`The audit report is malformed: ${what} is missing.`);
  return found;
}

export function parseAuditEvent(value: unknown): AuditEvent {
  if (!isRecord(value)) throw new Error('The audit report is malformed: an event is not an object.');
  const outcome = value.outcome === 'failure' || value.outcome === 'denied' ? value.outcome : 'success';
  const severity = value.severity === 'high' || value.severity === 'notice' ? value.severity : 'info';
  return {
    id: required(value.id, 'an event id'),
    at: required(value.at, 'an event time'),
    tenantId: text(value.tenantId),
    source: text(value.source) ?? 'auth',
    kind: required(value.kind, 'an event kind'),
    outcome,
    reason: text(value.reason),
    severity,
    actorType: text(value.actorType) ?? 'system',
    actorId: text(value.actorId),
    clientId: text(value.clientId),
    sessionId: text(value.sessionId),
    ip: text(value.ip),
    userAgent: text(value.userAgent),
    traceId: text(value.traceId),
    targetType: text(value.targetType),
    targetId: text(value.targetId),
    details: isRecord(value.details) ? value.details : {},
    country: text(value.country),
  };
}

function parseAdminAction(value: unknown): AdminAction {
  const action = isRecord(value) ? value : {};
  return {
    at: required(action.at, 'an administrator action time'),
    actorId: text(action.actorId),
    kind: required(action.kind, 'an administrator action kind'),
    targetType: text(action.targetType),
    targetId: text(action.targetId),
    changed: list(action.changed).filter((name): name is string => typeof name === 'string'),
  };
}

function parseSummary(value: unknown): AuditSummary | null {
  if (!isRecord(value)) return null;
  const sessions = isRecord(value.sessions) ? value.sessions : {};
  return {
    auth: Array.isArray(value.byKind),
    byKind: list(value.byKind).filter(isRecord).map(entry => ({ kind: text(entry.kind) ?? '', outcome: text(entry.outcome) ?? '', count: count(entry.count) })),
    byClient: list(value.byClient).filter(isRecord).map(entry => ({ clientId: text(entry.clientId) ?? '', logins: count(entry.logins), tokens: count(entry.tokens) })),
    ips: list(value.ips).filter(isRecord).map(entry => ({
      ip: text(entry.ip) ?? '',
      firstSeen: text(entry.firstSeen) ?? '',
      lastSeen: text(entry.lastSeen) ?? '',
      count: count(entry.count),
      country: text(entry.country),
    })),
    userAgents: list(value.userAgents).filter(isRecord).map(entry => ({
      userAgent: text(entry.userAgent) ?? '',
      firstSeen: text(entry.firstSeen) ?? '',
      lastSeen: text(entry.lastSeen) ?? '',
      count: count(entry.count),
    })),
    sessions: { created: count(sessions.created), ended: count(sessions.ended) },
    countries: list(value.countries).filter(isRecord).map(entry => ({
      country: text(entry.country) ?? '',
      firstSeen: text(entry.firstSeen) ?? '',
      lastSeen: text(entry.lastSeen) ?? '',
      count: count(entry.count),
    })),
    factors: list(value.factors).filter(isRecord).map(entry => ({ kind: text(entry.kind) ?? '', at: text(entry.at) ?? '' })),
    high: list(value.high).map(parseAuditEvent),
    adminActions: list(value.adminActions).map(parseAdminAction),
  };
}

/** Reads the response of `GET /users/audit`; throws a plain Error when its shape is wrong. */
export function parseUserAuditReport(value: unknown): UserAuditReport {
  if (!isRecord(value) || !Array.isArray(value.events) || !isRecord(value.window)) {
    throw new Error('The audit report is malformed.');
  }
  return {
    userId: text(value.userId) ?? '',
    window: {
      from: required(value.window.from, 'the window start'),
      to: required(value.window.to, 'the window end'),
      retentionFrom: required(value.window.retentionFrom, 'the start of retention'),
    },
    summary: parseSummary(value.summary),
    events: value.events.map(parseAuditEvent),
    nextCursor: text(value.nextCursor) ?? null,
    partial: list(value.partial).filter((name): name is string => typeof name === 'string'),
    truncated: value.truncated === true,
  };
}

// ── words ───────────────────────────────────────────────────────────────

export type EventCategory = 'signin' | 'tokens' | 'account' | 'admin';

export const CATEGORY_LABELS: Record<EventCategory, string> = {
  signin: 'Sign-in',
  tokens: 'Tokens',
  account: 'Account',
  admin: 'Admin',
};

/** Which pill an event belongs to: what an administrator did (central's `admin.*` rows, or an auth event an
 *  administrator caused) is Admin; the rest by the area the kind names. */
export function categoryOf(event: Pick<AuditEvent, 'kind' | 'actorType'>): EventCategory {
  const { kind } = event;
  if (kind.startsWith('admin.') || event.actorType === 'admin') return 'admin';
  if (kind.startsWith('token.') || kind.startsWith('client.')) return 'tokens';
  if (kind.startsWith('account.') || kind.startsWith('cert.') || kind.startsWith('antifraud.')) return 'account';
  return 'signin';
}

const asText = (value: unknown): string | undefined => (typeof value === 'string' && value !== '' ? value : undefined);
const asList = (value: unknown): string[] => (Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []);
const humanize = (value: string) => value.replace(/[_-]+/g, ' ');
const sentence = (value: string) => (value ? value[0].toUpperCase() + value.slice(1) : value);

const GRANTS: Record<string, string> = {
  authorization_code: 'authorization code',
  refresh_token: 'refresh token',
  client_credentials: 'client credentials',
  'urn:ietf:params:oauth:grant-type:device_code': 'device code',
};

const SESSION_CAUSES: Record<string, string> = {
  rp_logout: 'sign out',
  user_revoked: 'signed out by the user',
  admin_revoked: 'signed out by an administrator',
  user_agent_forgotten: 'device forgotten',
  user_deleted: 'user deleted',
};

const STEP_LABELS: Record<string, string> = { password: 'Password', otp: 'Code', passkey: 'Passkey', email: 'Email', sms: 'SMS' };
const STEP_KINDS: Record<string, string> = { login: 'sign-in', step_up: 'step-up', reauth: 're-authentication' };

const ACCOUNT_TITLES: Record<string, string> = {
  'account.registered': 'Account registered',
  'account.password_changed': 'Password changed',
  'account.password_set': 'Password set',
  'account.password_reset': 'Password reset',
  'account.passkey_enrolled': 'Passkey added',
  'account.passkey_removed': 'Passkey removed',
  'account.claims_updated': 'Profile updated',
  'account.limits_reset': 'Sign-in limits reset',
  'account.deleted': 'Account deleted',
  'account.updated': 'Identifiers updated',
  'account.roles_updated': 'Roles updated',
};

const ADMIN_TITLES: Record<string, string> = {
  'admin.user.viewed': 'User viewed by an admin',
  'admin.audit.queried': 'Activity report opened',
  'admin.secret.revealed': 'Secret revealed by an admin',
  'admin.user-password.action:reset': 'Password reset by an admin',
};

const PAST: Record<string, string> = { create: 'created', update: 'updated', delete: 'deleted' };

function adminTitle(kind: string): string {
  if (ADMIN_TITLES[kind]) return ADMIN_TITLES[kind];
  const [, resource = '', ...rest] = kind.split('.');
  const operation = rest.join('.');
  const noun = sentence(humanize(resource));
  if (PAST[operation]) return `${noun} ${PAST[operation]} by an admin`;
  if (operation.startsWith('action:')) return `${noun}: ${humanize(operation.slice('action:'.length))}`;
  return `${noun}${operation ? ` ${humanize(operation)}` : ''} by an admin`;
}

/** The title of an event and the one line under it. */
export function describeEvent(event: Pick<AuditEvent, 'kind' | 'outcome' | 'reason' | 'actorId' | 'details'>): { title: string; subtitle: string } {
  const { kind, outcome, reason, details } = event;
  const failed = outcome !== 'success';
  const subtitle = (...parts: (string | undefined)[]) => parts.filter(Boolean).join(' · ');
  switch (kind) {
    case 'login.step': {
      const step = asText(details.step) ?? '';
      const label = STEP_LABELS[step] ?? (step ? sentence(humanize(step)) : 'Sign-in');
      const verdict = outcome === 'success' ? 'passed' : outcome === 'denied' ? 'denied' : 'failed';
      return { title: `${label} check ${verdict}`, subtitle: subtitle(reason, STEP_KINDS[asText(details.kind) ?? ''] ?? undefined) };
    }
    case 'login.completed':
      return { title: failed ? 'Sign-in failed' : 'Signed in', subtitle: subtitle(asList(details.amr).join(', ') || undefined, reason) };
    case 'authorize.request':
      return { title: failed ? 'Authorization refused' : 'Authorization requested', subtitle: subtitle(asList(details.scope).join(' ') || undefined, reason) };
    case 'par.request':
      return { title: failed ? 'Pushed request refused' : 'Pushed authorization request', subtitle: subtitle(asList(details.scope).join(' ') || undefined, reason) };
    case 'consent.granted':
    case 'consent.denied':
    case 'consent.revoked':
      return { title: `Consent ${kind.slice('consent.'.length)}`, subtitle: asList(details.scope).join(' ') };
    case 'token.issued': {
      const grant = asText(details.grant) ?? '';
      return { title: 'Token issued', subtitle: GRANTS[grant] ?? humanize(grant) };
    }
    case 'token.refused':
      return { title: 'Token refused', subtitle: subtitle(reason, asText(details.detail)) };
    case 'token.reuse_detected': {
      const refresh = asText(details.tokenType) !== 'authorization_code' && asText(details.tokenType) !== 'code';
      return { title: refresh ? 'Refresh token reused' : 'Authorization code reused', subtitle: 'token family revoked' };
    }
    case 'token.revoked':
      return { title: 'Token revoked', subtitle: humanize(asText(details.cause) ?? '') };
    case 'client.auth_failed':
      return { title: 'Client authentication failed', subtitle: subtitle(asText(details.method), reason) };
    case 'session.ended':
      return { title: 'Session ended', subtitle: SESSION_CAUSES[asText(details.cause) ?? ''] ?? humanize(asText(details.cause) ?? '') };
    default:
  }
  if (ACCOUNT_TITLES[kind]) {
    return { title: ACCOUNT_TITLES[kind], subtitle: subtitle(asText(details.via) ? `via ${humanize(asText(details.via)!)}` : undefined) };
  }
  if (kind.startsWith('admin.')) {
    const changed = asList(details.changed);
    return { title: adminTitle(kind), subtitle: subtitle(event.actorId ? `by ${event.actorId}` : undefined, changed.length ? `changed ${changed.join(', ')}` : undefined) };
  }
  return { title: kind, subtitle: reason ?? '' };
}

export type ResultTone = 'ok' | 'warn' | 'danger' | 'info';

/** The Result badge: its text carries the meaning, the tone only colours it. High risk wins over the outcome. */
export function resultOf(event: Pick<AuditEvent, 'kind' | 'outcome' | 'severity' | 'actorType'>): { label: string; tone: ResultTone } {
  if (event.severity === 'high') return { label: 'High risk', tone: 'danger' };
  if (event.outcome === 'failure') return { label: 'Failed', tone: 'warn' };
  if (event.outcome === 'denied') return { label: 'Denied', tone: 'warn' };
  if (event.kind.startsWith('account.') || event.kind.startsWith('admin.') || event.actorType === 'admin') return { label: 'Account change', tone: 'info' };
  return { label: 'Succeeded', tone: 'ok' };
}

/** What `outcome` and `severity` say in a sentence, for the panel. */
export function outcomeSentence(event: Pick<AuditEvent, 'kind' | 'outcome' | 'severity'>): string {
  if (event.kind === 'token.reuse_detected') return 'Refused, the whole token family was revoked';
  if (event.severity === 'high') return 'High risk';
  if (event.outcome === 'failure') return 'Failed';
  if (event.outcome === 'denied') return 'Denied';
  return 'Succeeded';
}

/** An administrator action of the summary as an event, for the panel and for the attention list; it has no request. */
export function adminActionEvent(action: AdminAction, userId: string): AuditEvent {
  return {
    id: `admin-action:${action.at}:${action.kind}`,
    at: action.at,
    source: 'central',
    kind: action.kind,
    outcome: 'success',
    severity: 'notice',
    actorType: 'admin',
    actorId: action.actorId,
    targetType: action.targetType ?? 'user',
    targetId: action.targetId ?? userId,
    details: action.changed.length ? { changed: action.changed } : {},
  };
}

// ── user agents ─────────────────────────────────────────────────────────

/** `Firefox 132 · Windows`, `Chrome 131 · macOS`; an app's own string (`ExampleShop/4.2 (iPhone; iOS 18.1)`) as
 *  `ExampleShop 4.2 · iOS`; the string itself, cut, when nothing is recognised. */
export function describeUserAgent(userAgent: string | null | undefined): string {
  if (!userAgent) return '';
  const os = /Windows NT/.test(userAgent) ? 'Windows'
    : /iPhone|iPad|iPod|iOS/.test(userAgent) ? 'iOS'
    : /Android/.test(userAgent) ? 'Android'
    : /Mac OS X|Macintosh/.test(userAgent) ? 'macOS'
    : /Linux|X11/.test(userAgent) ? 'Linux'
    : '';
  const major = (match: RegExpExecArray | null) => (match ? match[1].split('.')[0] : '');
  const named = (name: string, match: RegExpExecArray | null) => [name, major(match)].filter(Boolean).join(' ');
  let browser = '';
  let found: RegExpExecArray | null;
  if ((found = /Edg(?:e|A|iOS)?\/(\d[\d.]*)/.exec(userAgent))) browser = named('Edge', found);
  else if ((found = /OPR\/(\d[\d.]*)/.exec(userAgent))) browser = named('Opera', found);
  else if ((found = /(?:Firefox|FxiOS)\/(\d[\d.]*)/.exec(userAgent))) browser = named('Firefox', found);
  else if ((found = /(?:Chrome|CriOS)\/(\d[\d.]*)/.exec(userAgent))) browser = named('Chrome', found);
  else if (/Safari\//.test(userAgent) && (found = /Version\/(\d[\d.]*)/.exec(userAgent))) browser = named('Safari', found);
  else if ((found = /^([A-Za-z][\w .-]*?)\/(\d[\w.]*)/.exec(userAgent))) browser = `${found[1]} ${found[2]}`;
  if (!browser && !os) return userAgent.length > 60 ? `${userAgent.slice(0, 57)}…` : userAgent;
  return [browser, os].filter(Boolean).join(' · ');
}

// ── summary cards ───────────────────────────────────────────────────────

export type Filters = {
  q: string;
  result: '' | AuditOutcome | 'high';
  client: string;
  category: '' | EventCategory;
  /** The request (trace) id of an event whose request is shown. */
  trace: string;
};

export const NO_FILTERS: Filters = { q: '', result: '', client: '', category: '', trace: '' };

export type SummaryCard = {
  id: 'signins' | 'failed' | 'credentials' | 'admin';
  label: string;
  /** Null when the part that holds it did not answer. */
  value: number | null;
  detail: string;
  /** The filters a click on the card sets (the others are cleared). */
  filters: Partial<Filters>;
};

const LOGIN_KINDS = new Set(['login.step', 'login.completed']);

/** The cards of the summary, derived like this:
 *  - failed sign-ins: `byKind` entries of `login.step` and `login.completed` whose outcome is not `success`;
 *  - sign-ins: those failures plus the sign-ins that created a session (`sessions.created`), with "N succeeded" under it;
 *  - credential changes: the `factors` list (password and passkey changes), named by kind;
 *  - admin actions: `adminActions` (central's own rows; "100+" when the list was cut).
 *  `events` are the loaded first page: the time of the last failure and of the last administrator action come from there
 *  or from `adminActions`, and are left out when the page holds none. */
export function summaryCards(summary: AuditSummary | null, events: AuditEvent[], truncated: boolean, zoneTime: (at: string) => string): SummaryCard[] {
  const authenticated = summary?.auth === true;
  const failed = authenticated
    ? summary!.byKind.filter(entry => LOGIN_KINDS.has(entry.kind) && entry.outcome !== 'success').reduce((sum, entry) => sum + entry.count, 0)
    : null;
  const succeeded = authenticated ? summary!.sessions.created : null;
  const lastFailure = events.find(event => LOGIN_KINDS.has(event.kind) && event.outcome !== 'success');
  const factors = authenticated ? summary!.factors : [];
  const factorNames = [...new Set(factors.map(factor => (factor.kind.startsWith('account.passkey_') ? 'passkey' : 'password')))];
  const actions = summary?.adminActions ?? [];
  const latestAction = actions[0];
  return [
    {
      id: 'signins',
      label: 'Sign-ins',
      value: failed === null || succeeded === null ? null : failed + succeeded,
      detail: succeeded === null ? 'unavailable' : `${succeeded} succeeded`,
      filters: { category: 'signin' },
    },
    {
      id: 'failed',
      label: 'Failed sign-ins',
      value: failed,
      detail: failed === null ? 'unavailable' : failed === 0 ? 'none in this period' : lastFailure ? `last ${zoneTime(lastFailure.at)}` : 'in this period',
      filters: { category: 'signin', result: 'failure' },
    },
    {
      id: 'credentials',
      label: 'Credential changes',
      value: authenticated ? factors.length : null,
      detail: !authenticated ? 'unavailable' : factorNames.length ? factorNames.join(', ') : 'none in this period',
      filters: { category: 'account' },
    },
    {
      id: 'admin',
      label: 'Admin actions on this user',
      value: actions.length,
      detail: latestAction
        ? `${describeEvent(adminActionEvent(latestAction, '')).title.replace(/ by an admin$/, '').toLowerCase()} · ${zoneTime(latestAction.at)}${truncated ? ' · newest only' : ''}`
        : 'none in this period',
      filters: { category: 'admin' },
    },
  ];
}

/** The events that need a look, newest first: what auth rated high risk and what administrators did to the user. */
export function attentionEvents(summary: AuditSummary | null, userId: string): AuditEvent[] {
  if (!summary) return [];
  const found = [...summary.high, ...summary.adminActions.map(action => adminActionEvent(action, userId))];
  return found.sort((a, b) => Date.parse(b.at) - Date.parse(a.at));
}

// ── filtering ───────────────────────────────────────────────────────────

/** The text a search looks in: what the row shows (title, subtitle, kind, client, address, country, browser, actor). */
function searchable(event: AuditEvent, countryName: (code: string) => string): string {
  const { title, subtitle } = describeEvent(event);
  return [title, subtitle, event.kind, event.clientId, event.ip, event.country, event.country ? countryName(event.country) : '', describeUserAgent(event.userAgent), event.actorId, event.reason]
    .filter(Boolean)
    .join('\n')
    .toLowerCase();
}

export function filterEvents(events: AuditEvent[], filters: Filters, countryName: (code: string) => string = code => code): AuditEvent[] {
  const query = filters.q.trim().toLowerCase();
  return events.filter(event => {
    if (filters.category && categoryOf(event) !== filters.category) return false;
    if (filters.result === 'high' ? event.severity !== 'high' : filters.result && event.outcome !== filters.result) return false;
    if (filters.client && event.clientId !== filters.client) return false;
    if (filters.trace && event.traceId !== filters.trace) return false;
    return !query || searchable(event, countryName).includes(query);
  });
}

export function hasActiveFilters(filters: Filters): boolean {
  return Boolean(filters.q.trim() || filters.result || filters.client || filters.category || filters.trace);
}

export const RESULT_LABELS: Record<Exclude<Filters['result'], ''>, string> = {
  success: 'Succeeded',
  failure: 'Failed',
  denied: 'Denied',
  high: 'High risk',
};

/** The chips under the filter bar, one per filter that is not already visible as a pill. */
export function filterChips(filters: Filters): { key: 'q' | 'result' | 'client' | 'trace'; label: string }[] {
  const chips: { key: 'q' | 'result' | 'client' | 'trace'; label: string }[] = [];
  if (filters.result) chips.push({ key: 'result', label: `Result: ${RESULT_LABELS[filters.result]}` });
  if (filters.client) chips.push({ key: 'client', label: `Client: ${filters.client}` });
  if (filters.q.trim()) chips.push({ key: 'q', label: `Search: ${filters.q.trim()}` });
  if (filters.trace) chips.push({ key: 'trace', label: `Request: ${filters.trace.length > 12 ? `${filters.trace.slice(0, 8)}…` : filters.trace}` });
  return chips;
}

/** The clients named by the loaded events, sorted, for the Client select. */
export function clientsOf(events: AuditEvent[], selected = ''): string[] {
  const names = new Set(events.map(event => event.clientId).filter((id): id is string => Boolean(id)));
  if (selected) names.add(selected);
  return [...names].sort((a, b) => a.localeCompare(b));
}

// ── paging ──────────────────────────────────────────────────────────────

export const PAGE_SIZES = [25, 50, 100] as const;
export type PageSize = (typeof PAGE_SIZES)[number];
export const DEFAULT_PAGE_SIZE: PageSize = 100;

/** The cursors that led to the page on screen: none for page 1, the cursor each page's `nextCursor` gave for the later ones. */
export type CursorStack = readonly string[];

export const currentCursor = (stack: CursorStack): string | undefined => stack[stack.length - 1];
export const pageOf = (stack: CursorStack): number => stack.length + 1;
/** Older: the next page, reached with the `nextCursor` the page on screen gave. */
export const pushCursor = (stack: CursorStack, nextCursor: string): CursorStack => [...stack, nextCursor];
/** Newer: the page before, whose cursor is the one under the top. */
export const popCursor = (stack: CursorStack): CursorStack => stack.slice(0, -1);

/** `Events 101–150`, the position of the page on screen in the walk; `No events` for an empty page. */
export function pageRangeLabel(stack: CursorStack, limit: number, loaded: number): string {
  if (loaded === 0) return 'No events';
  const first = stack.length * limit + 1;
  return `Events ${first}–${first + loaded - 1}`;
}

// ── the view in the URL ─────────────────────────────────────────────────

export type RangeKind = '24h' | '7d' | 'custom';

export type ActivityView = {
  range: RangeKind;
  /** The window asked for, ISO instants: fixed for the whole walk so the cursors stay valid. */
  from: string;
  to: string;
  filters: Filters;
  limit: PageSize;
  cursors: CursorStack;
  /** The event open in the panel. */
  event: string;
};

const HOUR_MS = 3_600_000;
export const RANGE_LENGTHS: Record<Exclude<RangeKind, 'custom'>, number> = { '24h': 24 * HOUR_MS, '7d': 7 * 24 * HOUR_MS };

/** ISO instant to the second: what the URL and the API carry. */
export const isoSecond = (date: Date): string => date.toISOString().replace(/\.\d{3}Z$/, 'Z');

export function presetWindow(range: Exclude<RangeKind, 'custom'>, now: Date): { from: string; to: string } {
  return { from: isoSecond(new Date(now.getTime() - RANGE_LENGTHS[range])), to: isoSecond(now) };
}

export function defaultView(now: Date): ActivityView {
  return { range: '7d', ...presetWindow('7d', now), filters: NO_FILTERS, limit: DEFAULT_PAGE_SIZE, cursors: [], event: '' };
}

const validInstant = (value: string | undefined): value is string => Boolean(value) && !Number.isNaN(Date.parse(value!));
const CATEGORIES = Object.keys(CATEGORY_LABELS) as EventCategory[];

/** Reads the view out of the page's URL parameters. Anything missing or not valid falls back to the default (7 days
 *  ending now); a custom range needs both instants and `from` before `to`. */
export function parseActivityParams(params: Record<string, string>, now: Date): ActivityView {
  const fallback = defaultView(now);
  const range: RangeKind = params.range === '24h' || params.range === 'custom' ? params.range : '7d';
  let from = fallback.from;
  let to = fallback.to;
  let kind = range;
  if (validInstant(params.from) && validInstant(params.to) && Date.parse(params.from) <= Date.parse(params.to)) {
    from = params.from;
    to = params.to;
  } else if (range !== 'custom') {
    ({ from, to } = presetWindow(range, now));
  } else {
    kind = '7d';
  }
  const result = params.result === 'success' || params.result === 'failure' || params.result === 'denied' || params.result === 'high' ? params.result : '';
  const category = CATEGORIES.find(name => name === params.cat) ?? '';
  const limit = PAGE_SIZES.find(size => String(size) === params.limit) ?? DEFAULT_PAGE_SIZE;
  const cursors = params.cursors ? params.cursors.split(',').map(decodeURIComponent).filter(Boolean) : [];
  return {
    range: kind,
    from,
    to,
    filters: { q: params.q ?? '', result, client: params.client ?? '', category, trace: params.trace ?? '' },
    limit,
    cursors,
    event: params.event ?? '',
  };
}

/** The URL parameters of a view; what is the default is left out so a link stays short. */
export function serializeActivityParams(view: ActivityView): Record<string, string> {
  const params: Record<string, string> = { range: view.range, from: view.from, to: view.to };
  const { filters } = view;
  if (filters.q.trim()) params.q = filters.q.trim();
  if (filters.result) params.result = filters.result;
  if (filters.client) params.client = filters.client;
  if (filters.category) params.cat = filters.category;
  if (filters.trace) params.trace = filters.trace;
  if (view.limit !== DEFAULT_PAGE_SIZE) params.limit = String(view.limit);
  if (view.cursors.length) params.cursors = view.cursors.map(encodeURIComponent).join(',');
  if (view.event) params.event = view.event;
  return params;
}
