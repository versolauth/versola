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
    operator: { name: string; issuer: string; mtlsUrl: string; centralMtlsUrl: string };
  };
  staff: { sub: string; display?: string; level: StaffLevel };
  edge: { id: string; publicUrl: string; central: 'ok' | 'unavailable' };
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
  requestedAt?: string;
  note?: string;
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
  postLogoutRedirectUris?: string[];
  scope: string[];
  accessTokenTtl: number;
  refreshTokenTtl: number;
  backChannelLogoutUri?: string | null;
  /** `/login/<presetId>` on the partner's own edge: the edge keeps one auth-request preset per partner client; the console prefixes the edge's public URL. */
  loginPath: string;
  createdAt: string;
  approval: PartnerClientApproval;
  fixed: PartnerClientFixed;
};

export type PartnerClientWrite = Omit<PartnerClient, 'createdAt' | 'approval' | 'fixed' | 'loginPath'> & { authMethod?: PartnerClientAuthMethod };

/** `ResourceDto` without `internal`: a partner resource always carries a secret the edge authenticates to the upstream with. */
export type PartnerResource = {
  resourceId: string;
  resource: string;
  audience: string[];
  endpoints: ResourceEndpoint[];
  secretRotation: boolean;
  /** When the secret was last rotated; absent when never. */
  secretRotatedAt?: string;
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
  replicas: number;
  /** The operator's policies for the group, read-only. */
  policies: { overload: Record<string, unknown>; accessLog: Record<string, unknown>; rateLimits: Record<string, unknown> };
  /** An enrollment token that was issued and not used yet; the token itself was shown once. */
  pendingToken?: { expiresAt: string };
};

export type EnrollmentToken = { token: string; bundle: string; expiresAt: string };

/** `POST /partner/api/edges`: the edge and its first enrollment token in one write. */
export type CreatedEdge = EnrollmentToken & { edge: PartnerEdge; signingKey: SigningKey };

/** The edge's signing key, created with it and shown once: the private half is never kept by the operator. */
export type SigningKey = { keyId: string; privateKey: string };

/** What a partner may end on its own edges: the sessions of a user, one session, every session of a client, or one refresh-token family (`fam_id`). The tokens live in the edge's sessions; auth is never contacted. */
export type RevocationBy = 'user' | 'sid' | 'client' | 'fam';

export type RevocationRequest = { by: RevocationBy; value: string; clients: string[]; edges: string[] };

export type Revocation = RevocationRequest & { id: string; at: string; by_staff: string; ended: number };

export type AuditActor = { sub: string; display?: string; byOperator?: true };

export type AuditEventRecord = { id: string; at: string; actor: AuditActor; action: string; target: string; outcome: 'ok' | 'refused' | 'failed'; from?: string };

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
  lastSignInAt?: string;
};

export type StaffInvite = { identifier: string; level: StaffLevel; expiresInDays: number };

export type PartnerSettings = {
  contact: { email: string; phone?: string | null };
  defaults: { backChannelLogoutUri: string; accessTokenTtl: number; refreshTokenTtl: number };
};

export type ProfileChange = { displayName: string; logoUri: string | null };

export type ProfileApproval = { id: string; status: 'waiting'; requestedAt: string; requested: ProfileChange };

export type PartnerErrorCode = 'outside_limits' | 'needs_approval' | 'partner_suspended' | 'not_a_member' | 'central_unavailable';
