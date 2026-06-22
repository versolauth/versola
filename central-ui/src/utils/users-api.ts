import type { User, UserRoleAssignment, UserSearchField, UserSession } from '../types';
import { parseUserAgent } from './ua-parser';

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

export async function searchUsers(field: UserSearchField, query: string): Promise<User[]> {
  const trimmed = query.trim();
  if (!trimmed) return [];

  const url = new URL('/users', window.location.origin);
  url.searchParams.set(field, trimmed);

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' } });
  if (response.status === 404) return [];
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Search failed (${response.status})`);
  }

  const data = (await response.json()) as UserSearchResponseDto;
  return data.users.map(toUser);
}

export async function createUser(user: Omit<User, 'id'>): Promise<User> {
  const response = await fetch('/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
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

  const response = await fetch('/users', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Update failed (${response.status})`);
  }
}

export async function patchUserClaims(userId: string, claimsPatch: Record<string, unknown>): Promise<void> {
  const response = await fetch('/users/claims', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
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
  const url = new URL('/users/roles', window.location.origin);
  url.searchParams.set('id', userId);
  url.searchParams.set('tenantId', tenantId);

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' } });
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

  const response = await fetch('/users/roles', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ userId, tenantId, add, remove }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Update roles failed (${response.status})`);
  }
}

type UserSessionDto = {
  clientId: string;
  userAgent?: string;
  createdAt?: string;
};

export async function fetchUserSessions(userId: string): Promise<UserSession[]> {
  const url = new URL('/users/sessions', window.location.origin);
  url.searchParams.set('id', userId);

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to load sessions (${response.status})`);
  }

  const data = (await response.json()) as UserSessionDto[];
  return data.map(dto => ({
    clientId: dto.clientId,
    userAgent: dto.userAgent,
    createdAt: dto.createdAt,
    ...parseUserAgent(dto.userAgent),
  }));
}

export async function invalidateUserSession(userId: string): Promise<void> {
  const url = new URL('/users/sessions', window.location.origin);
  url.searchParams.set('userId', userId);

  const response = await fetch(url.toString(), {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body.trim() || `Failed to invalidate session (${response.status})`);
  }
}
export async function resetUserLimits(
  userId: string,
  tenantId: string,
  email: string | undefined,
  phone: string | undefined,
): Promise<void> {
  const response = await fetch('/users/limits/reset', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ userId, tenantId, email: email ?? null, phone: phone ?? null }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text.trim() || `Reset limits failed (${response.status})`);
  }
}
