import type { OAuthScope, User, UserRoleAssignment, UserSearchField } from '../types';
import { mockScopes } from './mock-data';

const OMITTED_SCOPES = new Set(['offline_access']);
const OMITTED_CLAIMS = new Set(['sub']);

// User search and creation are wired to the central backend.
// Role assignments and full claim/role lookup remain mock-backed until the
// corresponding backend endpoints are exposed through the admin gateway.

const mockUsers: User[] = [
  {
    id: '11111111-0000-0000-0000-000000000001',
    email: 'alice@example.com',
    phone: '+15551111111',
    login: 'alice',
    claims: { given_name: 'Alice', family_name: 'Anderson', locale: 'en-US', zoneinfo: 'America/New_York', email_verified: true },
  },
  {
    id: '11111111-0000-0000-0000-000000000002',
    email: 'bob@example.com',
    login: 'bob',
    claims: { given_name: 'Bob', family_name: 'Brown', locale: 'en-GB', email_verified: false },
  },
  {
    id: '11111111-0000-0000-0000-000000000003',
    email: 'carol@example.com',
    phone: '+15552222222',
    claims: { given_name: 'Carol', locale: 'fr-FR', phone_number_verified: true },
  },
  {
    id: '11111111-0000-0000-0000-000000000004',
    phone: '+15553333333',
    login: 'dave',
    claims: {},
  },
  {
    id: '11111111-0000-0000-0000-000000000005',
    email: 'eve@example.com',
    phone: '+15554444444',
    login: 'eve',
    claims: { given_name: 'Eve', family_name: 'Evans', locale: 'de-DE', zoneinfo: 'Europe/Berlin', email_verified: true, phone_number_verified: true },
  },
  {
    id: '11111111-0000-0000-0000-000000000006',
    email: 'frank@example.com',
    login: 'frank',
    claims: { given_name: 'Frank', family_name: 'Foster', locale: 'en-US', email_verified: true },
  },
  {
    id: '11111111-0000-0000-0000-000000000007',
    email: 'grace@example.com',
    phone: '+15555555555',
    login: 'grace',
    claims: { given_name: 'Grace', family_name: 'Green', locale: 'es-ES', zoneinfo: 'Europe/Madrid' },
  },
  {
    id: '11111111-0000-0000-0000-000000000008',
    email: 'henry@example.com',
    login: 'henry',
    claims: { given_name: 'Henry', family_name: 'Hall', locale: 'en-AU', email_verified: true },
  },
  {
    id: '11111111-0000-0000-0000-000000000009',
    phone: '+15556666666',
    login: 'iris',
    claims: { given_name: 'Iris', phone_number_verified: false },
  },
  {
    id: '11111111-0000-0000-0000-000000000010',
    email: 'jack@example.com',
    phone: '+15557777777',
    claims: { given_name: 'Jack', family_name: 'Johnson', locale: 'en-US', email_verified: false, phone_number_verified: true },
  },
  {
    id: '11111111-0000-0000-0000-000000000011',
    email: 'kate@example.com',
    login: 'kate',
    claims: { given_name: 'Kate', family_name: 'King', locale: 'en-CA', zoneinfo: 'America/Toronto', email_verified: true },
  },
  {
    id: '11111111-0000-0000-0000-000000000012',
    email: 'leo@example.com',
    phone: '+15558888888',
    login: 'leo',
    claims: { given_name: 'Leo', family_name: 'Lane', locale: 'it-IT', zoneinfo: 'Europe/Rome', email_verified: true },
  },
];

const mockRoleAssignments: Record<string, UserRoleAssignment[]> = {
  '11111111-0000-0000-0000-000000000001': [
    { tenantId: 'acme-corp', roleId: 'admin' },
    { tenantId: 'globex', roleId: 'user' },
  ],
  '11111111-0000-0000-0000-000000000002': [
    { tenantId: 'acme-corp', roleId: 'support' },
  ],
  '11111111-0000-0000-0000-000000000003': [
    { tenantId: 'globex', roleId: 'readonly' },
  ],
  '11111111-0000-0000-0000-000000000004': [],
  '11111111-0000-0000-0000-000000000005': [
    { tenantId: 'acme-corp', roleId: 'user' },
    { tenantId: 'globex', roleId: 'admin' },
  ],
  '11111111-0000-0000-0000-000000000006': [
    { tenantId: 'acme-corp', roleId: 'readonly' },
  ],
  '11111111-0000-0000-0000-000000000007': [],
  '11111111-0000-0000-0000-000000000008': [
    { tenantId: 'globex', roleId: 'support' },
  ],
  '11111111-0000-0000-0000-000000000009': [],
  '11111111-0000-0000-0000-000000000010': [
    { tenantId: 'acme-corp', roleId: 'user' },
  ],
  '11111111-0000-0000-0000-000000000011': [],
  '11111111-0000-0000-0000-000000000012': [
    { tenantId: 'acme-corp', roleId: 'admin' },
    { tenantId: 'globex', roleId: 'user' },
  ],
};

const ARTIFICIAL_DELAY_MS = 120;

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function delay<T>(value: T): Promise<T> {
  return new Promise(resolve => setTimeout(() => resolve(value), ARTIFICIAL_DELAY_MS));
}

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
      claims: user.claims ?? {},
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
  if (!claimsEqual(previous.claims ?? {}, next.claims ?? {})) {
    body.claims = next.claims ?? {};
  }

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

function claimsEqual(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const key of keys) {
    if (JSON.stringify(a[key]) !== JSON.stringify(b[key])) return false;
  }
  return true;
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

export async function fetchAvailableScopes(): Promise<OAuthScope[]> {
  const filtered = mockScopes
    .filter(scope => !OMITTED_SCOPES.has(scope.id))
    .map(scope => ({
      ...scope,
      claims: scope.claims.filter(claim => !OMITTED_CLAIMS.has(claim.id)),
    }))
    .filter(scope => scope.claims.length > 0);
  return delay(clone(filtered));
}

export async function assignUserRole(userId: string, tenantId: string, roleId: string): Promise<void> {
  const list = mockRoleAssignments[userId] ?? [];
  if (list.some(assignment => assignment.tenantId === tenantId && assignment.roleId === roleId)) {
    return;
  }
  mockRoleAssignments[userId] = [...list, { tenantId, roleId }];
  await delay(undefined);
}

export async function removeUserRole(userId: string, tenantId: string, roleId: string): Promise<void> {
  const list = mockRoleAssignments[userId] ?? [];
  mockRoleAssignments[userId] = list.filter(
    assignment => !(assignment.tenantId === tenantId && assignment.roleId === roleId),
  );
  await delay(undefined);
}
