import type { Page, Request } from '@playwright/test';

type TenantDto = { id: string; description: string; edgeId?: string | null };
type ClientDto = { id: string; clientName: string; redirectUris: string[]; scope: string[]; permissions: string[]; secretRotation: boolean; edgeId?: string };
type ScopeDto = { scope: string; description: Record<string, string>; claims: Array<{ claim: string; description: Record<string, string> }> };
type PermissionDto = { permission: string; description: Record<string, string>; endpointIds: number[] };
type RuleDto = { subject: string; operator: string; value: unknown; pattern?: string };
type AclRuleNodeDto = { kind: 'rule'; rule: RuleDto };
type AclRuleGroupDto = { kind: 'all' | 'any'; children: AclRuleTreeDto[] };
type AclRuleTreeDto = AclRuleNodeDto | AclRuleGroupDto;
type ResourceEndpointId = string | number;
type ResourceEndpointDto = { id: ResourceEndpointId; method: string; path: string; fetchUserInfo: boolean; allowRules: AclRuleGroupDto; denyRules: AclRuleGroupDto; injectHeaders: Record<string, string> };
type ResourceDto = { id: number; resource: string; endpoints: ResourceEndpointDto[] };
type RoleDto = { id: string; description: Record<string, string>; permissions: string[]; active: boolean };
type EdgeDto = { id: string };
type AuthorizationPresetDto = {
  id: string;
  clientId: string;
  description: string;
  redirectUri: string;
  scope: string[];
  responseType: string;
  uiLocales?: string[]
};
type SetPatch<T> = { add?: T[]; remove?: T[] };
type DescriptionPatch = { add?: Record<string, string>; delete?: string[] };
type CreateClientRequest = {
  tenantId: string;
  id: string;
  clientName: string;
  redirectUris: string[];
  allowedScopes: string[];
  audience: string[];
  permissions: string[];
  accessTokenTtl: number;
};
type UpdateClientRequest = {
  tenantId: string;
  clientId: string;
  clientName?: string;
  redirectUris?: SetPatch<string>;
  scope?: SetPatch<string>;
  permissions?: SetPatch<string>;
  accessTokenTtl?: number;
};
type CreateScopeRequest = {
  tenantId: string;
  id: string;
  description: Record<string, string>;
  claims: Array<{ id: string; description: Record<string, string> }>;
};
type CreateResourceRequest = {
  tenantId: string;
  resource: string;
  endpoints: Array<{ id?: ResourceEndpointId; method: string; path: string; fetchUserInfo: boolean; allowRules: AclRuleGroupDto; denyRules: AclRuleGroupDto; injectHeaders: Record<string, string> }>;
};
type UpdateResourceRequest = {
  id: number;
  resource: string;
  deleteEndpoints: ResourceEndpointId[];
  createEndpoints: Array<{ id: ResourceEndpointId; method: string; path: string; fetchUserInfo: boolean; allowRules: AclRuleGroupDto; denyRules: AclRuleGroupDto; injectHeaders: Record<string, string> }>;
};
type CreatePermissionRequest = {
  tenantId: string;
  permission: string;
  description: Record<string, string>;
  endpointIds: ResourceEndpointId[];
};
type UpdatePermissionRequest = {
  tenantId: string;
  permission: string;
  description: DescriptionPatch;
  endpointIds?: ResourceEndpointId[];
};
type CreateRoleRequest = {
  tenantId: string;
  id: string;
  description: Record<string, string>;
  permissions: string[];
};
type UpdateRoleRequest = {
  tenantId: string;
  id: string;
  description: DescriptionPatch;
  permissions: SetPatch<string>;
};
type UpdateScopeRequest = {
  tenantId: string;
  id: string;
  patch: {
    add?: Array<{ id: string; description: Record<string, string> }>;
    update?: Array<{ id: string; description: DescriptionPatch }>;
    delete?: string[];
    description?: DescriptionPatch;
  };
};

export type RequestLog = {
  method: string;
  pathname: string;
  searchParams: Record<string, string>;
  body: unknown;
};

export type MockConfigState = {
  tenants: TenantDto[];
  clients: Record<string, ClientDto[]>;
  scopes: Record<string, ScopeDto[]>;
  permissions: Record<string, PermissionDto[]>;
  resources: Record<string, ResourceDto[]>;
  roles: Record<string, RoleDto[]>;
  edges: EdgeDto[];
  authorizationPresets: Record<string, AuthorizationPresetDto[]>; // keyed by clientId
};

export type MockConfigHarness = {
  state: MockConfigState;
  requests: RequestLog[];
};

const defaultState: MockConfigState = {
  tenants: [
    { id: 'tenant-alpha', description: 'Alpha Workspace', edgeId: null },
    { id: 'tenant-bravo', description: 'Bravo Workspace', edgeId: null },
  ],
  clients: {
    'tenant-alpha': [{ id: 'alpha-web', clientName: 'Alpha Web', redirectUris: ['https://alpha.example/callback'], scope: ['openid'], permissions: ['alpha.read'], secretRotation: false }],
    'tenant-bravo': [{ id: 'bravo-web', clientName: 'Bravo Web', redirectUris: ['https://bravo.example/callback'], scope: ['email'], permissions: ['bravo.read'], secretRotation: false }],
  },
  scopes: {
    'tenant-alpha': [{ scope: 'openid', description: { en: 'OpenID scope' }, claims: [{ claim: 'sub', description: { en: 'Subject' } }] }],
    'tenant-bravo': [{ scope: 'email', description: { en: 'Email scope' }, claims: [{ claim: 'email', description: { en: 'Email address' } }] }],
  },
  permissions: {
    'tenant-alpha': [{ permission: 'alpha.read', description: { en: 'Read alpha resources' }, endpointIds: [101] }],
    'tenant-bravo': [{ permission: 'bravo.read', description: { en: 'Read bravo resources' }, endpointIds: [201] }],
  },
  resources: {
    'tenant-alpha': [{ id: 1, resource: 'https://alpha.example/api', endpoints: [{ id: 101, method: 'GET', path: '/alpha/items', fetchUserInfo: false, allowRules: emptyAclGroup(), denyRules: emptyAclGroup(), injectHeaders: {} }] }],
    'tenant-bravo': [{ id: 2, resource: 'https://bravo.example/api', endpoints: [{ id: 201, method: 'GET', path: '/bravo/items', fetchUserInfo: false, allowRules: emptyAclGroup(), denyRules: emptyAclGroup(), injectHeaders: {} }] }],
  },
  roles: {
    'tenant-alpha': [{ id: 'alpha-admin', description: { en: 'Alpha admin' }, permissions: ['alpha.read'], active: true }],
    'tenant-bravo': [{ id: 'bravo-admin', description: { en: 'Bravo admin' }, permissions: ['bravo.read'], active: true }],
  },
  edges: [],
  authorizationPresets: {},
};

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function emptyAclGroup(): AclRuleGroupDto {
  return { kind: 'any', children: [] };
}

function mergeState(overrides: Partial<MockConfigState> = {}): MockConfigState {
  return {
    tenants: clone(overrides.tenants ?? defaultState.tenants),
    clients: clone({ ...defaultState.clients, ...overrides.clients }),
    authorizationPresets: clone({ ...defaultState.authorizationPresets, ...overrides.authorizationPresets }),
    scopes: clone({ ...defaultState.scopes, ...overrides.scopes }),
    permissions: clone({ ...defaultState.permissions, ...overrides.permissions }),
    resources: clone({ ...defaultState.resources, ...overrides.resources }),
    edges: clone(overrides.edges ?? defaultState.edges),
    roles: clone({ ...defaultState.roles, ...overrides.roles }),
  };
}

function readBody(request: Request): unknown {
  const body = request.postData();
  return body ? JSON.parse(body) : undefined;
}

function pageSlice<T>(items: T[], url: URL): T[] {
  const offset = Number(url.searchParams.get('offset') ?? '0');
  const limit = Number(url.searchParams.get('limit') ?? `${items.length || 30}`);
  return items.slice(offset, offset + limit);
}

function json(data: unknown, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(data) };
}

function applyPatchSet<T>(items: T[], patch?: SetPatch<T>): T[] {
  if (!patch) {
    return items;
  }

  const next = items.filter(item => !(patch.remove ?? []).includes(item));
  for (const item of patch.add ?? []) {
    if (!next.includes(item)) {
      next.push(item);
    }
  }

  return next;
}

function applyDescriptionPatch(description: Record<string, string>, patch?: DescriptionPatch): Record<string, string> {
  const next = { ...description };

  for (const key of patch?.delete ?? []) {
    delete next[key];
  }

  Object.assign(next, patch?.add ?? {});
  return next;
}

function nextResourceId(state: MockConfigState) {
  return Math.max(0, ...Object.values(state.resources).flat().map(resource => resource.id)) + 1;
}

function nextEndpointId(state: MockConfigState) {
  return Math.max(
    100,
    ...Object.values(state.resources)
      .flat()
      .flatMap(resource => resource.endpoints.map(endpoint => typeof endpoint.id === 'number' ? endpoint.id : 0)),
  ) + 1;
}

export async function setupConfigApiMocks(page: Page, overrides: Partial<MockConfigState> = {}): Promise<MockConfigHarness> {
  const state = mergeState(overrides);
  const requests: RequestLog[] = [];

  await page.route('**/v1/configuration/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = url.pathname;
    const method = request.method();
    const body = readBody(request);

    requests.push({
      method,
      pathname,
      searchParams: Object.fromEntries(url.searchParams.entries()),
      body,
    });

    if (pathname === '/v1/configuration/tenants') {
      if (method === 'GET') {
        await route.fulfill(json({ tenants: state.tenants }));
        return;
      }

      if (method === 'POST') {
        const payload = body as TenantDto;
        if (state.tenants.some(tenant => tenant.id === payload.id)) {
          await route.fulfill(json({ message: `Tenant ${payload.id} already exists` }, 409));
          return;
        }

        state.tenants.push({ id: payload.id, description: payload.description });
        state.clients[payload.id] = [];
        state.scopes[payload.id] = [];
        state.permissions[payload.id] = [];
        state.resources[payload.id] = [];
        state.roles[payload.id] = [];
        await route.fulfill({ status: 201, body: '' });
        return;
      }

      if (method === 'PUT') {
        const payload = body as TenantDto;
        const tenant = state.tenants.find(candidate => candidate.id === payload.id);
        if (!tenant) {
          await route.fulfill(json({ message: `Tenant ${payload.id} was not found` }, 404));
          return;
        }

        tenant.description = payload.description;
        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const tenantId = url.searchParams.get('tenantId');
        state.tenants = state.tenants.filter(tenant => tenant.id !== tenantId);
        if (tenantId) {
          delete state.clients[tenantId];
          delete state.scopes[tenantId];
          delete state.permissions[tenantId];
          delete state.resources[tenantId];
          delete state.roles[tenantId];
        }
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    const tenantId = url.searchParams.get('tenantId') ?? '';
    if (pathname === '/v1/configuration/clients') {
      if (method === 'GET') {
        await route.fulfill(json({ clients: pageSlice(state.clients[tenantId] ?? [], url) }));
        return;
      }

      if (method === 'POST') {
        const payload = body as CreateClientRequest;
        const tenantClients = state.clients[payload.tenantId] ?? [];

        if (tenantClients.some(client => client.id === payload.id)) {
          await route.fulfill(json({ message: `Client ${payload.id} already exists` }, 409));
          return;
        }

        const createdClient: ClientDto = {
          id: payload.id,
          clientName: payload.clientName,
          redirectUris: [...payload.redirectUris],
          scope: [...payload.allowedScopes],
          permissions: [...payload.permissions],
          secretRotation: false,
        };

        state.clients[payload.tenantId] = [createdClient, ...tenantClients];
        await route.fulfill(json({ secret: `secret-${payload.id}` }, 201));
        return;
      }

      if (method === 'PUT') {
        const payload = body as UpdateClientRequest;
        const tenantClients = state.clients[payload.tenantId] ?? [];
        const client = tenantClients.find(candidate => candidate.id === payload.clientId);

        if (!client) {
          await route.fulfill(json({ message: `Client ${payload.clientId} was not found` }, 404));
          return;
        }

        if (payload.clientName !== undefined) {
          client.clientName = payload.clientName;
        }

        client.redirectUris = applyPatchSet(client.redirectUris, payload.redirectUris);
        client.scope = applyPatchSet(client.scope, payload.scope);
        client.permissions = applyPatchSet(client.permissions, payload.permissions);

        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const clientId = url.searchParams.get('clientId');
        state.clients[tenantId] = (state.clients[tenantId] ?? []).filter(client => client.id !== clientId);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/v1/configuration/clients/rotate-secret' && method === 'POST') {
      const clientId = url.searchParams.get('clientId');
      const client = (state.clients[tenantId] ?? []).find(candidate => candidate.id === clientId);

      if (!client || !clientId) {
        await route.fulfill(json({ message: `Client ${clientId} was not found` }, 404));
        return;
      }

      client.secretRotation = true;
      await route.fulfill(json({ secret: `rotated-${clientId}` }));
      return;
    }

    if (pathname === '/v1/configuration/clients/previous-secret' && method === 'DELETE') {
      const clientId = url.searchParams.get('clientId');
      const client = (state.clients[tenantId] ?? []).find(candidate => candidate.id === clientId);

      if (!client || !clientId) {
        await route.fulfill(json({ message: `Client ${clientId} was not found` }, 404));
        return;
      }

      client.secretRotation = false;
      await route.fulfill({ status: 204, body: '' });
      return;
    }

    if (pathname === '/v1/configuration/auth-request-presets') {
      const clientId = url.searchParams.get('clientId');

      if (method === 'GET' && clientId) {
        const presets = state.authorizationPresets[clientId] ?? [];
        await route.fulfill(json(presets));
        return;
      }

      if (method === 'POST') {
        const payload = body as { tenantId: string; clientId: string; presets: AuthorizationPresetDto[] };
        state.authorizationPresets[payload.clientId] = payload.presets;
        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const presetId = url.searchParams.get('id');
        if (clientId && presetId) {
          state.authorizationPresets[clientId] = (state.authorizationPresets[clientId] ?? []).filter(p => p.id !== presetId);
        }
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/v1/configuration/scopes') {
      if (method === 'GET') {
        await route.fulfill(json({ scopes: pageSlice(state.scopes[tenantId] ?? [], url) }));
        return;
      }

      if (method === 'POST') {
        const payload = body as CreateScopeRequest;
        const tenantScopes = state.scopes[payload.tenantId] ?? [];

        if (tenantScopes.some(scope => scope.scope === payload.id)) {
          await route.fulfill(json({ message: `Scope ${payload.id} already exists` }, 409));
          return;
        }

        state.scopes[payload.tenantId] = [
          {
            scope: payload.id,
            description: { ...payload.description },
            claims: payload.claims.map(claim => ({ claim: claim.id, description: { ...claim.description } })),
          },
          ...tenantScopes,
        ];
        await route.fulfill({ status: 201, body: '' });
        return;
      }

      if (method === 'PUT') {
        const payload = body as UpdateScopeRequest;
        const scope = (state.scopes[payload.tenantId] ?? []).find(candidate => candidate.scope === payload.id);

        if (!scope) {
          await route.fulfill(json({ message: `Scope ${payload.id} was not found` }, 404));
          return;
        }

        scope.description = applyDescriptionPatch(scope.description, payload.patch.description);
        scope.claims = scope.claims.filter(claim => !(payload.patch.delete ?? []).includes(claim.claim));

        for (const claim of payload.patch.update ?? []) {
          const existingClaim = scope.claims.find(candidate => candidate.claim === claim.id);
          if (existingClaim) {
            existingClaim.description = applyDescriptionPatch(existingClaim.description, claim.description);
          }
        }

        for (const claim of payload.patch.add ?? []) {
          if (!scope.claims.some(candidate => candidate.claim === claim.id)) {
            scope.claims.push({ claim: claim.id, description: { ...claim.description } });
          }
        }

        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const scopeId = url.searchParams.get('scopeId');
        state.scopes[tenantId] = (state.scopes[tenantId] ?? []).filter(scope => scope.scope !== scopeId);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/v1/configuration/permissions') {
      if (method === 'GET') {
        await route.fulfill(json({ permissions: pageSlice(state.permissions[tenantId] ?? [], url) }));
        return;
      }

      if (method === 'POST') {
        const payload = body as CreatePermissionRequest;
        const tenantPermissions = state.permissions[payload.tenantId] ?? [];

        if (tenantPermissions.some(candidate => candidate.permission === payload.permission)) {
          await route.fulfill(json({ message: `Permission ${payload.permission} already exists` }, 409));
          return;
        }

        state.permissions[payload.tenantId] = [{
          permission: payload.permission,
          description: { ...payload.description },
          endpointIds: [...payload.endpointIds],
        }, ...tenantPermissions];
        await route.fulfill({ status: 201, body: '' });
        return;
      }

      if (method === 'PUT') {
        const payload = body as UpdatePermissionRequest;
        const permission = (state.permissions[payload.tenantId] ?? []).find(candidate => candidate.permission === payload.permission);

        if (!permission) {
          await route.fulfill(json({ message: `Permission ${payload.permission} was not found` }, 404));
          return;
        }

        permission.description = applyDescriptionPatch(permission.description, payload.description);
        if (payload.endpointIds) {
          permission.endpointIds = [...payload.endpointIds];
        }

        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const permissionId = url.searchParams.get('permission');
        state.permissions[tenantId] = (state.permissions[tenantId] ?? []).filter(permission => permission.permission !== permissionId);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/v1/configuration/resources') {
      if (method === 'GET') {
        await route.fulfill(json({ resources: state.resources[tenantId] ?? [] }));
        return;
      }

      if (method === 'POST') {
        const payload = body as CreateResourceRequest;
        let endpointId = nextEndpointId(state);
        const createdResource: ResourceDto = {
          id: nextResourceId(state),
          resource: payload.resource,
          endpoints: payload.endpoints.map(endpoint => ({
            id: endpoint.id ?? endpointId++,
            method: endpoint.method,
            path: endpoint.path,
            fetchUserInfo: endpoint.fetchUserInfo,
            allowRules: clone(endpoint.allowRules),
            denyRules: clone(endpoint.denyRules),
            injectHeaders: { ...endpoint.injectHeaders },
          })),
        };

        state.resources[payload.tenantId] = [createdResource, ...(state.resources[payload.tenantId] ?? [])];
        await route.fulfill(json({ id: createdResource.id }, 201));
        return;
      }

      if (method === 'PUT') {
        const payload = body as UpdateResourceRequest;
        const resource = Object.values(state.resources).flat().find(candidate => candidate.id === payload.id);

        if (!resource) {
          await route.fulfill(json({ message: `Resource ${payload.id} was not found` }, 404));
          return;
        }

        resource.resource = payload.resource;

        const replacedEndpointIds = new Set<ResourceEndpointId>(payload.deleteEndpoints);
        for (const endpoint of payload.createEndpoints) replacedEndpointIds.add(endpoint.id);

        resource.endpoints = resource.endpoints
          .filter(endpoint => !replacedEndpointIds.has(endpoint.id))
          .concat(payload.createEndpoints.map(endpoint => ({
            id: endpoint.id,
            method: endpoint.method,
            path: endpoint.path,
            fetchUserInfo: endpoint.fetchUserInfo,
            allowRules: clone(endpoint.allowRules),
            denyRules: clone(endpoint.denyRules),
            injectHeaders: { ...endpoint.injectHeaders },
          })));

        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const resourceId = Number(url.searchParams.get('id'));
        for (const tenantResources of Object.values(state.resources)) {
          const index = tenantResources.findIndex(resource => resource.id === resourceId);
          if (index >= 0) {
            tenantResources.splice(index, 1);
            break;
          }
        }
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/v1/configuration/roles') {
      if (method === 'GET') {
        await route.fulfill(json({ roles: pageSlice(state.roles[tenantId] ?? [], url) }));
        return;
      }

      if (method === 'POST') {
        const payload = body as CreateRoleRequest;
        const tenantRoles = state.roles[payload.tenantId] ?? [];

        if (tenantRoles.some(candidate => candidate.id === payload.id)) {
          await route.fulfill(json({ message: `Role ${payload.id} already exists` }, 409));
          return;
        }

        state.roles[payload.tenantId] = [{
          id: payload.id,
          description: { ...payload.description },
          permissions: [...payload.permissions],
          active: true,
        }, ...tenantRoles];
        await route.fulfill({ status: 201, body: '' });
        return;
      }

      if (method === 'PUT') {
        const payload = body as UpdateRoleRequest;
        const role = (state.roles[payload.tenantId] ?? []).find(candidate => candidate.id === payload.id);

        if (!role) {
          await route.fulfill(json({ message: `Role ${payload.id} was not found` }, 404));
          return;
        }

        role.description = applyDescriptionPatch(role.description, payload.description);
        role.permissions = applyPatchSet(role.permissions, payload.permissions);
        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const roleId = url.searchParams.get('roleId');
        state.roles[tenantId] = (state.roles[tenantId] ?? []).filter(role => role.id !== roleId);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/v1/configuration/edges') {
      if (method === 'GET') {
        await route.fulfill(json({ edges: state.edges }));
        return;
      }

      if (method === 'POST') {
        const payload = body as { id: string };
        if (state.edges.some(edge => edge.id === payload.id)) {
          await route.fulfill(json({ message: `Edge ${payload.id} already exists` }, 409));
          return;
        }

        state.edges = [{ id: payload.id, tenants: [], clients: [] }, ...state.edges];
        const mockKeyId = `${new Date().toISOString().split('T')[0]}_12-00-00`;
        const mockPrivateKey = `mock-private-key-${payload.id}-${Date.now()}`;
        await route.fulfill(json({ keyId: mockKeyId, privateKey: mockPrivateKey }, 201));
        return;
      }

      if (method === 'PUT') {
        const payload = body as { id: string; tenants: string[]; clients: EdgeClientLinkDto[] };
        const edge = state.edges.find(e => e.id === payload.id);
        if (!edge) {
          await route.fulfill(json({ message: `Edge ${payload.id} not found` }, 404));
          return;
        }

        edge.tenants = payload.tenants;
        edge.clients = payload.clients.map(c => ({ tenantId: c.tenantId, clientId: c.clientId }));
        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const edgeId = url.searchParams.get('edgeId');
        state.edges = state.edges.filter(edge => edge.id !== edgeId);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/v1/configuration/edges/rotate-key') {
      if (method === 'POST') {
        const edgeId = url.searchParams.get('edgeId');
        const edge = state.edges.find(e => e.id === edgeId);
        if (!edge) {
          await route.fulfill(json({ message: `Edge ${edgeId} not found` }, 404));
          return;
        }

        const mockKeyId = `${new Date().toISOString().split('T')[0]}_${new Date().toISOString().split('T')[1].substring(0, 8).replace(/:/g, '-')}`;
        const mockPrivateKey = `rotated-key-${edgeId}-${Date.now()}`;
        await route.fulfill(json({ keyId: mockKeyId, privateKey: mockPrivateKey }));
        return;
      }
    }

    await route.fulfill(json({ message: `No mock handler for ${method} ${pathname}` }, 404));
  });

  return { state, requests };
}