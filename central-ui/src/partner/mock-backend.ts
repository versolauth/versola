/**
 * The mocked partner edge behind the partner console (#963): `/partner/api/*` as the P1 edge will
 * serve it, on the same `MockPage` / `MockRoute` interfaces as the operator mock, so the Playwright
 * specs and the in-page demo share it. It answers in every state the screens must survive: a
 * staff member who is not on the team (`not_a_member`), a suspended partner, central unavailable
 * (503 on everything central answers), and the limit rules on clients. Its shapes, status codes and
 * error codes are those of `edge/open-api/partner-api.yaml`; where the console's own behaviour differs
 * from it (revocation), the PR says so.
 */
import type { MockPage, MockRequest } from '../demo/mock-backend';
import type { PartnerLimits } from '../types';
import { CONSENT_SCREEN_FIELDS, partnerLimitViolations } from '../utils/partners';
import { validateAccessTokenTtl } from '../utils/validators';
import type {
  AuditEventRecord, CreatedEdge, EnrollmentToken, PartnerClient, PartnerClientWrite, PartnerEdge, PartnerMe, PartnerResource, PartnerResourceWrite, PartnerSettings,
  ProfileApproval, ProfileChange, Revocation, RevocationRequest, StaffInvite, StaffLevel, StaffMember,
} from './partner-types';

export type MockPartnerState = {
  /** What `/partner/api/me` answers; `null` answers 500. */
  me: PartnerMe | null;
  /** Answers `/me` with 403 `not_a_member` and this masked identifier. */
  notAMember?: string;
  /** Central cannot be reached: everything the edge answers by calling central is 503 `central_unavailable`; `/me` and the team, which the edge answers itself, keep working. */
  centralUnavailable?: boolean;
  /** The edges whose enrollment token was issued and not used yet, with its expiry. The DTO does not carry it; only Revoke token needs it. */
  pendingTokens?: Record<string, string>;
  clients: PartnerClient[];
  resources: PartnerResource[];
  edges: PartnerEdge[];
  revocations: Revocation[];
  audit: AuditEventRecord[];
  staff: StaffMember[];
  settings: PartnerSettings;
  profileApproval: ProfileApproval | null;
  /** Holds every request this long, to look at loading states. */
  delayMs?: number;
  /** The clock the mock stamps writes with (ISO); the real clock when absent. A spec pins it to the page's clock. */
  now?: string;
};

export type PartnerRequestLog = { method: string; pathname: string; searchParams: Record<string, string>; body: unknown };

export type MockPartnerHarness = { state: MockPartnerState; requests: PartnerRequestLog[] };

const AUDIT_PAGE = 8;

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function json(data: unknown, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(data) };
}

function readBody(request: MockRequest): unknown {
  const body = request.postData();
  return body ? JSON.parse(body) : undefined;
}

/** The bootstrap bundle the partner console shows once for a new edge. */
export function partnerBundle(edgeId: string, me: PartnerMe, token: string): string {
  const central = (me.partner.operator.centralMtlsUrl ?? '').replace('central-mtls.', 'central.');
  return JSON.stringify({
    edgeId, group: me.partner.id,
    central: { enrollUrl: `${central}/edge/enroll`, mtlsUrl: me.partner.operator.centralMtlsUrl, pins: ['sha256/4f3a0c7e9b2d…c1', 'sha256/9b2e51a8d0f4…77'] },
    auth: { issuer: me.partner.operator.issuer, mtlsUrl: me.partner.operator.mtlsUrl, pins: ['sha256/a1d08c3f5e27…e5'] },
    token,
  }, null, 2);
}

/** What central refuses for any client, partner or not: an access token lifetime outside its rule (at most 24 h, at least 1 h when DPoP-bound, as a device client's are). */
export function tokenLifetimeError(client: { kind: string; accessTokenTtl: number }): string | undefined {
  return validateAccessTokenTtl(client.accessTokenTtl, client.kind === 'native').error;
}

/** The fields of a client write that wait for the operator: the consent-screen ones always, the rest when outside the limits. */
export function waitingFieldsOf(limits: PartnerLimits, client: PartnerClientWrite, previous?: PartnerClient): string[] {
  const outside = partnerLimitViolations(limits, client);
  const consent = CONSENT_SCREEN_FIELDS.filter(field => field in client && (!previous || (client as Record<string, unknown>)[field] !== (previous as unknown as Record<string, unknown>)[field]));
  return [...new Set([...consent, ...outside])];
}

export function mergePartnerState(overrides: Partial<MockPartnerState> = {}, base: MockPartnerState): MockPartnerState {
  return {
    ...clone(base),
    ...clone(overrides),
  };
}

/** Registers the partner API on `page`. `base` is the seed the overrides apply to (the demo seed by default). */
export async function setupPartnerApiMocks(page: MockPage, overrides: Partial<MockPartnerState> = {}, base?: MockPartnerState): Promise<MockPartnerHarness> {
  const seed = base ?? (await import('./seed')).partnerSeed();
  const state = mergePartnerState(overrides, seed);
  const requests: PartnerRequestLog[] = [];
  const nowMs = () => (state.now ? Date.parse(state.now) : Date.now());
  const nowIso = () => new Date(nowMs()).toISOString();
  const issueToken = (edge: PartnerEdge): EnrollmentToken => {
    const me = state.me!;
    const token = `vtk_${me.partner.id}_${Math.random().toString(36).slice(2, 10)}${Math.random().toString(36).slice(2, 10)}`;
    // An enrollment token lives 24 hours.
    const expiresAt = new Date(nowMs() + 24 * 60 * 60_000).toISOString();
    const issued = { token, bundle: partnerBundle(edge.id, me, token), bundleComplete: false, expiresAt };
    state.pendingTokens = { ...(state.pendingTokens ?? {}), [edge.id]: expiresAt };
    return issued;
  };

  /** One audit event of what the signed-in staff member just did, as the edge relays it: the kind, the action in words, the actor with a name. */
  const recordAudit = (kind: string, action: string, target: string) => {
    const me = state.me!;
    state.audit = [{ id: `ev-${Date.now()}-${state.audit.length}`, kind, at: nowIso(), actor: { sub: me.staff.sub, display: me.staff.display ?? me.staff.sub }, action, target, outcome: 'ok', from: me.edge.id }, ...state.audit];
  };

  await page.route('**/partner/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = url.pathname.replace(/^.*\/partner\/api/, '');
    const method = request.method();
    const body = readBody(request);
    requests.push({ method, pathname, searchParams: Object.fromEntries(url.searchParams.entries()), body });
    if (state.delayMs) await new Promise(resolve => setTimeout(resolve, state.delayMs));

    const refuse = (status: number, error: string, extra: Record<string, unknown> = {}) => route.fulfill(json({ error, ...extra }, status));
    const write = method !== 'GET';
    // What the edge answers itself: who is asking, the team, the pending profile change, and the one operation a partner does not have.
    const edgeServed = pathname === '/me' || pathname === '/staff' || pathname.startsWith('/staff/') || (pathname === '/profile' && method === 'GET') || /^\/clients\/[^/]+\/rotate-secret$/.test(pathname)
      // Revocation here ends sessions on the partner's own edges (the owner's decision), so it does not go through central; the edge's contract has it central-served.
      || pathname === '/revocations';
    const level: StaffLevel = state.me?.staff.level ?? 'viewer';
    const rank: Record<StaffLevel, number> = { viewer: 0, developer: 1, admin: 2 };
    const required: StaffLevel = pathname === '/me' ? 'viewer'
      : pathname.startsWith('/staff') || pathname === '/settings' || pathname === '/profile' || (pathname === '/revocations' && write) ? 'admin'
        : write ? 'developer' : 'viewer';
    if (pathname !== '/me' && rank[level] < rank[required]) {
      await refuse(403, 'insufficient_level', { required, message: `A ${level} may not do this` });
      return;
    }
    if (!edgeServed && state.centralUnavailable) {
      await refuse(503, 'central_unavailable', { message: 'central is unreachable' });
      return;
    }
    // A suspended partner: every write is refused by the edge itself, and so are the management reads (central refuses them); /me and the team still answer.
    if (!edgeServed && state.me?.partner.status === 'suspended') {
      await refuse(403, 'partner_suspended', { message: 'the operator has suspended the partner' });
      return;
    }

    if (pathname === '/me' && method === 'GET') {
      if (state.notAMember) {
        await route.fulfill(json({ error: 'not_a_member', identifier: state.notAMember, partner: state.me?.partner.displayName ?? '' }, 403));
        return;
      }
      if (!state.me) {
        await refuse(500, 'internal_error');
        return;
      }
      await route.fulfill(json({ ...state.me, edge: { ...state.me.edge, central: state.centralUnavailable ? 'unavailable' : state.me.edge.central } }));
      return;
    }
    const me = state.me!;
    const limits = me.partner.limits;

    // ── clients ──
    const clientMatch = pathname.match(/^\/clients(?:\/([^/]+))?(?:\/(rotate-secret|withdraw))?$/);
    if (clientMatch) {
      const id = clientMatch[1] ? decodeURIComponent(clientMatch[1]) : null;
      const action = clientMatch[2];
      if (method === 'GET' && !id) {
        await route.fulfill(json({ clients: state.clients }));
        return;
      }
      if (method === 'POST' && !id) {
        const payload = body as PartnerClientWrite;
        if (state.clients.some(client => client.id === payload.id)) {
          await refuse(409, 'conflict', { message: `Client ${payload.id} already exists` });
          return;
        }
        // Central decides what is inside the limits and answers 400 with the reason in `message`; the edge sends no `outside_limits` code.
        const outsideAtCreate = [...(state.clients.length >= limits.maxClients ? ['maxClients'] : []), ...partnerLimitViolations(limits, payload)];
        const lifetimeError = tokenLifetimeError(payload);
        if (lifetimeError || outsideAtCreate.length > 0) {
          await refuse(400, 'invalid_request', { message: lifetimeError ? `accessTokenTtl: ${lifetimeError}` : `outside the partner's limits: ${outsideAtCreate.join(', ')}` });
          return;
        }
        // Registered waiting for the operator (`approval.kind` create): the name and the logo wait.
        const waiting = ['clientName', ...(payload.logoUri ? ['logoUri'] : [])];
        const now = nowIso();
        const authMethod = payload.authMethod ?? 'tls_client_auth';
        const client: PartnerClient = {
          id: payload.id, kind: payload.kind, clientName: payload.clientName, logoUri: payload.logoUri ?? null, policyUri: payload.policyUri ?? null, tosUri: payload.tosUri ?? null,
          redirectUris: payload.redirectUris, scope: payload.scope, accessTokenTtl: payload.accessTokenTtl, refreshTokenTtl: payload.refreshTokenTtl ?? null,
          backChannelLogoutUri: payload.backChannelLogoutUri ?? null, authMethod, limits, createdAt: now,
          loginPath: `/login/${payload.id}`,
          approval: { kind: 'create', status: 'pending', waitingFields: waiting, requestedAt: now, note: null },
          fixed: { consent: true, flow: 'code+pkce+par', authMethod, group: me.partner.id },
        };
        state.clients = [client, ...state.clients];
        recordAudit('clients.create', 'submitted a client for approval', client.id);
        await route.fulfill(json(client, 202));
        return;
      }
      const client = id ? state.clients.find(candidate => candidate.id === id) : undefined;
      if (!client) {
        await refuse(404, 'not_found', { message: `Client ${id} was not found` });
        return;
      }
      if (method === 'POST' && action === 'rotate-secret') {
        // A partner client has no secret and no key the operator holds: there is nothing to rotate.
        await refuse(409, 'not_supported', { message: "a partner client authenticates with the group's certificate: it has no secret to rotate" });
        return;
      }
      if (method === 'PUT' && !action) {
        const payload = body as PartnerClientWrite;
        if (payload.id && payload.id !== client.id) {
          await refuse(400, 'invalid_request', { message: 'the id in the body differs from the path' });
          return;
        }
        const lifetimeError = tokenLifetimeError({ kind: client.kind, accessTokenTtl: payload.accessTokenTtl });
        if (lifetimeError) {
          await refuse(400, 'invalid_request', { message: `accessTokenTtl: ${lifetimeError}` });
          return;
        }
        const waiting = waitingFieldsOf(limits, payload, client);
        // Within the limits a change applies at once (200); the name, the logo and anything outside the limits wait for the operator (202).
        const applied = { ...payload } as Partial<PartnerClientWrite>;
        for (const field of waiting) delete (applied as Record<string, unknown>)[field];
        delete applied.authMethod;
        delete applied.kind;
        Object.assign(client, applied);
        recordAudit('clients.update', 'changed a client', client.id);
        if (waiting.length > 0) {
          client.approval = { kind: 'change', status: 'pending', waitingFields: waiting, requestedAt: nowIso(), note: null };
          await route.fulfill(json(client, 202));
          return;
        }
        await route.fulfill(json(client));
        return;
      }
      if (method === 'DELETE' && !action) {
        state.clients = state.clients.filter(candidate => candidate.id !== id);
        recordAudit('clients.delete', 'deleted a client', id!);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
      if (method === 'POST' && action === 'withdraw') {
        if (client.approval.status !== 'pending') {
          await refuse(409, 'conflict', { message: 'Nothing is waiting for this client' });
          return;
        }
        // The approval says whether it is the client's creation (the client is gone with its request) or a change (the client stays as registered).
        const neverApproved = client.approval.kind === 'create';
        if (neverApproved) state.clients = state.clients.filter(candidate => candidate.id !== id);
        else client.approval = { kind: 'change', status: 'approved', waitingFields: [], note: null };
        recordAudit('clients.withdraw', 'withdrew a request for a client', id!);
        await route.fulfill(json(neverApproved ? { ...client, approval: { ...client.approval, status: 'rejected', waitingFields: [], note: 'withdrawn' } } : client));
        return;
      }
    }

    // ── resources ──
    const resourceMatch = pathname.match(/^\/resources(?:\/([^/]+))?(?:\/(rotate-secret))?$/);
    if (resourceMatch) {
      const id = resourceMatch[1] ? decodeURIComponent(resourceMatch[1]) : null;
      const action = resourceMatch[2];
      if (method === 'GET' && !id) {
        await route.fulfill(json({ resources: state.resources }));
        return;
      }
      if (method === 'POST' && !id) {
        const payload = body as PartnerResourceWrite;
        if (state.resources.some(resource => resource.resourceId === payload.resourceId)) {
          await refuse(409, 'conflict', { message: `Resource ${payload.resourceId} already exists` });
          return;
        }
        const resource: PartnerResource = { resourceId: payload.resourceId, resource: payload.resource, audience: [...payload.audience], endpoints: payload.endpoints ?? [], secretRotation: false };
        state.resources = [resource, ...state.resources];
        await route.fulfill(json({ resource, secret: `generated-${resource.resourceId}` }, 201));
        return;
      }
      const resource = id ? state.resources.find(candidate => candidate.resourceId === id) : undefined;
      if (!resource) {
        await refuse(404, 'not_found', { message: `Resource ${id} was not found` });
        return;
      }
      if (method === 'PUT' && !action) {
        const payload = body as PartnerResourceWrite;
        resource.resource = payload.resource;
        resource.audience = [...payload.audience];
        if (payload.endpoints) {
          let next = Math.max(400, ...state.resources.flatMap(candidate => candidate.endpoints.map(endpoint => Number(endpoint.id) || 0))) + 1;
          resource.endpoints = payload.endpoints.map(endpoint => ({ ...endpoint, id: endpoint.id ?? next++ }));
        }
        await route.fulfill(json(resource));
        return;
      }
      if (method === 'DELETE' && !action) {
        state.resources = state.resources.filter(candidate => candidate.resourceId !== id);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
      if (method === 'POST' && action === 'rotate-secret') {
        resource.secretRotation = true;
        await route.fulfill(json({ secret: `rotated-${id}` }));
        return;
      }
    }

    // ── edges ──
    const edgeMatch = pathname.match(/^\/edges(?:\/([^/]+))?(?:\/(enrollment-tokens))?$/);
    if (edgeMatch) {
      const id = edgeMatch[1] ? decodeURIComponent(edgeMatch[1]) : null;
      const action = edgeMatch[2];
      if (method === 'GET' && !id) {
        await route.fulfill(json({ edges: state.edges.map(edge => ({ ...edge, status: me.partner.status === 'suspended' && edge.status === 'enrolled' ? 'suspended' : edge.status })) }));
        return;
      }
      if (method === 'POST' && !id) {
        const payload = body as { id: string; publicUrl: string };
        if (state.edges.some(edge => edge.id === payload.id)) {
          await refuse(409, 'conflict', { message: `Edge ${payload.id} already exists` });
          return;
        }
        if (state.edges.length >= limits.maxEdges) {
          await refuse(409, 'conflict', { message: `the partner is at its limit of ${limits.maxEdges} edges` });
          return;
        }
        const edge: PartnerEdge = { id: payload.id, publicUrl: payload.publicUrl, status: 'waiting', replicas: 0, policies: state.edges[0]?.policies ?? { overload: {}, accessLog: {}, rateLimits: {} } };
        // One write: the edge and its first enrollment token, so a failure between the two cannot leave an edge without a token.
        const issued = issueToken(edge);
        state.edges = [...state.edges, edge];
        const keyId = nowIso().slice(0, 19).replace('T', '_').replace(/:/g, '-');
        const created: CreatedEdge = { edge, signingKey: { keyId, privateKey: `-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC…${edge.id}\n-----END PRIVATE KEY-----` }, ...issued, bundleComplete: true };
        await route.fulfill(json(created, 201));
        return;
      }
      const edge = id ? state.edges.find(candidate => candidate.id === id) : undefined;
      if (!edge) {
        await refuse(404, 'not_found', { message: `Edge ${id} was not found` });
        return;
      }
      if (method === 'DELETE' && !action) {
        state.edges = state.edges.filter(candidate => candidate.id !== id);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
      if (method === 'POST' && action === 'enrollment-tokens') {
        if (edge.status !== 'waiting') {
          await refuse(409, 'conflict', { message: `Edge ${id} is already enrolled` });
          return;
        }
        const issued = issueToken(edge);
        await route.fulfill(json(issued, 201));
        return;
      }
      if (method === 'DELETE' && action === 'enrollment-tokens') {
        if (!state.pendingTokens?.[edge.id]) {
          await refuse(409, 'conflict', { message: `Edge ${edge.id} has no unused enrollment token` });
          return;
        }
        const { [edge.id]: _revoked, ...rest } = state.pendingTokens;
        state.pendingTokens = rest;
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    // ── revocations ──
    if (pathname === '/revocations') {
      if (method === 'GET') {
        await route.fulfill(json({ revocations: state.revocations }));
        return;
      }
      if (method === 'POST') {
        const payload = body as RevocationRequest;
        if (!payload.value?.trim() || !['user', 'sid', 'fam'].includes(payload.by)) {
          await refuse(400, 'invalid_request', { message: 'by is user, sid or fam, and value is required' });
          return;
        }
        const ended = payload.by === 'sid' || payload.by === 'fam' ? 1 : 3;
        const revocation: Revocation = { id: `rv-${Date.now()}`, at: nowIso(), by_staff: me.staff.sub, by: payload.by, value: payload.value.trim(), clients: [], edges: [], ended };
        state.revocations = [revocation, ...state.revocations];
        recordAudit('team.revoke', `Ended ${ended} ${ended === 1 ? 'session' : 'sessions'} by ${payload.by === 'user' ? 'user' : payload.by === 'fam' ? 'refresh family' : 'session'} ${revocation.value}`, revocation.value);
        await route.fulfill(json({ ended }));
        return;
      }
    }

    // ── audit ──
    if (pathname === '/audit' && method === 'GET') {
      const actor = url.searchParams.get('actor') ?? '';
      const kind = url.searchParams.get('kind') ?? '';
      const cursor = url.searchParams.get('cursor');
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      // `actor` is team, operator, edges or a `sub`; `kind` is clients, resources, edges, team, revocations or security, or one exact kind; `q` is free text.
      const isEdge = (sub: string) => sub.startsWith('edge-');
      const matches = state.audit
        .filter(event => !actor || (actor === 'operator' ? !!event.actor.byOperator : actor === 'edges' ? isEdge(event.actor.sub) : actor === 'team' ? !event.actor.byOperator && !isEdge(event.actor.sub) && event.actor.sub !== 'anonymous' : event.actor.sub === actor))
        .filter(event => !kind || (kind === 'security' ? event.outcome !== 'ok' : (kind === 'revocations' ? event.kind === 'team.revoke' : event.kind === kind || (event.kind.startsWith(`${kind}.`) && event.kind !== 'team.revoke'))))
        .filter(event => !q || `${event.actor.display ?? ''} ${event.actor.sub} ${event.action} ${event.target}`.toLowerCase().includes(q));
      const start = cursor ? Math.max(0, matches.findIndex(event => event.id === cursor)) : 0;
      const pageEvents = matches.slice(start, start + AUDIT_PAGE);
      const next = matches[start + AUDIT_PAGE]?.id ?? null;
      await route.fulfill(json({ events: pageEvents, next }));
      return;
    }

    // ── staff ──
    const staffMatch = pathname.match(/^\/staff(?:\/([^/]+))?$/);
    if (staffMatch) {
      const id = staffMatch[1] ? decodeURIComponent(staffMatch[1]) : null;
      if (method === 'GET' && !id) {
        await route.fulfill(json({ staff: state.staff }));
        return;
      }
      if (method === 'POST' && !id) {
        const payload = body as StaffInvite;
        const identifier = payload.identifier.trim();
        const kind = identifier.includes('@') ? 'email' : 'phone';
        const masked = kind === 'email' ? identifier : identifier.replace(/\d(?=\d{2})/g, '•');
        const now = nowMs();
        const member: StaffMember = {
          id: `inv-${Date.now()}`, identifier: { kind, masked }, level: payload.level, state: 'invited', addedBy: me.staff.sub,
          addedAt: new Date(now).toISOString(), expiresAt: new Date(now + payload.expiresInDays * 86_400_000).toISOString(),
        };
        state.staff = [...state.staff, member];
        await route.fulfill(json(member, 201));
        return;
      }
      const member = id ? state.staff.find(candidate => candidate.id === id) : undefined;
      if (!member) {
        await refuse(404, 'not_found', { message: `Member ${id} was not found` });
        return;
      }
      if (method === 'PUT') {
        member.level = (body as { level: StaffLevel }).level;
        await route.fulfill(json(member));
        return;
      }
      if (method === 'DELETE') {
        if (member.subject === me.staff.sub) {
          await refuse(409, 'conflict', { message: 'You cannot remove yourself' });
          return;
        }
        state.staff = state.staff.filter(candidate => candidate.id !== id);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    // ── settings and profile ──
    if (pathname === '/settings') {
      if (method === 'GET') {
        await route.fulfill(json(state.settings));
        return;
      }
      if (method === 'PUT') {
        state.settings = clone(body as PartnerSettings);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }
    if (pathname === '/profile') {
      if (method === 'GET') {
        await route.fulfill(json({ approval: state.profileApproval }));
        return;
      }
      if (method === 'PUT') {
        const payload = body as ProfileChange;
        state.profileApproval = { id: `apr-${Date.now()}`, status: 'waiting', requestedAt: nowIso(), requested: { displayName: payload.displayName, logoUri: payload.logoUri ?? null } };
        await route.fulfill(json({ approval: state.profileApproval }, 202));
        return;
      }
    }

    await refuse(404, 'not_found', { message: `No mock handler for ${method} ${pathname}` });
  });

  return { state, requests };
}
