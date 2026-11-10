import type { Edge, Partner, PartnerApproval, PartnerApprovalKind, PartnerLimits, PartnerStatus } from '../types';
import type { ClientKind, PlanLine } from './client-presets';
import { MIN_DPOP_BOUND_ACCESS_TOKEN_TTL_SECONDS, validateAccessTokenTtl } from './validators';

/**
 * What both consoles know about a partner (#549): the limits the operator sets, the rules a
 * partner client is held to, and the words the screens use for them. Nothing here talks to an API,
 * so the partner console may import it.
 */

/** A partner ID is a slug, validated like a scope ID: no `.`, no `:`. */
export function validatePartnerId(id: string): boolean {
  return /^[a-z0-9][a-z0-9-]{1,62}$/.test(id);
}

/** Whether a redirect URI is https and under one of the partner's domains (the domain itself or a subdomain). */
export function redirectUriWithinDomains(uri: string, domains: string[]): boolean {
  try {
    const url = new URL(uri);
    return url.protocol === 'https:' && domains.some(domain => url.hostname === domain || url.hostname.endsWith(`.${domain}`));
  } catch {
    return false;
  }
}

export type PartnerClientDraft = { redirectUris: string[]; scope: string[]; accessTokenTtl?: number; refreshTokenTtl?: number };

/** The fields of a client draft that fall outside the partner's limits: what central refuses, or what waits for the operator. */
export function partnerLimitViolations(limits: PartnerLimits, draft: PartnerClientDraft): string[] {
  const outside: string[] = [];
  if (draft.redirectUris.some(uri => !redirectUriWithinDomains(uri, limits.redirectDomains))) outside.push('redirectUris');
  if (draft.scope.some(scope => !limits.scopes.includes(scope))) outside.push('scope');
  if (draft.accessTokenTtl !== undefined && draft.accessTokenTtl > limits.accessTokenTtlMax) outside.push('accessTokenTtl');
  if (draft.refreshTokenTtl !== undefined && draft.refreshTokenTtl > limits.refreshTokenTtlMax) outside.push('refreshTokenTtl');
  return outside;
}

/** `5 min`, `12 h`, `30 days`: a token lifetime in the unit it was most likely set in. */
export function ttlLabel(seconds: number): string {
  if (seconds % 86_400 === 0 && seconds >= 86_400) {
    const days = seconds / 86_400;
    return `${days} ${days === 1 ? 'day' : 'days'}`;
  }
  if (seconds % 3_600 === 0 && seconds >= 3_600) return `${seconds / 3_600} h`;
  if (seconds % 60 === 0 && seconds >= 60) return `${seconds / 60} min`;
  return `${seconds} s`;
}

/**
 * Why an access token lifetime is refused, or `''`: the operator's cap for the partner first, then central's own rule for every client
 * (`validateAccessTokenTtl`: at most 24 hours, and at least 1 hour when the tokens are DPoP-bound, as a device client's are). A partner is
 * held to the same limits as the operator's own clients; the cap can only narrow them.
 */
export function partnerAccessTtlError(seconds: number, capSeconds: number, dpopBound: boolean): string {
  if (seconds > capSeconds) return `At most ${ttlLabel(capSeconds)}: the operator's cap.`;
  return validateAccessTokenTtl(seconds, dpopBound).error ?? '';
}

/** The caps in one line: `access ≤ 10 min · refresh ≤ 30 days`. */
export function ttlCapsLabel(limits: Pick<PartnerLimits, 'accessTokenTtlMax' | 'refreshTokenTtlMax'>): string {
  return `access ≤ ${ttlLabel(limits.accessTokenTtlMax)} · refresh ≤ ${ttlLabel(limits.refreshTokenTtlMax)}`;
}

/** Fields that always need the operator, whatever the limits say: they appear on the consent screen (#549 decision 7). */
export const CONSENT_SCREEN_FIELDS = ['clientName', 'logoUri', 'displayName'];

/**
 * What a kind sets when a partner owns the client (#549): the plan the kind step shows instead of the first-party one.
 * The credential is the group certificate the partner's own edges present (central provisions nothing), tokens are not
 * certificate-bound (every replica presents the same certificate), DPoP is off (tokens never leave the edge session) and
 * the access token is the partner's cap.
 */
export function partnerKindPlan(kind: ClientKind, partner: Pick<Partner, 'displayName' | 'groupId' | 'limits'>, credential: 'mtls' | 'private-key-jwt'): PlanLine[] {
  const limits = partner.limits;
  const plan: PlanLine[] = [
    { state: 'fixed', text: 'PKCE, S256 only', why: 'Every client on this server. Not a setting.' },
    credential === 'mtls'
      ? { state: 'fixed', text: `Group certificate: tls_client_auth, san_uri = urn:versola:edge-group:${partner.groupId}`, why: `The certificate every edge of ${partner.displayName} presents. Central provisions nothing; the partner's edges renew it themselves.` }
      : { state: 'fixed', text: 'private_key_jwt', why: `${partner.displayName} registers the public keys its edge signs assertions with.` },
    { state: 'on', text: `Served by the edges of group ${partner.groupId}`, why: kind === 'device'
      ? `The app signs in through ${partner.displayName}'s edge; the tokens are issued to the device.`
      : `Tokens stay inside ${partner.displayName}'s edge behind a session cookie and never reach the browser.` },
    { state: 'on', text: 'Pushed authorization requests', why: 'RFC 9126. Every partner client.' },
    { state: 'off', text: 'Certificate-bound access tokens', why: 'Every replica of the partner\'s edges presents the same group certificate, so binding would say nothing.' },
  ];
  if (kind === 'device') {
    const belowFloor = limits.accessTokenTtlMax < MIN_DPOP_BOUND_ACCESS_TOKEN_TTL_SECONDS;
    plan.push(
      { state: 'on', text: 'DPoP-bound access tokens', why: belowFloor
        ? `The device holds its tokens, so DPoP is their only sender-constraint. DPoP-bound tokens live at least ${ttlLabel(MIN_DPOP_BOUND_ACCESS_TOKEN_TTL_SECONDS)}: raise ${partner.displayName}'s access token cap (now ${ttlLabel(limits.accessTokenTtlMax)}) before creating one.`
        : 'The device holds its tokens, so DPoP is their only sender-constraint.' },
      { state: 'fixed', text: 'App Link redirect only', why: `A verified domain under ${limits.redirectDomains.join(', ')}. A custom scheme can be claimed by any app on the device.` },
    );
  }
  plan.push({ state: 'on', text: `${ttlLabel(limits.accessTokenTtlMax)} access token at most`, why: `${partner.displayName}'s cap; refresh at most ${ttlLabel(limits.refreshTokenTtlMax)}.` });
  return plan;
}

export type ReviewState = 'none' | 'ok' | 'due' | 'overdue';

/** `due` within 30 days of the review date, `overdue` past it. */
export function reviewState(reviewAt: string | null | undefined, now = Date.now()): ReviewState {
  if (!reviewAt) return 'none';
  const at = Date.parse(reviewAt);
  if (Number.isNaN(at)) return 'none';
  if (at < now) return 'overdue';
  if (at - now <= 30 * 86_400_000) return 'due';
  return 'ok';
}

export const PARTNER_STATUS_LABELS: Record<PartnerStatus, string> = {
  onboarding: 'Onboarding',
  active: 'Active',
  suspended: 'Suspended',
};

export const APPROVAL_KIND_LABELS: Record<PartnerApprovalKind, string> = {
  client_create: 'New client',
  client_change: 'Client change',
  profile_change: 'Name or logo',
};

export const APPROVAL_STATUS_LABELS: Record<PartnerApproval['status'], string> = {
  waiting: 'Waiting',
  approved: 'Approved',
  rejected: 'Rejected',
  withdrawn: 'Withdrawn',
};

/** The words the diff uses for a requested field. */
export const APPROVAL_FIELD_LABELS: Record<string, string> = {
  kind: 'Kind',
  clientName: 'Display name',
  logoUri: 'Logo',
  redirectUris: 'Redirect URIs',
  scope: 'Scopes',
  accessTokenTtl: 'Access token',
  refreshTokenTtl: 'Refresh token',
  backChannelLogoutUri: 'Back-channel logout',
  policyUri: 'Policy',
  tosUri: 'Terms',
  displayName: 'Display name',
  consent: 'Consent',
  authMethod: 'Client authentication',
};

export function approvalFieldLabel(name: string): string {
  return APPROVAL_FIELD_LABELS[name] ?? name;
}

/** A requested value as text, one line per list item; the TTL fields as durations. */
export function approvalValueLines(name: string, value: unknown): string[] {
  if (value === undefined || value === null || value === '') return [];
  if (Array.isArray(value)) return value.map(item => String(item));
  if ((name === 'accessTokenTtl' || name === 'refreshTokenTtl') && typeof value === 'number') return [ttlLabel(value)];
  if (name === 'kind') return [value === 'web' ? 'Web application' : value === 'device' || value === 'native' ? 'Native app' : String(value)];
  if (typeof value === 'object') return [JSON.stringify(value)];
  return [String(value)];
}

/** The fields of an approval, requested first, with what is registered beside each. */
export type ApprovalDiffRow = { field: string; label: string; current: string[]; requested: string[]; outside: boolean };

export function approvalDiff(approval: Pick<PartnerApproval, 'requested' | 'current' | 'outsideLimits'>): ApprovalDiffRow[] {
  const names = [...new Set([...Object.keys(approval.requested), ...Object.keys(approval.current ?? {})])];
  return names.map(field => ({
    field,
    label: approvalFieldLabel(field),
    current: approvalValueLines(field, approval.current?.[field]),
    requested: approvalValueLines(field, approval.requested[field]),
    // The name and the logo are what users see: they always need the operator, whatever the limits say.
    outside: approval.outsideLimits.includes(field) || CONSENT_SCREEN_FIELDS.includes(field),
  }));
}

/** Whether a request changes what users see on the consent screen: the preview is drawn then. */
export function changesConsentScreen(approval: Pick<PartnerApproval, 'kind' | 'requested'>): boolean {
  if (approval.kind === 'profile_change') return true;
  return ['clientName', 'logoUri', 'scope', 'displayName'].some(field => field in approval.requested);
}

export type PartnerActivityEvent = { at: string; actor: string; byOperator?: boolean; text: string };

/** The last things that happened to a partner, read off what the console already holds: its approvals (who asked, who decided) and its
 *  edges' renewals. Newest first. */
export function partnerActivity(partner: Pick<Partner, 'id' | 'createdAt'>, approvals: PartnerApproval[], edges: Edge[]): PartnerActivityEvent[] {
  const events: PartnerActivityEvent[] = [];
  for (const approval of approvals.filter(candidate => candidate.partnerId === partner.id)) {
    const what = approval.kind === 'profile_change' ? 'asked to change the name or logo'
      : approval.kind === 'client_create' ? `submitted client ${approval.targetId} for approval`
        : `asked to change client ${approval.targetId}`;
    events.push({ at: approval.requestedAt, actor: approval.requestedBy, text: what });
    if (approval.decidedAt && approval.decidedBy) {
      events.push({ at: approval.decidedAt, actor: approval.decidedBy, byOperator: true, text: `${approval.status === 'approved' ? 'approved' : 'rejected'} ${approval.kind === 'profile_change' ? 'the name or logo change' : `the request for ${approval.targetId}`}` });
    }
  }
  for (const edge of edges.filter(candidate => candidate.partnerId === partner.id)) {
    if (edge.lastRenewedAt) events.push({ at: edge.lastRenewedAt, actor: `edge ${edge.id}`, text: 'renewed its certificate' });
  }
  events.push({ at: partner.createdAt, actor: 'operator', byOperator: true, text: 'added the partner' });
  return events.sort((a, b) => Date.parse(b.at) - Date.parse(a.at));
}

/** The default limits a new partner starts from. */
export function defaultPartnerLimits(): PartnerLimits {
  return {
    scopes: ['openid'],
    redirectDomains: [],
    maxClients: 10,
    maxEdges: 3,
    accessTokenTtlMax: 10 * 60,
    refreshTokenTtlMax: 30 * 86_400,
    clientAuthMethods: ['tls_client_auth'],
    rateLimitPolicy: 'partner-default',
  };
}

/** The edge rate-limit policy sets a group may be put on. */
export const RATE_LIMIT_POLICY_OPTIONS: Array<{ id: string; label: string }> = [
  { id: 'partner-default', label: 'partner-default · 500 req/s per edge, 50 per user' },
  { id: 'partner-high', label: 'partner-high · 2000 req/s per edge, 200 per user' },
  { id: 'none', label: 'none · no rate limit' },
];
