import type {
  PasskeyInfo,
  SessionClientEntry,
  User,
  UserRefreshToken,
  UserAgent,
  UserDevice,
  UserRoleAssignment,
  UserSearchField,
  UserSession,
} from '../types';
import { centralResourcePath, resolveBaseUrl } from './central-api';
import { parseUserAuditReport, type UserAuditReport } from './user-audit';

type UserSearchRecordDto = {
  id: string;
  email?: string;
  phone?: string;
  login?: string;
  claims: Record<string, unknown>;
};
type UserSearchResponseDto = { users: UserSearchRecordDto[] };
type CreateUserResponseDto = { id: string };
type UserRolesResponseDto = { roles: string[] };

export const IDENTIFIER_TAKEN_MESSAGE = 'One of the identifiers is already taken by another user.';

export const DEFAULT_TEMPORARY_PASSWORD_TTL_HOURS = 12;

function toUser(record: UserSearchRecordDto): User {
  return {
    id: record.id,
    email: record.email,
    phone: record.phone,
    login: record.login,
    claims: record.claims ?? {},
  };
}

// Shares central-api.ts's centralResourcePath so this switches between
// 'prefix' and 'direct' console modes the same way (see that function's
// comment) -- and the same base URL (respects configureCentralApi / api-url
// attribute).
function proxyUrl(path: string): URL {
  const base = resolveBaseUrl();
  const normalizedBase = base.endsWith('/') ? base : `${base}/`;
  const normalizedPath = path.replace(/^\//, '');
  return new URL(centralResourcePath(normalizedPath), normalizedBase);
}

export async function searchUsers(field: UserSearchField, query: string): Promise<User[]> {
  const trimmed = query.trim();
  if (!trimmed) return [];

  const url = proxyUrl('/users');
  url.searchParams.set(field, trimmed);

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' }, credentials: 'include' });
  if (response.status === 404) return [];
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Search failed (${response.status})`);
  }

  const data = (await response.json()) as UserSearchResponseDto;
  return data.users.map(toUser);
}

export async function createUser(user: Omit<User, 'id'>): Promise<User> {
  const response = await fetch(proxyUrl('/users').toString(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    credentials: 'include',
    body: JSON.stringify({
      email: user.email,
      phone: user.phone,
      login: user.login,
    }),
  });

  if (response.status === 409) {
    throw new Error(IDENTIFIER_TAKEN_MESSAGE);
  }

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Create failed (${response.status})`);
  }

  const { id } = (await response.json()) as CreateUserResponseDto;
  return { ...user, id, claims: user.claims ?? {} };
}

export async function updateUser(previous: User, next: User): Promise<void> {
  const body: Record<string, unknown> = { id: next.id };
  const patchField = (key: 'email' | 'phone' | 'login') => {
    const before = previous[key] ?? undefined;
    const after = next[key] ?? undefined;
    if (before === after) return;
    body[key] = after ?? null;
  };
  patchField('email');
  patchField('phone');
  patchField('login');

  const response = await fetch(proxyUrl('/users').toString(), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    credentials: 'include',
    body: JSON.stringify(body),
  });

  if (response.status === 409) {
    throw new Error(IDENTIFIER_TAKEN_MESSAGE);
  }

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Update failed (${response.status})`);
  }
}

export async function patchUserClaims(userId: string, claimsPatch: Record<string, unknown>): Promise<void> {
  const response = await fetch(proxyUrl('/users/claims').toString(), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    credentials: 'include',
    body: JSON.stringify({
      id: userId,
      claims: claimsPatch,
    }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Update claims failed (${response.status})`);
  }
}

export async function fetchUserRoles(userId: string, tenantId: string): Promise<UserRoleAssignment[]> {
  const url = proxyUrl('/users/roles');
  url.searchParams.set('id', userId);
  url.searchParams.set('tenantId', tenantId);

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' }, credentials: 'include' });
  if (response.status === 204) return [];
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to load roles (${response.status})`);
  }

  const data = (await response.json()) as UserRolesResponseDto;
  return (data.roles ?? []).map(roleId => ({ tenantId, roleId }));
}

export async function updateUserRoles(
  userId: string,
  tenantId: string,
  add: string[],
  remove: string[],
): Promise<void> {
  if (add.length === 0 && remove.length === 0) return;

  const response = await fetch(proxyUrl('/users/roles').toString(), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ userId, tenantId, add, remove }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Update roles failed (${response.status})`);
  }
}

type UserSessionDto = {
  publicId: string;
  clients: SessionClientEntry[];
  platform?: 'ios' | 'android' | 'desktop';
  os?: string;
  browser?: string;
  version?: string;
  createdAt?: string;
  expiresAt?: string;
  idleExpiresAt?: string;
  userAgent?: UserAgent;
};

type UserDeviceDto = {
  userAgent?: UserAgent;
  session?: UserSessionDto | null;
  endedSessionIds?: string[];
};

function toUserSession(dto: UserSessionDto): UserSession {
  return {
    publicId: dto.publicId,
    clients: dto.clients,
    platform: dto.platform,
    os: dto.os,
    browser: dto.browser,
    version: dto.version,
    createdAt: dto.createdAt,
    expiresAt: dto.expiresAt,
    idleExpiresAt: dto.idleExpiresAt,
    userAgent: dto.userAgent,
  };
}

/** The user's remembered devices as auth stores them: each user agent with the session under it,
 *  `session: null` once signed out. Signed-out devices are listed here and not in the session list. */
export async function fetchUserDevices(userId: string): Promise<UserDevice[]> {
  const url = proxyUrl('/users/user-agents');
  url.searchParams.set('id', userId);

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' }, credentials: 'include' });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to load devices (${response.status})`);
  }

  const data = (await response.json()) as UserDeviceDto[];
  return data.map(dto => ({
    userAgent: dto.userAgent,
    session: dto.session ? toUserSession(dto.session) : null,
    endedSessionIds: dto.endedSessionIds ?? [],
  }));
}

/** The offline access clients hold for the user. Independent of the sessions: a refresh
 *  token outlives the session it was issued under, so it is read from here, not from the session list. */
export async function fetchUserRefreshTokens(userId: string): Promise<UserRefreshToken[]> {
  const url = proxyUrl('/users/refresh-tokens');
  url.searchParams.set('id', userId);

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' }, credentials: 'include' });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to load refresh tokens (${response.status})`);
  }

  return (await response.json()) as UserRefreshToken[];
}

/** Hard delete: auth purges the account (sessions, refresh tokens, passkeys, passwords, roles,
 *  throttling, pending conversations) before the index row goes. 404 means the id is unknown. */
export async function deleteUser(userId: string): Promise<void> {
  const url = proxyUrl('/users');
  url.searchParams.set('id', userId);

  const response = await fetch(url.toString(), {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
    credentials: 'include',
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to delete user (${response.status})`);
  }
}

/** Revokes the offline access one client holds for the user: its refresh tokens stop
 *  redeeming, the user stays signed in. Idempotent. */
export async function revokeUserRefreshTokens(userId: string, clientId: string): Promise<void> {
  const url = proxyUrl('/users/refresh-tokens');
  url.searchParams.set('id', userId);
  url.searchParams.set('clientId', clientId);

  const response = await fetch(url.toString(), {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
    credentials: 'include',
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to revoke offline access (${response.status})`);
  }
}

/** Signs one user agent out: ends that session (`publicId` from the session list), relying
 *  parties told; the user's other sessions stay. 404 means it is not the user's session. */
export async function endUserSession(userId: string, sessionId: string): Promise<void> {
  const url = proxyUrl('/users/sessions');
  url.searchParams.set('id', userId);
  url.searchParams.set('sessionId', sessionId);

  const response = await fetch(url.toString(), {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
    credentials: 'include',
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to end session (${response.status})`);
  }
}

/** Forgets a device: its user agent, its session and the refresh tokens bound to that session.
 *  404 means the user agent is not the user's. */
export async function forgetUserAgent(userId: string, userAgentId: string): Promise<void> {
  const url = proxyUrl('/users/user-agents');
  url.searchParams.set('id', userId);
  url.searchParams.set('userAgentId', userAgentId);

  const response = await fetch(url.toString(), {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
    credentials: 'include',
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to forget the device (${response.status})`);
  }
}

export async function invalidateUserSession(userId: string): Promise<void> {
  const url = proxyUrl('/users/sessions');
  url.searchParams.set('userId', userId);

  const response = await fetch(url.toString(), {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
    credentials: 'include',
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to invalidate session (${response.status})`);
  }
}
export async function listPasskeys(userId: string): Promise<PasskeyInfo[]> {
  const url = proxyUrl('/users/passkeys');
  url.searchParams.set('id', userId);

  const response = await fetch(url.toString(), {
    headers: { Accept: 'application/json' },
    credentials: 'include',
  });
  if (response.status === 204) return [];
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to load passkeys (${response.status})`);
  }

  const data = (await response.json()) as { passkeys: PasskeyInfo[] };
  return data.passkeys ?? [];
}

export async function renamePasskey(userId: string, credentialId: string, name: string | null): Promise<void> {
  const response = await fetch(proxyUrl('/users/passkeys').toString(), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ userId, credentialId, name }),
    credentials: 'include',
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Rename passkey failed (${response.status})`);
  }
}

export async function deletePasskey(userId: string, credentialId: string): Promise<void> {
  const url = proxyUrl('/users/passkeys');
  url.searchParams.set('id', userId);
  url.searchParams.set('credentialId', credentialId);

  const response = await fetch(url.toString(), {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
    credentials: 'include',
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Delete passkey failed (${response.status})`);
  }
}

/** Returns the plaintext temporary password for the `show` channel (non-prod only), null otherwise. */
export async function resetPassword(
  userId: string,
  channel?: string,
  expiresInSeconds = DEFAULT_TEMPORARY_PASSWORD_TTL_HOURS * 60 * 60,
): Promise<string | null> {
  const response = await fetch(proxyUrl('/users/password/reset').toString(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({
      userId,
      channel: channel ?? null,
      expiresInSeconds,
    }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Reset password failed (${response.status})`);
  }

  if (response.status === 204) return null;
  const body = await response.json() as { password: string };
  return body.password;
}

export async function resetUserLimits(
  userId: string,
  tenantId: string,
  email: string | undefined,
  phone: string | undefined,
): Promise<void> {
  const response = await fetch(proxyUrl('/users/limits/reset').toString(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ userId, tenantId, email: email ?? null, phone: phone ?? null }),
    credentials: 'include',
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Reset limits failed (${response.status})`);
  }
}

/** A failed `GET /users/audit`: `status` 400 is a window or cursor the server refused, 503 a busy report. */
export class UserAuditError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'UserAuditError';
  }
}

export type UserAuditQuery = { from?: string; to?: string; cursor?: string; limit?: number };

/** One page of the user's audit report (`audit:read`): the events of auth and of central about the user, newest
 *  first. `from` and `to` are ISO instants and are sent unchanged with every page of a walk; the first page (no
 *  `cursor`) also carries the summary. */
export async function fetchUserAudit(userId: string, query: UserAuditQuery = {}, signal?: AbortSignal): Promise<UserAuditReport> {
  const url = proxyUrl('/users/audit');
  url.searchParams.set('id', userId);
  if (query.from) url.searchParams.set('from', query.from);
  if (query.to) url.searchParams.set('to', query.to);
  if (query.cursor) url.searchParams.set('cursor', query.cursor);
  if (query.limit) url.searchParams.set('limit', String(query.limit));

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' }, credentials: 'include', signal });
  if (!response.ok) {
    const body = (await response.text()).trim();
    const fallback = response.status === 403 ? 'You do not have permission to read the audit log.'
      : response.status === 503 ? 'Too many reports are running. Try again in a moment.'
      : `Failed to load activity (${response.status})`;
    throw new UserAuditError(body || fallback, response.status);
  }
  return parseUserAuditReport(await response.json());
}

/** The calling administrator's own preferences (`GET /users/preferences`): the `zoneinfo` of their profile, `null` when none is stored. */
export type MyPreferences = { zoneinfo: string | null };

export async function fetchMyPreferences(): Promise<MyPreferences> {
  const response = await fetch(proxyUrl('/users/preferences').toString(), {
    headers: { Accept: 'application/json' },
    credentials: 'include',
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to load your preferences (${response.status})`);
  }
  const data = (await response.json()) as { zoneinfo?: unknown };
  return { zoneinfo: typeof data.zoneinfo === 'string' ? data.zoneinfo : null };
}

/** Sets the time zone of the caller's own profile, or clears it with `null`. Central refuses a name that is not in the time zone database (400). */
export async function saveMyZoneinfo(zoneinfo: string | null): Promise<void> {
  const response = await fetch(proxyUrl('/users/preferences').toString(), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ zoneinfo }),
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to save the time zone (${response.status})`);
  }
}
