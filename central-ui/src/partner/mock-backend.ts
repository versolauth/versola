/**
 * The mocked partner edge behind the partner console (#963): `/partner/api/*` as the P1 edge will
 * serve it, on the same `MockPage` / `MockRoute` interfaces as the operator mock, so the Playwright
 * specs and the in-page demo share it. It answers in every state the screens must survive: a
 * staff member who is not on the team (`not_a_member`), a suspended partner, central unavailable
 * on writes, and the limit rules on clients.
 */
import type { MockPage, MockRequest } from '../demo/mock-backend';
import type { PartnerLimits } from '../types';
import { CONSENT_SCREEN_FIELDS, partnerLimitViolations } from '../utils/partners';
import type {
  AuditEventRecord, CreatedEdge, EnrollmentToken, PartnerClient, PartnerClientWrite, PartnerEdge, PartnerMe, PartnerResource, PartnerResourceWrite, PartnerSettings,
  ProfileApproval, ProfileChange, Revocation, RevocationRequest, StaffInvite, StaffLevel, StaffMember,
} from './partner-types';

export type MockPartnerState = {
  /** What `/partner/api/me` answers; `null` answers 500. */
  me: PartnerMe | null;
  /** Answers `/me` with 403 `not_a_member` and this masked identifier. */
  notAMember?: string;
  /** Makes every write answer 503 `central_unavailable`; reads keep working from the snapshot. */
  centralUnavailable?: boolean;
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
  const central = me.partner.operator.centralMtlsUrl.replace('central-mtls.', 'central.');
  return JSON.stringify({
    edgeId, group: me.partner.id,
    central: { enrollUrl: `${central}/edge/enroll`, mtlsUrl: me.partner.operator.centralMtlsUrl, pins: ['sha256/4f3a0c7e9b2d…c1', 'sha256/9b2e51a8d0f4…77'] },
    auth: { issuer: me.partner.operator.issuer, mtlsUrl: me.partner.operator.mtlsUrl, pins: ['sha256/a1d08c3f5e27…e5'] },
    token,
  }, null, 2);
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
  const tokens = new Map<string, EnrollmentToken>();
  const nowMs = () => (state.now ? Date.parse(state.now) : Date.now());
  const nowIso = () => new Date(nowMs()).toISOString();
  const issueToken = (edge: PartnerEdge): EnrollmentToken => {
    const me = state.me!;
    const token = `vtk_${me.partner.id}_${Math.random().toString(36).slice(2, 10)}${Math.random().toString(36).slice(2, 10)}`;
    const expiresAt = new Date(nowMs() + 60 * 60_000).toISOString();
    const issued = { token, bundle: partnerBundle(edge.id, me, token), expiresAt };
    tokens.set(edge.id, issued);
    edge.pendingToken = { expiresAt };
    return issued;
  };

  await page.route('**/partner/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = url.pathname.replace(/^.*\/partner\/api/, '');
    const method = request.method();
    const body = readBody(request);
    requests.push({ method, pathname, searchParams: Object.fromEntries(url.searchParams.entries()), body });
    if (state.delayMs) await new Promise(resolve => setTimeout(resolve, state.delayMs));

    const write = method !== 'GET';
    if (write && state.centralUnavailable && pathname !== '/staff' && !pathname.startsWith('/staff/') && pathname !== '/revocations') {
      // Staff and the sessions live on the partner's own edges: neither needs central, so ending a session works while it is down.
      await route.fulfill(json({ error: 'central_unavailable', message: 'central is unreachable' }, 503));
      return;
    }
    if (write && state.me?.partner.status === 'suspended' && !pathname.startsWith('/staff')) {
      await route.fulfill(json({ error: 'partner_suspended' }, 403));
      return;
    }
    const level: StaffLevel = state.me?.staff.level ?? 'viewer';
    const adminOnly = pathname.startsWith('/staff') || pathname === '/settings' || pathname === '/profile' || pathname === '/revocations';
    if (write && (level === 'viewer' || (level === 'developer' && adminOnly))) {
      await route.fulfill(json({ error: 'forbidden', message: `A ${level} may not do this` }, 403));
      return;
    }

    if (pathname === '/me' && method === 'GET') {
      if (state.notAMember) {
        await route.fulfill(json({ error: 'not_a_member', identifier: state.notAMember, partner: state.me?.partner.displayName ?? '' }, 403));
        return;
      }
      if (!state.me) {
        await route.fulfill(json({ message: 'edge database unavailable' }, 500));
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
          await route.fulfill(json({ message: `Client ${payload.id} already exists` }, 409));
          return;
        }
        if (state.clients.length >= limits.maxClients) {
          await route.fulfill(json({ error: 'outside_limits', fields: ['maxClients'] }, 403));
          return;
        }
        // Creating a client always needs the operator (#549 decision 7): the name and logo wait, plus whatever is outside the limits.
        const waiting = [...new Set(['clientName', ...(payload.logoUri ? ['logoUri'] : []), ...partnerLimitViolations(limits, payload)])];
        const now = nowIso();
        const client: PartnerClient = {
          ...payload, logoUri: payload.logoUri ?? null, policyUri: payload.policyUri ?? null, tosUri: payload.tosUri ?? null,
          backChannelLogoutUri: payload.backChannelLogoutUri ?? null, createdAt: now,
          loginUrl: `${me.edge.publicUrl}/login/${payload.id}`,
          approval: { kind: 'create', status: 'pending', waitingFields: waiting, requestedAt: now },
          fixed: { consent: true, flow: 'code+pkce+par', authMethod: payload.authMethod ?? 'tls_client_auth', group: me.partner.id },
        };
        delete (client as Partial<PartnerClientWrite>).authMethod;
        state.clients = [client, ...state.clients];
        state.audit = [{ id: `ev-${Date.now()}`, at: now, actor: { sub: me.staff.sub, display: me.staff.display }, action: 'submitted a client for approval', target: client.id, outcome: 'ok', from: me.edge.id }, ...state.audit];
        await route.fulfill(json(client, 201));
        return;
      }
      const client = id ? state.clients.find(candidate => candidate.id === id) : undefined;
      if (!client) {
        await route.fulfill(json({ message: `Client ${id} was not found` }, 404));
        return;
      }
      if (method === 'PUT' && !action) {
        const payload = body as PartnerClientWrite;
        const outside = partnerLimitViolations(limits, payload);
        const waiting = waitingFieldsOf(limits, payload, client);
        // Within the limits a change applies at once; the name, the logo and anything outside the limits wait for the operator.
        const applied = { ...payload } as Partial<PartnerClientWrite>;
        for (const field of waiting) delete (applied as Record<string, unknown>)[field];
        delete applied.authMethod;
        Object.assign(client, applied);
        if (waiting.length > 0) {
          client.approval = { kind: 'change', status: 'pending', waitingFields: waiting, requestedAt: nowIso() };
          state.audit = [{ id: `ev-${Date.now()}`, at: client.approval.requestedAt!, actor: { sub: me.staff.sub, display: me.staff.display }, action: 'asked the operator to change a client', target: client.id, outcome: 'ok', from: me.edge.id }, ...state.audit];
          await route.fulfill(json({ ...client, approval: { ...client.approval, waitingFields: [...waiting] }, outsideLimits: outside }, 202));
          return;
        }
        await route.fulfill(json(client));
        return;
      }
      if (method === 'DELETE' && !action) {
        state.clients = state.clients.filter(candidate => candidate.id !== id);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
      if (method === 'POST' && action === 'rotate-secret') {
        await route.fulfill(json({ rotatedAt: nowIso() }));
        return;
      }
      if (method === 'POST' && action === 'withdraw') {
        if (client.approval.status !== 'pending') {
          await route.fulfill(json({ message: 'Nothing is waiting for this client' }, 409));
          return;
        }
        // The approval says whether it is the client's creation (the client is gone with its request) or a change (the client stays as registered).
        const neverApproved = client.approval.kind === 'create';
        if (neverApproved) state.clients = state.clients.filter(candidate => candidate.id !== id);
        else client.approval = { kind: 'change', status: 'approved', waitingFields: [] };
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
          await route.fulfill(json({ message: `Resource ${payload.resourceId} already exists` }, 409));
          return;
        }
        const resource: PartnerResource = { resourceId: payload.resourceId, resource: payload.resource, audience: [...payload.audience], endpoints: payload.endpoints ?? [], secretRotation: false, secretRotatedAt: nowIso() };
        state.resources = [resource, ...state.resources];
        await route.fulfill(json({ resource, secret: `generated-${resource.resourceId}` }, 201));
        return;
      }
      const resource = id ? state.resources.find(candidate => candidate.resourceId === id) : undefined;
      if (!resource) {
        await route.fulfill(json({ message: `Resource ${id} was not found` }, 404));
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
        resource.secretRotatedAt = nowIso();
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
          await route.fulfill(json({ message: `Edge ${payload.id} already exists` }, 409));
          return;
        }
        if (state.edges.length >= limits.maxEdges) {
          await route.fulfill(json({ error: 'outside_limits', fields: ['maxEdges'] }, 403));
          return;
        }
        const edge: PartnerEdge = { id: payload.id, publicUrl: payload.publicUrl, status: 'waiting', replicas: 0, policies: state.edges[0]?.policies ?? { overload: {}, accessLog: {}, rateLimits: {} } };
        // One write: the edge and its first enrollment token, so a failure between the two cannot leave an edge without a token.
        const issued = issueToken(edge);
        state.edges = [...state.edges, edge];
        const created: CreatedEdge = { edge, ...issued };
        await route.fulfill(json(created, 201));
        return;
      }
      const edge = id ? state.edges.find(candidate => candidate.id === id) : undefined;
      if (!edge) {
        await route.fulfill(json({ message: `Edge ${id} was not found` }, 404));
        return;
      }
      if (method === 'DELETE' && !action) {
        state.edges = state.edges.filter(candidate => candidate.id !== id);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
      if (method === 'POST' && action === 'enrollment-tokens') {
        if (edge.status !== 'waiting') {
          await route.fulfill(json({ message: `Edge ${id} is already enrolled` }, 409));
          return;
        }
        const issued = issueToken(edge);
        await route.fulfill(json(issued, 201));
        return;
      }
      if (method === 'DELETE' && action === 'enrollment-tokens') {
        tokens.delete(edge.id);
        delete edge.pendingToken;
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
        const ownEdges = state.edges.filter(edge => edge.status === 'enrolled').map(edge => edge.id);
        if (!payload.value?.trim() || !payload.clients?.length || !payload.edges?.length) {
          await route.fulfill(json({ message: 'value, clients and edges are required' }, 400));
          return;
        }
        if (payload.edges.some(id => !ownEdges.includes(id))) {
          await route.fulfill(json({ message: 'Only your own enrolled edges can end sessions' }, 400));
          return;
        }
        const ended = payload.by === 'sid' || payload.by === 'fam' ? 1 : payload.by === 'client' ? 1284 : 3;
        const revocation: Revocation = { id: `rv-${Date.now()}`, at: nowIso(), by_staff: me.staff.display ?? me.staff.sub, ...payload, ended };
        state.revocations = [revocation, ...state.revocations];
        state.audit = [{ id: `ev-${Date.now()}`, at: revocation.at, actor: { sub: me.staff.sub, display: me.staff.display }, action: `ended sessions by ${payload.by}`, target: `${payload.clients.join(', ')} · ${payload.value}`, outcome: 'ok', from: me.edge.id }, ...state.audit];
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
      const matches = state.audit
        .filter(event => !actor || (actor === 'operator' ? !!event.actor.byOperator : actor === 'edges' ? event.actor.sub.startsWith('edge-') : actor === 'team' ? !event.actor.byOperator && !event.actor.sub.startsWith('edge-') && event.actor.sub !== 'anonymous' : event.actor.sub === actor))
        .filter(event => !kind || (kind === 'security' ? event.outcome !== 'ok' : kind === 'clients' ? /client/.test(event.action) : kind === 'resources' ? /resource/.test(event.action) : kind === 'edges' ? event.actor.sub.startsWith('edge-') : kind === 'team' ? /member|invite/.test(event.action) : kind === 'revocations' ? /ended|revoked/.test(event.action) : true))
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
          id: `inv-${Date.now()}`, identifier: { kind, masked }, level: payload.level, state: 'invited', addedBy: me.staff.display ?? me.staff.sub,
          addedAt: new Date(now).toISOString(), expiresAt: new Date(now + payload.expiresInDays * 86_400_000).toISOString(),
        };
        state.staff = [...state.staff, member];
        state.audit = [{ id: `ev-${now}`, at: member.addedAt, actor: { sub: me.staff.sub, display: me.staff.display }, action: `invited a member (${payload.level})`, target: masked, outcome: 'ok', from: me.edge.id }, ...state.audit];
        await route.fulfill(json(member, 201));
        return;
      }
      const member = id ? state.staff.find(candidate => candidate.id === id) : undefined;
      if (!member) {
        await route.fulfill(json({ message: `Member ${id} was not found` }, 404));
        return;
      }
      if (method === 'PUT') {
        member.level = (body as { level: StaffLevel }).level;
        await route.fulfill(json(member));
        return;
      }
      if (method === 'DELETE') {
        if (member.subject === me.staff.sub) {
          await route.fulfill(json({ message: 'You cannot remove yourself' }, 409));
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

    await route.fulfill(json({ message: `No mock handler for ${method} ${pathname}` }, 404));
  });

  return { state, requests };
}
