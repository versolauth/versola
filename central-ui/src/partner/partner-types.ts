/**
 * The partner API (#549, #963): what the partner edge serves at `/partner/api/*` to the partner
 * console. These are the DTOs of the contract the edge (P1, #564) implements; the operator's
 * `central-api.ts` is never imported here (a partner bundle must not contain it).
 */
import type { PartnerLimits, PartnerStatus, ResourceEndpoint } from '../types';

export type StaffLevel = 'admin' | 'developer' | 'viewer';

export type PartnerMe = {
  partner: {
    id: string;
    displayName: string;
    logoUri?: string | null;
    status: PartnerStatus;
    limits: PartnerLimits;
    reviewAt?: string | null;
    /** The operator's authorization server and mutual-TLS addresses: the edge may not know them (`null`) while central has not been read. */
    operator: { name: string; issuer: string | null; mtlsUrl: string | null; centralMtlsUrl: string | null };
  };
  staff: { sub: string; display?: string; level: StaffLevel };
  edge: { id: string; publicUrl: string | null; central: 'ok' | 'unavailable' };
  /** The partner's own console client and its auth-request preset. Sign-in does not need it (the edge serves `/partner/login`), so it is only for the record. */
  console: { clientId: string; presetId: string };
};

export type PartnerClientKind = 'web' | 'native';
export type PartnerClientAuthMethod = 'tls_client_auth' | 'private_key_jwt';

export type PartnerClientApproval = {
  /** Whether the request is the client's creation or a change to an approved client: the console never infers it from `waitingFields`. */
  kind: 'create' | 'change';
  status: 'pending' | 'approved' | 'rejected';
  /** The fields that wait for the operator. */
  waitingFields: string[];
  requestedAt?: string | null;
  note?: string | null;
};

export type PartnerClientFixed = {
  consent: true;
  flow: 'code+pkce+par';
  authMethod: PartnerClientAuthMethod;
  group: string;
};

/** A client as the partner sees it: the fields it may read and change, what waits for the operator, what is fixed. */
export type PartnerClient = {
  id: string;
  kind: PartnerClientKind;
  clientName: string;
  logoUri?: string | null;
  policyUri?: string | null;
  tosUri?: string | null;
  redirectUris: string[];
  scope: string[];
  accessTokenTtl: number;
  refreshTokenTtl: number | null;
  backChannelLogoutUri?: string | null;
  /** What the client authenticates with; `fixed.authMethod` says the same for every client of the group. */
  authMethod?: PartnerClientAuthMethod;
  /** What the operator lets the partner have, as it holds it for this client. */
  limits: PartnerLimits;
  /** `/login/<presetId>` on the partner's own edge: the edge keeps one auth-request preset per partner client; the console prefixes the edge's public URL. */
  loginPath: string;
  createdAt: string;
  approval: PartnerClientApproval;
  fixed: PartnerClientFixed;
};

export type PartnerClientWrite = Omit<PartnerClient, 'createdAt' | 'approval' | 'fixed' | 'loginPath' | 'limits' | 'consentFlow'> & { authMethod?: PartnerClientAuthMethod };

/** `ResourceDto` without `internal`: a partner resource always carries a secret the edge authenticates to the upstream with. */
export type PartnerResource = {
  resourceId: string;
  resource: string;
  audience: string[];
  endpoints: ResourceEndpoint[];
  secretRotation: boolean;
};

export type PartnerResourceWrite = {
  resourceId: string;
  resource: string;
  audience: string[];
  endpoints?: ResourceEndpoint[];
};

export type PartnerEdgeStatus = 'waiting' | 'enrolled' | 'suspended';

export type PartnerEdge = {
  id: string;
  publicUrl: string;
  status: PartnerEdgeStatus;
  certificateNotAfter?: string;
  lastRenewedAt?: string;
  /** Always 0: central does not count the replicas behind an edge, so the console shows none. */
  replicas: number;
  /** The operator's policies for the group, read-only. */
  policies: { overload: Record<string, unknown>; accessLog: Record<string, unknown>; rateLimits: Record<string, unknown> };
};

/** `vtk1.<base64url(enroll address)>.<secret>`: registering an edge is this one string, shown once. The edge makes its own keys. */
export type EnrollmentToken = { token: string; expiresAt: string | null };

/** `POST /partner/api/edges`: the edge and its first enrollment token in one write. */
export type CreatedEdge = EnrollmentToken & { edge: PartnerEdge };

/** What a partner may end on the edge that serves its console: the sessions of a user, one session, or one refresh-token family (`fam_id`). The tokens live in the edge's sessions; auth is never contacted. */
export type RevocationBy = 'user' | 'sid' | 'fam';

/** The edge ignores `clients` and `edges` if they are sent: it ends sessions of its own store only. */
export type RevocationRequest = { by: RevocationBy; value: string };

/** `clients` and `edges` are always empty lists on the real edge; `by` may be empty on rows an older edge recorded. */
export type Revocation = RevocationRequest & { id: string; at: string; by_staff: string; clients: string[]; edges: string[]; ended: number };

/** `sub` is the staff member's, `operator` for the operator's own actions; `display` is the name the edge found for it. */
export type AuditActor = { sub: string; display?: string; byOperator?: true };

/** `kind` is what the event is about (`clients.create`, `team.invite`, `security.refused`); `action` says it in words; the team's own events are included. */
export type AuditEventRecord = { id: string; at: string; kind: string; actor: AuditActor; action: string; target: string; outcome: 'ok' | 'refused' | 'failed'; from?: string };

export type AuditQuery = { cursor?: string | null; actor?: string; kind?: string; q?: string };

export type AuditPage = { events: AuditEventRecord[]; next?: string | null };

export type StaffMember = {
  /** The row's own id: the `sub` of a member, the invite's id while it is an invite. */
  id: string;
  subject?: string;
  identifier?: { kind: 'phone' | 'email'; masked: string };
  display?: string;
  level: StaffLevel;
  state: 'member' | 'invited';
  addedBy: string;
  addedAt: string;
  expiresAt?: string;
};

export type StaffInvite = { identifier: string; level: StaffLevel; expiresInDays: number };

export type PartnerSettings = {
  contact: { email: string; phone?: string | null };
  /** What the console offers for a new client; every part may be absent (a fresh partner has none). Seconds for the lifetimes. */
  defaults: { backChannelLogoutUri?: string | null; accessTokenTtl?: number | null; refreshTokenTtl?: number | null };
};

export type ProfileChange = { displayName: string; logoUri: string | null };

export type ProfileApproval = { id: string; status: 'waiting'; requestedAt: string; requested: ProfileChange };

/** The `error` codes of the edge's contract (`edge/open-api/partner-api.yaml`). `central_answered_<status>` is any other status central answered. */
export type PartnerErrorCode =
  | 'invalid_request' | 'unauthenticated' | 'not_a_member' | 'insufficient_level' | 'origin_mismatch' | 'partner_suspended' | 'partner_principal_mismatch'
  | 'not_found' | 'conflict' | 'not_supported' | 'central_unreadable' | 'central_unavailable' | 'internal_error' | `central_answered_${number}`;
