import type {
  AclRuleGroup,
  AuthorizationPreset,
  Edge,
  OAuthClaim,
  OAuthClient,
  OAuthScope,
  Permission,
  Resource,
  ResourceEndpoint,
  ResourceEndpointId,
  Role,
  ServiceKey,
  Tenant,
} from '../types';

export const DEFAULT_PAGE_SIZE = 30;
const READ_CACHE_TTL_MS = 60_000;

type LocalizedDescription = Record<string, string>;
type PagedResult<T> = { items: T[]; total: number; hasNext: boolean };
type QueryValue = string | number | undefined | null;
type CentralApiConfig = { baseUrl: string | null; authToken: string | null };

type ClientSecretResponse = { secret: string };
type AuthorizationPresetResponse = {
  id: string;
  clientId: string;
  description: string;
  redirectUri: string;
  scope: string[];
  responseType: string;
  uiLocales?: string[];
  customParameters?: Record<string, string[]>;
};
type CreateResourceResponse = { id: number };
type CreateResourceEndpointResponse = { id: ResourceEndpointId };
type CreateResourceEndpointPayload = Omit<ResourceEndpoint, 'id'>;
type SaveResourceEndpointPayload = CreateResourceEndpointPayload & { id?: ResourceEndpointId };
type ResourceEndpointWriteDto = {
  id?: string | number;
  method: string;
  path: string;
  fetchUserInfo: boolean;
  allowRules: AclRuleGroup;
  denyRules: AclRuleGroup;
  injectHeaders: Record<string, string>;
};
type ResourceEndpointDto = {
  id?: ResourceEndpointId;
  method: string;
  path: string;
  fetchUserInfo: boolean;
  allowRules: AclRuleGroup;
  denyRules: AclRuleGroup;
  injectHeaders: Record<string, string>;
};
type ResourceResponseDto = { id: number; resource: string; endpoints: Array<ResourceEndpointDto & { id: ResourceEndpointId }> };

type EdgeResponseDto = { id: string; hasOldKey?: boolean };
type EdgesResponse = { edges: EdgeResponseDto[] };
type ServiceKeyResponseDto = { keyId: string; privateKey: string };

type TenantsResponse = { tenants: Array<{ id: string; description: string; edgeId?: string | null }> };
type PermissionsResponse = { permissions: Array<{ permission: string; description: LocalizedDescription; endpointIds: ResourceEndpointId[] }> };
type ScopesResponse = { scopes: Array<{ scope: string; description: LocalizedDescription; claims: Array<{ claim: string; description: LocalizedDescription }> }> };
type ClientsResponse = { clients: Array<{ id: string; clientName: string; redirectUris: string[]; scope: string[]; permissions: string[]; secretRotation: boolean }> };
type RolesResponse = { roles: Array<{ id: string; description: LocalizedDescription; permissions: string[]; active: boolean }> };
type ResourcesResponse = { resources: ResourceResponseDto[] };

const apiConfig: CentralApiConfig = { baseUrl: null, authToken: null };
const permissionStore = new Map<string, Permission>();
const clientSupplementStore = new Map<string, { externalAudience: string[]; accessTokenTtl: number; hasPreviousSecret: boolean }>();
const roleSupplementStore = new Map<string, { active: boolean; createdAt: string; updatedAt: string }>();
const readCache = new Map<string, { expiresAt: number; value: unknown }>();
const inFlightReads = new Map<string, Promise<unknown>>();

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function entityKey(tenantId: string, id: string): string {
  return `${tenantId}::${id}`;
}

function resolveBaseUrl(): string {
  return apiConfig.baseUrl?.trim() || window.location.origin;
}

function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const baseUrl = resolveBaseUrl();
  const normalizedBase = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  const normalizedPath = path.replace(/^\//, '');
  const url = new URL(normalizedPath, normalizedBase);
  Object.entries(query ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null) {
      url.searchParams.set(key, String(value));
    }
  });
  return url.toString();
}

function invalidateReadCache() {
  readCache.clear();
  inFlightReads.clear();
}

function buildErrorMessage(status: number, bodyText: string): string {
  const trimmed = bodyText.trim();

  if (!trimmed) {
    return `Request failed (${status})`;
  }

  try {
    const parsed = JSON.parse(trimmed) as { message?: string; error?: string };
    return parsed.message || parsed.error || `Request failed (${status})`;
  } catch {
    return `${trimmed} (${status})`;
  }
}

export function configureCentralApi(config: Partial<CentralApiConfig>): void {
  apiConfig.baseUrl = config.baseUrl?.trim() || null;
  apiConfig.authToken = config.authToken?.trim() || null;
  invalidateReadCache();
}

async function request<T>(
  path: string,
  options: { method?: string; query?: Record<string, QueryValue>; body?: unknown } = {},
): Promise<T> {
  const method = options.method ?? 'GET';
  const url = buildUrl(path, options.query);
  const useReadCache = method === 'GET' && !options.body;

  if (useReadCache) {
    const cached = readCache.get(url);
    if (cached && cached.expiresAt > Date.now()) {
      return clone(cached.value as T);
    }

    const pending = inFlightReads.get(url);
    if (pending) {
      return clone(await pending as T);
    }
  }

  const requestPromise = (async () => {
    const authToken = apiConfig.authToken?.trim();
    const response = await fetch(url, {
      method,
      headers: {
        Accept: 'application/json',
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
        ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      },
      ...(options.body ? { body: JSON.stringify(options.body) } : {}),
    });

    if (!response.ok) {
      throw new Error(buildErrorMessage(response.status, await response.text()));
    }

    if (response.status === 204) {
      return undefined as T;
    }

    const bodyText = await response.text();
    if (!bodyText.trim()) {
      return undefined as T;
    }

    return JSON.parse(bodyText) as T;
  })();

  if (useReadCache) {
    inFlightReads.set(url, requestPromise as Promise<unknown>);
  }

  try {
    const result = await requestPromise;

    if (useReadCache) {
      readCache.set(url, {
        expiresAt: Date.now() + READ_CACHE_TTL_MS,
        value: clone(result),
      });
    } else {
      invalidateReadCache();
    }

    return result;
  } finally {
    if (useReadCache) {
      inFlightReads.delete(url);
    }
  }
}

async function requestVoid(
  path: string,
  options: { method?: string; query?: Record<string, QueryValue>; body?: unknown } = {},
): Promise<void> {
  await request<void>(path, options);
}

function toPagedResult<T>(items: T[], limit: number): PagedResult<T> {
  const hasNext = items.length === limit;
  return {
    items,
    total: items.length + (hasNext ? 1 : 0),
    hasNext,
  };
}

function unique<T>(values: T[]): T[] {
  return [...new Set(values)];
}

function patchDescription(existing: LocalizedDescription, next: LocalizedDescription) {
  const add: LocalizedDescription = {};

  Object.entries(next).forEach(([key, value]) => {
    if (existing[key] !== value) {
      add[key] = value;
    }
  });

  return {
    add,
    delete: Object.keys(existing).filter(key => !(key in next)),
  };
}

function patchSet<T extends string | number>(existing: T[], next: T[]) {
  const before = new Set(existing);
  const after = new Set(next);

  return {
    add: [...after].filter(value => !before.has(value)),
    remove: [...before].filter(value => !after.has(value)),
  };
}

function toCreateClaim(claim: OAuthClaim) {
  return {
    id: claim.id,
    description: claim.description,
  };
}

function patchScopeClaims(existing: OAuthClaim[], next: OAuthClaim[]) {
  const existingById = new Map(existing.map(claim => [claim.id, claim]));
  const nextById = new Map(next.map(claim => [claim.id, claim]));

  return {
    add: next.filter(claim => !existingById.has(claim.id)).map(toCreateClaim),
    update: next
      .filter(claim => existingById.has(claim.id))
      .map(claim => ({
        id: claim.id,
        description: patchDescription(existingById.get(claim.id)?.description ?? {}, claim.description),
      }))
      .filter(claim => Object.keys(claim.description.add).length > 0 || claim.description.delete.length > 0),
    delete: existing.filter(claim => !nextById.has(claim.id)).map(claim => claim.id),
  };
}

async function fetchAllPages<T>(fetchPage: (offset: number, limit: number) => Promise<PagedResult<T>>, limit = 100): Promise<T[]> {
  const items: T[] = [];
  let offset = 0;

  while (true) {
    const page = await fetchPage(offset, limit);
    items.push(...page.items);

    if (!page.hasNext || page.items.length === 0) {
      return items;
    }

    offset += page.items.length;
  }
}

function hydratePermission(tenantId: string, permissionId: string): Permission {
  const existing = permissionStore.get(entityKey(tenantId, permissionId));
  if (existing) return clone(existing);

  return {
    id: permissionId,
    description: { en: permissionId },
    endpointIds: [],
  };
}

function mapClaim(scopeId: string, claim: { claim: string; description: LocalizedDescription }): OAuthClaim {
  return { id: claim.claim, scopeId, description: claim.description };
}

function serializeResourceEndpoint(
  endpoint: CreateResourceEndpointPayload | SaveResourceEndpointPayload,
  options: { includeGeneratedId?: boolean } = {},
): ResourceEndpointWriteDto {
  const endpointId = 'id' in endpoint && (typeof endpoint.id === 'number' || typeof endpoint.id === 'string')
    ? endpoint.id
    : (options.includeGeneratedId ? globalThis.crypto.randomUUID() : undefined);

  return {
    ...(endpointId !== undefined ? { id: endpointId } : {}),
    method: endpoint.method,
    path: endpoint.path,
    fetchUserInfo: endpoint.fetchUserInfo,
    allowRules: clone(endpoint.allowRules),
    denyRules: clone(endpoint.denyRules),
    injectHeaders: { ...endpoint.injectHeaders },
  };
}

function serializePersistedResourceEndpoint(
  endpoint: CreateResourceEndpointPayload | SaveResourceEndpointPayload,
): ResourceEndpointWriteDto & { id: ResourceEndpointId } {
  const serialized = serializeResourceEndpoint(endpoint, { includeGeneratedId: true });
  if (serialized.id === undefined) throw new Error('Resource endpoint id is required');
  return serialized as ResourceEndpointWriteDto & { id: ResourceEndpointId };
}

function mapResource(resource: ResourceResponseDto): Resource {
  return {
    id: resource.id,
    resource: resource.resource,
    endpoints: resource.endpoints.map(endpoint => ({
      id: endpoint.id,
      method: endpoint.method,
      path: endpoint.path,
      fetchUserInfo: endpoint.fetchUserInfo,
      allowRules: clone(endpoint.allowRules),
      denyRules: clone(endpoint.denyRules),
      injectHeaders: { ...endpoint.injectHeaders },
    })),
  };
}

export async function fetchTenants(): Promise<Tenant[]> {
  const response = await request<TenantsResponse>('/v1/configuration/tenants');
  return response.tenants.map(tenant => ({
    id: tenant.id,
    name: tenant.description || tenant.id,
    description: tenant.description,
    edgeId: tenant.edgeId ?? null,
  }));
}

export async function createTenant(id: string, description: string, edgeId: string | null = null): Promise<void> {
  await requestVoid('/v1/configuration/tenants', {
    method: 'POST',
    body: { id, description, edgeId },
  });
}

export async function updateTenant(id: string, description: string, edgeId: string | null = null): Promise<void> {
  await requestVoid('/v1/configuration/tenants', {
    method: 'PUT',
    body: { id, description, edgeId },
  });
}

export async function deleteTenant(tenantId: string): Promise<void> {
  await requestVoid('/v1/configuration/tenants', {
    method: 'DELETE',
    query: { tenantId },
  });
}

export async function fetchPermissions(tenantId: string, offset = 0, limit = DEFAULT_PAGE_SIZE): Promise<PagedResult<Permission>> {
  const response = await request<PermissionsResponse>('/v1/configuration/permissions', { query: { tenantId, offset, limit } });
  return toPagedResult(
    response.permissions.map(permission => {
      const hydrated = {
        ...hydratePermission(tenantId, permission.permission),
        id: permission.permission,
        description: permission.description,
        endpointIds: [...(permission.endpointIds ?? [])],
      };
      permissionStore.set(entityKey(tenantId, permission.permission), clone(hydrated));
      return hydrated;
    }),
    limit,
  );
}

export async function fetchScopes(tenantId: string, offset = 0, limit = DEFAULT_PAGE_SIZE): Promise<PagedResult<OAuthScope>> {
  const response = await request<ScopesResponse>('/v1/configuration/scopes', { query: { tenantId, offset, limit } });
  return toPagedResult(
    response.scopes.map(scope => ({
      id: scope.scope,
      description: scope.description,
      claims: scope.claims.map(claim => mapClaim(scope.scope, claim)),
    })),
    limit,
  );
}

export async function fetchClients(tenantId: string, offset = 0, limit = DEFAULT_PAGE_SIZE): Promise<PagedResult<OAuthClient>> {
  const response = await request<ClientsResponse>('/v1/configuration/clients', { query: { tenantId, offset, limit } });
  return toPagedResult(
    response.clients.map(client => {
      const supplement = clientSupplementStore.get(entityKey(tenantId, client.id));
      return {
        id: client.id,
        clientName: client.clientName,
        redirectUris: [...client.redirectUris],
        scope: [...client.scope],
        externalAudience: supplement?.externalAudience ? [...supplement.externalAudience] : [],
        hasPreviousSecret: supplement?.hasPreviousSecret ?? client.secretRotation,
        accessTokenTtl: supplement?.accessTokenTtl ?? 3600,
        permissions: [...client.permissions],
        tenantId,
      };
    }),
    limit,
  );
}

export async function fetchRoles(tenantId: string, offset = 0, limit = DEFAULT_PAGE_SIZE): Promise<PagedResult<Role>> {
  const response = await request<RolesResponse>('/v1/configuration/roles', { query: { tenantId, offset, limit } });
  return toPagedResult(
    response.roles.map(role => {
      const supplement = roleSupplementStore.get(entityKey(tenantId, role.id));
      return {
        id: role.id,
        description: role.description,
        active: supplement?.active ?? role.active,
        permissions: role.permissions.map(permissionId => hydratePermission(tenantId, permissionId)),
        createdAt: supplement?.createdAt ?? new Date(0).toISOString(),
        updatedAt: supplement?.updatedAt ?? new Date(0).toISOString(),
      };
    }),
    limit,
  );
}

export async function fetchAllPermissions(tenantId: string): Promise<Permission[]> {
  return fetchAllPages((offset, limit) => fetchPermissions(tenantId, offset, limit));
}

export async function fetchAllScopes(tenantId: string): Promise<OAuthScope[]> {
  return fetchAllPages((offset, limit) => fetchScopes(tenantId, offset, limit));
}

export async function fetchClientPresets(tenantId: string, clientId: string): Promise<AuthorizationPreset[]> {
  const response = await request<AuthorizationPresetResponse[]>('/v1/configuration/auth-request-presets', {
    query: { tenantId, clientId },
  });
  return response.map(preset => ({
    id: preset.id,
    description: preset.description,
    redirectUri: preset.redirectUri,
    scope: [...preset.scope],
    responseType: preset.responseType as 'code' | 'code id_token',
    uiLocales: preset.uiLocales,
    customParameters: preset.customParameters || {},
  }));
}

export async function fetchAllClients(tenantId: string): Promise<OAuthClient[]> {
  return fetchAllPages((offset, limit) => fetchClients(tenantId, offset, limit));
}

export async function fetchAllRoles(tenantId: string): Promise<Role[]> {
  return fetchAllPages((offset, limit) => fetchRoles(tenantId, offset, limit));
}

export async function fetchResources(tenantId: string): Promise<Resource[]> {
  const response = await request<ResourcesResponse>('/v1/configuration/resources', { query: { tenantId } });
  return response.resources.map(mapResource);
}

export async function createResource(
  tenantId: string,
  resource: string,
  endpoints: CreateResourceEndpointPayload[] = [],
): Promise<{ id: number; endpoints: Array<ResourceEndpointWriteDto & { id: ResourceEndpointId }> }> {
  const serializedEndpoints = endpoints.map(endpoint => serializePersistedResourceEndpoint(endpoint));
  const response = await request<CreateResourceResponse>('/v1/configuration/resources', {
    method: 'POST',
    body: { tenantId, resource, endpoints: serializedEndpoints },
  });
  return { id: response.id, endpoints: serializedEndpoints };
}

export async function updateResource(
  id: number,
  existingEndpoints: Array<Pick<ResourceEndpoint, 'id'>>,
  resource: string,
  endpoints?: SaveResourceEndpointPayload[],
): Promise<Array<ResourceEndpointWriteDto & { id: ResourceEndpointId }>> {
  const createEndpoints = (endpoints ?? []).map(endpoint => serializePersistedResourceEndpoint(endpoint));
  const nextEndpointIds = new Set(createEndpoints.map(endpoint => endpoint.id));
  const deleteEndpoints = existingEndpoints
    .map(endpoint => endpoint.id)
    .filter(endpointId => !nextEndpointIds.has(endpointId));

  await requestVoid('/v1/configuration/resources', {
    method: 'PUT',
    body: { id, resource, deleteEndpoints, createEndpoints },
  });

  return createEndpoints;
}

export async function deleteResource(id: number): Promise<void> {
  await requestVoid('/v1/configuration/resources', {
    method: 'DELETE',
    query: { id },
  });
}

export async function saveResourceDraft(
  id: number,
  resource: string,
  endpoints: SaveResourceEndpointPayload[],
): Promise<Resource> {
  const response = await request<ResourceResponseDto>('/v1/configuration/resources/draft', {
    method: 'POST',
    body: { id, resource, endpoints: endpoints.map(endpoint => serializeResourceEndpoint(endpoint)) },
  });
  return mapResource(response);
}

export async function createResourceEndpoint(
  tenantId: string,
  resourceId: number,
  endpoint: CreateResourceEndpointPayload,
): Promise<ResourceEndpointId> {
  const response = await request<CreateResourceEndpointResponse>('/v1/configuration/resources/endpoints', {
    method: 'POST',
    body: { tenantId, resourceId, ...serializeResourceEndpoint(endpoint, { includeGeneratedId: true }) },
  });

  return response.id;
}

export async function updateResourceEndpoint(
  tenantId: string,
  endpointId: ResourceEndpointId,
  endpoint: CreateResourceEndpointPayload,
): Promise<void> {
  await requestVoid('/v1/configuration/resources/endpoints', {
    method: 'PUT',
    body: { tenantId, id: endpointId, ...serializeResourceEndpoint(endpoint) },
  });
}

export async function deleteResourceEndpoint(tenantId: string, endpointId: ResourceEndpointId): Promise<void> {
  await requestVoid('/v1/configuration/resources/endpoints', {
    method: 'DELETE',
    query: { tenantId, id: endpointId },
  });
}

export async function createPermission(tenantId: string, permission: Permission): Promise<void> {
  await requestVoid('/v1/configuration/permissions', {
    method: 'POST',
    body: {
      tenantId,
      permission: permission.id,
      description: permission.description,
      endpointIds: unique(permission.endpointIds ?? []),
    },
  });

  permissionStore.set(entityKey(tenantId, permission.id), clone(permission));
}

export async function updatePermission(tenantId: string, existing: Permission, permission: Permission): Promise<void> {
  await requestVoid('/v1/configuration/permissions', {
    method: 'PUT',
    body: {
      tenantId,
      permission: permission.id,
      description: patchDescription(existing.description, permission.description),
      endpointIds: JSON.stringify(unique(existing.endpointIds ?? [])) !== JSON.stringify(unique(permission.endpointIds ?? []))
        ? unique(permission.endpointIds ?? [])
        : undefined,
    },
  });

  permissionStore.set(entityKey(tenantId, permission.id), clone(permission));
}

export async function deletePermission(tenantId: string, permissionId: string): Promise<void> {
  await requestVoid('/v1/configuration/permissions', {
    method: 'DELETE',
    query: { tenantId, permission: permissionId },
  });

  permissionStore.delete(entityKey(tenantId, permissionId));
}

export async function createScope(tenantId: string, scope: OAuthScope): Promise<void> {
  await requestVoid('/v1/configuration/scopes', {
    method: 'POST',
    body: {
      tenantId,
      id: scope.id,
      description: scope.description,
      claims: scope.claims.map(toCreateClaim),
    },
  });
}

export async function updateScope(tenantId: string, existing: OAuthScope, scope: OAuthScope): Promise<void> {
  await requestVoid('/v1/configuration/scopes', {
    method: 'PUT',
    body: {
      tenantId,
      id: scope.id,
      patch: {
        ...patchScopeClaims(existing.claims, scope.claims),
        description: patchDescription(existing.description, scope.description),
      },
    },
  });
}

export async function deleteScope(tenantId: string, scopeId: string): Promise<void> {
  await requestVoid('/v1/configuration/scopes', {
    method: 'DELETE',
    query: { tenantId, scopeId },
  });
}

export async function createRole(tenantId: string, role: Role): Promise<void> {
  await requestVoid('/v1/configuration/roles', {
    method: 'POST',
    body: {
      tenantId,
      id: role.id,
      description: role.description,
      permissions: unique(role.permissions.map(permission => permission.id)),
    },
  });

  roleSupplementStore.set(entityKey(tenantId, role.id), {
    active: role.active,
    createdAt: role.createdAt,
    updatedAt: role.updatedAt,
  });
}

export async function updateRole(tenantId: string, existing: Role, role: Role): Promise<void> {
  await requestVoid('/v1/configuration/roles', {
    method: 'PUT',
    body: {
      tenantId,
      id: role.id,
      description: patchDescription(existing.description, role.description),
      permissions: patchSet(
        existing.permissions.map(permission => permission.id),
        role.permissions.map(permission => permission.id),
      ),
    },
  });

  roleSupplementStore.set(entityKey(tenantId, role.id), {
    active: role.active,
    createdAt: existing.createdAt,
    updatedAt: role.updatedAt,
  });
}

export async function deleteRole(tenantId: string, roleId: string): Promise<void> {
  await requestVoid('/v1/configuration/roles', {
    method: 'DELETE',
    query: { tenantId, roleId },
  });

  roleSupplementStore.delete(entityKey(tenantId, roleId));
}

export async function createClient(tenantId: string, client: OAuthClient): Promise<string> {
  const response = await request<ClientSecretResponse>('/v1/configuration/clients', {
    method: 'POST',
    body: {
      tenantId,
      id: client.id,
      clientName: client.clientName,
      redirectUris: unique(client.redirectUris),
      allowedScopes: unique(client.scope),
      audience: unique(client.externalAudience),
      permissions: unique(client.permissions),
      accessTokenTtl: client.accessTokenTtl,
    },
  });

  clientSupplementStore.set(entityKey(tenantId, client.id), {
    externalAudience: [...client.externalAudience],
    accessTokenTtl: client.accessTokenTtl,
    hasPreviousSecret: client.hasPreviousSecret,
  });

  return response.secret;
}

export async function rotateClientSecret(tenantId: string, clientId: string): Promise<string> {
  const response = await request<ClientSecretResponse>('/v1/configuration/clients/rotate-secret', {
    method: 'POST',
    query: { tenantId, clientId },
  });

  const existing = clientSupplementStore.get(entityKey(tenantId, clientId));
  clientSupplementStore.set(entityKey(tenantId, clientId), {
    externalAudience: existing?.externalAudience ? [...existing.externalAudience] : [],
    accessTokenTtl: existing?.accessTokenTtl ?? 3600,
    hasPreviousSecret: true,
  });

  return response.secret;
}

export async function deletePreviousClientSecret(tenantId: string, clientId: string): Promise<void> {
  await requestVoid('/v1/configuration/clients/previous-secret', {
    method: 'DELETE',
    query: { tenantId, clientId },
  });

  const existing = clientSupplementStore.get(entityKey(tenantId, clientId));
  clientSupplementStore.set(entityKey(tenantId, clientId), {
    externalAudience: existing?.externalAudience ? [...existing.externalAudience] : [],
    accessTokenTtl: existing?.accessTokenTtl ?? 3600,
    hasPreviousSecret: false,
  });
}

export async function updateClient(tenantId: string, existing: OAuthClient, client: OAuthClient): Promise<void> {
  await requestVoid('/v1/configuration/clients', {
    method: 'PUT',
    body: {
      tenantId,
      clientId: client.id,
      clientName: existing.clientName !== client.clientName ? client.clientName : undefined,
      redirectUris: patchSet(existing.redirectUris, client.redirectUris),
      scope: patchSet(existing.scope, client.scope),
      permissions: patchSet(existing.permissions, client.permissions),
      accessTokenTtl: existing.accessTokenTtl !== client.accessTokenTtl ? client.accessTokenTtl : undefined,
    },
  });

  clientSupplementStore.set(entityKey(tenantId, client.id), {
    externalAudience: [...client.externalAudience],
    accessTokenTtl: client.accessTokenTtl,
    hasPreviousSecret: client.hasPreviousSecret,
  });
}

export async function deleteClient(tenantId: string, clientId: string): Promise<void> {
  await requestVoid('/v1/configuration/clients', {
    method: 'DELETE',
    query: { tenantId, clientId },
  });

  clientSupplementStore.delete(entityKey(tenantId, clientId));
}

export async function fetchEdges(): Promise<Edge[]> {
  const response = await request<EdgesResponse>('/v1/configuration/edges');
  return response.edges.map(edge => ({
    id: edge.id,
    hasOldKey: edge.hasOldKey ?? false,
  }));
}

export async function registerEdge(id: string): Promise<ServiceKey> {
  const response = await request<ServiceKeyResponseDto>('/v1/configuration/edges', {
    method: 'POST',
    body: { id },
  });
  return { keyId: response.keyId, privateKey: response.privateKey };
}

export async function rotateEdgeKey(edgeId: string): Promise<ServiceKey> {
  const response = await request<ServiceKeyResponseDto>('/v1/configuration/edges/rotate-key', {
    method: 'POST',
    query: { edgeId },
  });
  return { keyId: response.keyId, privateKey: response.privateKey };
}

export async function deleteOldEdgeKey(edgeId: string): Promise<void> {
  await requestVoid('/v1/configuration/edges/old-key', {
    method: 'DELETE',
    query: { edgeId },
  });
}

export async function deleteEdge(edgeId: string): Promise<void> {
  await requestVoid('/v1/configuration/edges', {
    method: 'DELETE',
    query: { edgeId },
  });
}