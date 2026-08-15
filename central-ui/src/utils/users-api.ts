import type { PasskeyInfo, SessionClientEntry, User, UserRoleAssignment, UserSearchField, UserSession } from '../types';
import { CONSOLE_PREFIX, resolveBaseUrl } from './central-api';

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

function toUser(record: UserSearchRecordDto): User {
  return {
    id: record.id,
    email: record.email,
    phone: record.phone,
    login: record.login,
    claims: record.claims ?? {},
  };
}

// Route through the console's /central prefix (see CONSOLE_PREFIX in
// central-api.ts) rather than calling edge's resources/central/ route
// directly, so this shares the EDGE_SESSION cookie's path scope with the rest
// of the console. Uses the same base URL as central-api.ts (respects
// configureCentralApi / api-url attribute).
function proxyUrl(path: string): URL {
  const base = resolveBaseUrl();
  const normalizedBase = base.endsWith('/') ? base : `${base}/`;
  const normalizedPath = path.replace(/^\//, '');
  return new URL(`${CONSOLE_PREFIX}/${normalizedPath}`, normalizedBase);
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
    throw new Error('User with this email, phone or login already exists');
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
  clients: SessionClientEntry[];
  platform?: 'ios' | 'android' | 'desktop';
  os?: string;
  browser?: string;
  version?: string;
  createdAt?: string;
};

export async function fetchUserSessions(userId: string): Promise<UserSession[]> {
  const url = proxyUrl('/users/sessions');
  url.searchParams.set('id', userId);

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' }, credentials: 'include' });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to load sessions (${response.status})`);
  }

  const data = (await response.json()) as UserSessionDto[];
  return data.map(dto => ({
    clients: dto.clients,
    platform: dto.platform,
    os: dto.os,
    browser: dto.browser,
    version: dto.version,
    createdAt: dto.createdAt,
  }));
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
  expiresInSeconds?: number,
): Promise<string | null> {
  const response = await fetch(proxyUrl('/users/password/reset').toString(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({
      userId,
      channel: channel ?? null,
      expiresInSeconds: expiresInSeconds ?? null,
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
