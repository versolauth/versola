/**
 * The partner console's API client: the partner edge's own contract at `/partner/api/*`, on the
 * edge's cookie session. Nothing here reaches central: the edge forwards what it may, scoped to
 * the partner its certificate names (#549 decision 5).
 */
import type {
  AuditPage, AuditQuery, CreatedEdge, EnrollmentToken, PartnerClient, PartnerClientWrite, PartnerEdge, PartnerErrorCode, PartnerMe, PartnerResource, PartnerResourceWrite,
  PartnerSettings, ProfileApproval, ProfileChange, Revocation, RevocationRequest, StaffInvite, StaffLevel, StaffMember,
} from './partner-types';

export const PARTNER_API_BASE = '/partner/api';

/** The edge's fixed console paths: they redirect to `/login/<console preset>` and `/logout/<console preset>`. The edge has no `/login` or `/logout` of its own. */
export const PARTNER_LOGIN_PATH = '/partner/login';
export const PARTNER_LOGOUT_PATH = '/partner/logout';

/** What the edge answered with: the HTTP status, the error code of the contract when it sent one, and the fields it named. */
export class PartnerApiError extends Error {
  constructor(readonly status: number, readonly code: PartnerErrorCode | null, message: string, readonly fields: string[] = [], readonly identifier?: string, readonly partner?: string) {
    super(message);
    this.name = 'PartnerApiError';
  }
}

export function isPartnerApiError(error: unknown, code?: PartnerErrorCode): error is PartnerApiError {
  return error instanceof PartnerApiError && (code === undefined || error.code === code);
}

/** What to tell the staff member about a failed write, in the console's words. */
export function writeErrorMessage(error: unknown, fallback = 'The change was not saved.'): string {
  if (isPartnerApiError(error, 'central_unavailable')) {
    return 'Central is unavailable right now, so nothing was saved. Your edges keep serving from their last snapshot; try again in a moment.';
  }
  if (isPartnerApiError(error, 'partner_suspended')) {
    return 'The operator has suspended your organisation: nothing can be changed until it resumes you.';
  }
  if (isPartnerApiError(error, 'outside_limits')) {
    return `Outside your limits: ${error.fields.join(', ') || 'one of the fields'}. Ask the operator to change the limits, or change the request.`;
  }
  if (isPartnerApiError(error, 'needs_approval')) {
    return `This needs the operator's approval: ${error.fields.join(', ') || 'the change'}.`;
  }
  return error instanceof Error && error.message ? error.message : fallback;
}

type Query = Record<string, string | number | undefined | null>;

async function request<T>(path: string, options: { method?: string; query?: Query; body?: unknown } = {}): Promise<T> {
  const url = new URL(`${PARTNER_API_BASE}${path}`, window.location.origin);
  for (const [key, value] of Object.entries(options.query ?? {})) {
    if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value));
  }
  const response = await fetch(url.toString(), {
    method: options.method ?? 'GET',
    headers: { Accept: 'application/json', ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
    credentials: 'include',
  });
  if (response.status === 401) {
    // No session on the edge: sign in again through the console's login path, and fail the call so nothing waits on it.
    window.location.assign(PARTNER_LOGIN_PATH);
    throw new PartnerApiError(401, null, 'Your session ended. Signing you in again…');
  }
  const text = await response.text();
  const parsed = text ? tryParse(text) : undefined;
  if (!response.ok) {
    const body = (parsed ?? {}) as { error?: PartnerErrorCode; message?: string; fields?: string[]; identifier?: string; partner?: string };
    throw new PartnerApiError(response.status, body.error ?? null, body.message ?? body.error ?? text ?? `Request failed (${response.status})`, body.fields ?? [], body.identifier, body.partner);
  }
  return parsed as T;
}

function tryParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export const fetchMe = () => request<PartnerMe>('/me');

export const fetchClients = () => request<{ clients: PartnerClient[] }>('/clients').then(response => response.clients);
export const createClient = (client: PartnerClientWrite) => request<PartnerClient>('/clients', { method: 'POST', body: client });
export const updateClient = (client: PartnerClientWrite) => request<PartnerClient>(`/clients/${encodeURIComponent(client.id)}`, { method: 'PUT', body: client });
export const deleteClient = (id: string) => request<void>(`/clients/${encodeURIComponent(id)}`, { method: 'DELETE' });
export const rotateClientKeys = (id: string) => request<{ jwksUri?: string; rotatedAt: string }>(`/clients/${encodeURIComponent(id)}/rotate-secret`, { method: 'POST' });
export const withdrawClient = (id: string) => request<PartnerClient>(`/clients/${encodeURIComponent(id)}/withdraw`, { method: 'POST' });

export const fetchResources = () => request<{ resources: PartnerResource[] }>('/resources').then(response => response.resources);
export const createResource = (resource: PartnerResourceWrite) => request<{ resource: PartnerResource; secret: string }>('/resources', { method: 'POST', body: resource });
export const updateResource = (resource: PartnerResourceWrite) => request<PartnerResource>(`/resources/${encodeURIComponent(resource.resourceId)}`, { method: 'PUT', body: resource });
export const deleteResource = (id: string) => request<void>(`/resources/${encodeURIComponent(id)}`, { method: 'DELETE' });
export const rotateResourceSecret = (id: string) => request<{ secret: string }>(`/resources/${encodeURIComponent(id)}/rotate-secret`, { method: 'POST' });

export const fetchEdges = () => request<{ edges: PartnerEdge[] }>('/edges').then(response => response.edges);
export const createEdge = (edge: { id: string; publicUrl: string }) => request<CreatedEdge>('/edges', { method: 'POST', body: edge });
export const deleteEdge = (id: string) => request<void>(`/edges/${encodeURIComponent(id)}`, { method: 'DELETE' });
export const issueEnrollmentToken = (edgeId: string) => request<EnrollmentToken>(`/edges/${encodeURIComponent(edgeId)}/enrollment-tokens`, { method: 'POST' });
export const revokeEnrollmentToken = (edgeId: string) => request<void>(`/edges/${encodeURIComponent(edgeId)}/enrollment-tokens`, { method: 'DELETE' });

export const revoke = (body: RevocationRequest) => request<{ ended: number }>('/revocations', { method: 'POST', body });
export const fetchRevocations = () => request<{ revocations: Revocation[] }>('/revocations').then(response => response.revocations);

export const fetchAudit = (query: AuditQuery) => request<AuditPage>('/audit', { query });

export const fetchStaff = () => request<{ staff: StaffMember[] }>('/staff').then(response => response.staff);
export const inviteStaff = (invite: StaffInvite) => request<StaffMember>('/staff', { method: 'POST', body: invite });
export const updateStaffLevel = (id: string, level: StaffLevel) => request<StaffMember>(`/staff/${encodeURIComponent(id)}`, { method: 'PUT', body: { level } });
export const removeStaff = (id: string) => request<void>(`/staff/${encodeURIComponent(id)}`, { method: 'DELETE' });

export const fetchSettings = () => request<PartnerSettings>('/settings');
export const updateSettings = (settings: PartnerSettings) => request<void>('/settings', { method: 'PUT', body: settings });
export const fetchProfileApproval = () => request<{ approval: ProfileApproval | null }>('/profile').then(response => response.approval);
export const requestProfileChange = (change: ProfileChange) => request<{ approval: ProfileApproval }>('/profile', { method: 'PUT', body: change });
