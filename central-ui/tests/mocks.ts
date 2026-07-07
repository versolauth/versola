import type { Page, Request } from '@playwright/test';

type TenantDto = { id: string; description: string; edgeId?: string | null };
type BackendAuthFactor = { type: string; required: boolean };
type BackendAuthFlow = { primary: { credentials: string[]; inlinePassword: boolean; factors: BackendAuthFactor[] }; passkey?: { factors: BackendAuthFactor[] } | null };
type ClientDto = { id: string; clientName: string; redirectUris: string[]; scope: string[]; permissions: string[]; secretRotation: boolean; edgeId?: string; authFlow?: BackendAuthFlow | null };
type ScopeDto = { scope: string; description: Record<string, string>; claims: Array<{ claim: string; description: Record<string, string> }> };
type PermissionDto = { permission: string; description: Record<string, string>; endpointIds: ResourceEndpointId[] };
type InjectTargetDto = 'header' | 'query' | 'body';
type InjectRuleDto = { target: InjectTargetDto; name: string; expression: string };
type ResourceEndpointId = string | number;
type ResourceEndpointDto = { id: ResourceEndpointId; method: string; path: string; fetchUserInfo: boolean; allow?: string | null; inject: InjectRuleDto[] };
type ResourceDto = { resourceId: string; resource: string; endpoints: ResourceEndpointDto[] };
type RoleDto = { id: string; description: Record<string, string>; permissions: string[]; active: boolean };
type EdgeDto = { id: string; hasOldKey?: boolean; tenants?: string[]; clients?: EdgeClientLinkDto[] };
type EdgeClientLinkDto = { tenantId: string; clientId: string };
type AuthorizationPresetDto = {
  id: string;
  clientId: string;
  description: string;
  redirectUri: string;
  postLoginRedirectUri: string;
  scope: string[];
  responseType: string;
  uiLocales?: string[]
};
type FormPropertyDto =
  | { type: 'BooleanProperty'; name: string }
  | { type: 'StringArrayProperty'; name: string; allowedValues: string[] };
type FormDto = {
  id: string;
  version: number;
  active: boolean;
  style: string;
  jsSource: string | null;
  jsCompiled: string | null;
  localizations: Record<string, Record<string, string>>;
  properties: FormPropertyDto[];
};
type FormLocaleDto = { code: string; name: string };
type ThemeDto = { id: string; css: string; tenantId: string | null };
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
  authFlow?: BackendAuthFlow | null;
};
type UpdateClientRequest = {
  clientId: string;
  clientName?: string;
  redirectUris?: SetPatch<string>;
  scope?: SetPatch<string>;
  permissions?: SetPatch<string>;
  accessTokenTtl?: number;
  authFlow?: BackendAuthFlow | null;
};
type CreateScopeRequest = {
  tenantId: string;
  id: string;
  description: Record<string, string>;
  claims: Array<{ id: string; description: Record<string, string> }>;
};
type CreateResourceRequest = {
  tenantId: string;
  resourceId: string;
  resource: string;
  endpoints: Array<{ id?: ResourceEndpointId; method: string; path: string; fetchUserInfo: boolean; allow?: string | null; inject: InjectRuleDto[] }>;
};
type UpdateResourceRequest = {
  resourceId: string;
  resource?: string;
  deleteEndpoints: ResourceEndpointId[];
  createEndpoints: Array<{ id: ResourceEndpointId; method: string; path: string; fetchUserInfo: boolean; allow?: string | null; inject: InjectRuleDto[] }>;
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

type PasskeyInfoDto = {
  id: string;
  name?: string | null;
  deviceType: string;
  transports: string[];
  backedUp: boolean;
  backupEligible: boolean;
  lastUsedAt?: string | null;
  createdAt: string;
};

type UserDto = {
  id: string;
  email?: string;
  phone?: string;
  login?: string;
  claims: Record<string, unknown>;
  rolesByTenant?: Record<string, string[]>;
  passkeys?: PasskeyInfoDto[];
};

type RateLimitDto = { maxAttempts: number; windowSeconds: number };
type SubmissionLimitsDto = {
  otpRequest: RateLimitDto[];
  otpSubmit: RateLimitDto[];
  passwordSubmit: RateLimitDto[];
  banDurationSeconds: number;
};
type PasskeySettingsDto = { rpId: string; rpName: string; origins: string[]; userVerification: string };
type OtpTemplateDto = { id: string; tenantId: string; localizations: Record<string, string> };
type ChallengeSettingsDto = {
  tenantId: string;
  allowedPrefixes: string[];
  passwordRegex?: string | null;
  submissionLimits: SubmissionLimitsDto;
  otpLength: number;
  otpResendAfter: number;
  passkeySettings?: PasskeySettingsDto | null;
};
type LocaleDto = { code: string; name: string; isDefault: boolean; active: boolean };

const emptySubmissionLimits = (): SubmissionLimitsDto => ({
  otpRequest: [],
  otpSubmit: [],
  passwordSubmit: [],
  banDurationSeconds: 0,
});

const defaultChallengeSettings = (tenantId: string): ChallengeSettingsDto => ({
  tenantId,
  allowedPrefixes: [],
  passwordRegex: null,
  submissionLimits: emptySubmissionLimits(),
  otpLength: 6,
  otpResendAfter: 60,
  passkeySettings: null,
});

type MyPermissionsDto = {
  resources: Record<string, { permissions: string[] }>;
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
  users: UserDto[];
  forms: FormDto[];
  formLocales: FormLocaleDto[];
  themes: ThemeDto[];
  otpTemplates: Record<string, OtpTemplateDto[]>; // keyed by tenantId
  challengeSettings: Record<string, ChallengeSettingsDto>; // keyed by tenantId
  locales: LocaleDto[];
  myPermissions: MyPermissionsDto;
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
  myPermissions: {
    resources: {
      central: {
        permissions: [
          'oauth:read', 'oauth:manage', 'oauth:secrets',
          'access:read', 'access:manage',
          'security:read', 'security:manage',
          'users:read', 'users:manage',
          'resources:read', 'resources:manage',
          'forms:read', 'forms:manage',
          'locales:read', 'locales:manage',
          'tenants:read', 'tenants:manage',
          'edges:read', 'edges:manage',
          'jwks:read', 'jwks:manage'
        ]
      }
    }
  },
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
    'tenant-alpha': [{ resourceId: 'alpha-api', resource: 'https://alpha.example/api', endpoints: [{ id: 101, method: 'GET', path: '/alpha/items', fetchUserInfo: false, allow: 'true', inject: [] }] }],
    'tenant-bravo': [{ resourceId: 'bravo-api', resource: 'https://bravo.example/api', endpoints: [{ id: 201, method: 'GET', path: '/bravo/items', fetchUserInfo: false, allow: 'true', inject: [] }] }],
  },
  roles: {
    'tenant-alpha': [{ id: 'alpha-admin', description: { en: 'Alpha admin' }, permissions: ['alpha.read'], active: true }],
    'tenant-bravo': [{ id: 'bravo-admin', description: { en: 'Bravo admin' }, permissions: ['bravo.read'], active: true }],
  },
  edges: [],
  authorizationPresets: {},
  users: [],
  forms: [],
  formLocales: [],
  themes: [],
  otpTemplates: {},
  challengeSettings: {},
  locales: [],
};

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
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
    users: clone(overrides.users ?? defaultState.users),
    forms: clone(overrides.forms ?? defaultState.forms),
    formLocales: clone(overrides.formLocales ?? defaultState.formLocales),
    themes: clone(overrides.themes ?? defaultState.themes),
    otpTemplates: clone({ ...defaultState.otpTemplates, ...overrides.otpTemplates }),
    challengeSettings: clone({ ...defaultState.challengeSettings, ...overrides.challengeSettings }),
    locales: clone(overrides.locales ?? defaultState.locales),
    myPermissions: clone(overrides.myPermissions ?? defaultState.myPermissions),
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

  await page.route('**/configuration/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    // Requests are routed through the edge proxy at `resources/central/`; strip it to match config paths.
    const pathname = url.pathname.replace(/^\/resources\/central/, '');
    const method = request.method();
    const body = readBody(request);

    requests.push({
      method,
      pathname,
      searchParams: Object.fromEntries(url.searchParams.entries()),
      body,
    });

    if (pathname === '/configuration/tenants') {
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
    if (pathname === '/configuration/clients') {
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
          authFlow: payload.authFlow ?? null,
        };

        state.clients[payload.tenantId] = [createdClient, ...tenantClients];
        await route.fulfill(json({ secret: `secret-${payload.id}` }, 201));
        return;
      }

      if (method === 'PUT') {
        const payload = body as UpdateClientRequest;
        const tenantClients = state.clients[tenantId] ?? [];
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
        if ('authFlow' in payload) {
          client.authFlow = payload.authFlow ?? null;
        }

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

    if (pathname === '/configuration/clients/rotate-secret' && method === 'POST') {
      const clientId = url.searchParams.get('clientId');
      // Find client across all tenants since tenantId is not in the request
      let client: ClientDto | undefined;
      for (const clients of Object.values(state.clients)) {
        client = clients.find(candidate => candidate.id === clientId);
        if (client) break;
      }

      if (!client || !clientId) {
        await route.fulfill(json({ message: `Client ${clientId} was not found` }, 404));
        return;
      }

      client.secretRotation = true;
      await route.fulfill(json({ secret: `rotated-${clientId}` }));
      return;
    }

    if (pathname === '/configuration/clients/previous-secret' && method === 'DELETE') {
      const clientId = url.searchParams.get('clientId');
      // Find client across all tenants since tenantId is not in the request
      let client: ClientDto | undefined;
      for (const clients of Object.values(state.clients)) {
        client = clients.find(candidate => candidate.id === clientId);
        if (client) break;
      }

      if (!client || !clientId) {
        await route.fulfill(json({ message: `Client ${clientId} was not found` }, 404));
        return;
      }

      client.secretRotation = false;
      await route.fulfill({ status: 204, body: '' });
      return;
    }

    if (pathname === '/configuration/auth-request-presets') {
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

    if (pathname === '/configuration/scopes') {
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

    if (pathname === '/configuration/permissions') {
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

    if (pathname === '/configuration/resources') {
      if (method === 'GET') {
        await route.fulfill(json({ resources: state.resources[tenantId] ?? [] }));
        return;
      }

      if (method === 'POST') {
        const payload = body as CreateResourceRequest;
        let endpointId = nextEndpointId(state);
        const createdResource: ResourceDto = {
          resourceId: payload.resourceId,
          resource: payload.resource,
          endpoints: payload.endpoints.map(endpoint => ({
            id: endpoint.id ?? endpointId++,
            method: endpoint.method,
            path: endpoint.path,
            fetchUserInfo: endpoint.fetchUserInfo,
            ...(endpoint.allow != null && endpoint.allow.length > 0 ? { allow: endpoint.allow } : {}),
            inject: endpoint.inject.map(rule => ({ ...rule })),
          })),
        };

        state.resources[payload.tenantId] = [createdResource, ...(state.resources[payload.tenantId] ?? [])];
        await route.fulfill(json({ resourceId: createdResource.resourceId }, 201));
        return;
      }

      if (method === 'PUT') {
        const payload = body as UpdateResourceRequest;
        const resource = Object.values(state.resources).flat().find(candidate => candidate.resourceId === payload.resourceId);

        if (!resource) {
          await route.fulfill(json({ message: `Resource ${payload.resourceId} was not found` }, 404));
          return;
        }

        if (payload.resource !== undefined) resource.resource = payload.resource;

        const replacedEndpointIds = new Set<ResourceEndpointId>(payload.deleteEndpoints);
        for (const endpoint of payload.createEndpoints) replacedEndpointIds.add(endpoint.id);

        resource.endpoints = resource.endpoints
          .filter(endpoint => !replacedEndpointIds.has(endpoint.id))
          .concat(payload.createEndpoints.map(endpoint => ({
            id: endpoint.id,
            method: endpoint.method,
            path: endpoint.path,
            fetchUserInfo: endpoint.fetchUserInfo,
            ...(endpoint.allow != null && endpoint.allow.length > 0 ? { allow: endpoint.allow } : {}),
            inject: endpoint.inject.map(rule => ({ ...rule })),
          })));

        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const resourceId = url.searchParams.get('resourceId');
        for (const tenantResources of Object.values(state.resources)) {
          const index = tenantResources.findIndex(resource => resource.resourceId === resourceId);
          if (index >= 0) {
            tenantResources.splice(index, 1);
            break;
          }
        }
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/configuration/roles') {
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

    if (pathname === '/configuration/edges') {
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

        state.edges = [{ id: payload.id, hasOldKey: false, tenants: [], clients: [] }, ...state.edges];
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

    if (pathname === '/configuration/edges/rotate-key' && method === 'POST') {
      const edgeId = url.searchParams.get('edgeId');
      const edge = state.edges.find(e => e.id === edgeId);
      if (!edge) {
        await route.fulfill(json({ message: `Edge ${edgeId} not found` }, 404));
        return;
      }

      edge.hasOldKey = true;
      const mockKeyId = `${new Date().toISOString().split('T')[0]}_${new Date().toISOString().split('T')[1].substring(0, 8).replace(/:/g, '-')}`;
      const mockPrivateKey = `rotated-key-${edgeId}-${Date.now()}`;
      await route.fulfill(json({ keyId: mockKeyId, privateKey: mockPrivateKey }));
      return;
    }

    if (pathname === '/configuration/edges/old-key' && method === 'DELETE') {
      const edgeId = url.searchParams.get('edgeId');
      const edge = state.edges.find(e => e.id === edgeId);
      if (!edge) {
        await route.fulfill(json({ message: `Edge ${edgeId} not found` }, 404));
        return;
      }

      edge.hasOldKey = false;
      await route.fulfill({ status: 204, body: '' });
      return;
    }

    if (pathname === '/configuration/forms/locales') {
      if (method === 'GET') {
        await route.fulfill(json({ locales: state.formLocales }));
        return;
      }

      if (method === 'PUT') {
        const payload = body as { add?: FormLocaleDto[]; delete?: string[] };
        const removed = new Set(payload.delete ?? []);
        state.formLocales = state.formLocales.filter(locale => !removed.has(locale.code));
        for (const locale of payload.add ?? []) {
          if (!state.formLocales.some(existing => existing.code === locale.code)) {
            state.formLocales.push({ ...locale });
          }
        }
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/configuration/forms/active' && method === 'PUT') {
      const payload = body as { id: string; version: number };
      state.forms = state.forms.map(form =>
        form.id === payload.id ? { ...form, active: form.version === payload.version } : form,
      );
      await route.fulfill({ status: 204, body: '' });
      return;
    }

    if (pathname === '/configuration/forms') {
      if (method === 'GET') {
        await route.fulfill(json({ forms: state.forms }));
        return;
      }

      if (method === 'PUT') {
        const payload = body as Omit<FormDto, 'version' | 'active'>;
        const versions = state.forms.filter(form => form.id === payload.id);
        const nextVersion = versions.reduce((max, form) => Math.max(max, form.version), 0) + 1;
        state.forms.push({
          id: payload.id,
          version: nextVersion,
          active: versions.length === 0,
          style: payload.style,
          jsSource: payload.jsSource,
          jsCompiled: payload.jsCompiled,
          localizations: payload.localizations,
          properties: payload.properties,
        });
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/configuration/themes') {
      if (method === 'GET') {
        const visible = state.themes.filter(theme => !theme.tenantId || theme.tenantId === tenantId);
        await route.fulfill(json({ themes: visible }));
        return;
      }

      if (method === 'POST') {
        const payload = body as ThemeDto;
        const scopedTenantId = payload.tenantId ?? null;
        if (state.themes.some(theme => theme.id === payload.id && (theme.tenantId ?? null) === scopedTenantId)) {
          await route.fulfill(json({ message: `Theme ${payload.id} already exists` }, 409));
          return;
        }

        state.themes.push({ id: payload.id, css: payload.css, tenantId: scopedTenantId });
        await route.fulfill({ status: 201, body: '' });
        return;
      }

      if (method === 'PUT') {
        const payload = body as { id: string; css: string };
        const theme = state.themes.find(candidate => candidate.id === payload.id);
        if (!theme) {
          await route.fulfill(json({ message: `Theme ${payload.id} was not found` }, 404));
          return;
        }

        theme.css = payload.css;
        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const id = url.searchParams.get('id');
        if (!id) {
          await route.fulfill(json({ message: 'Missing theme ID' }, 400));
          return;
        }
        state.themes = state.themes.filter(theme => theme.id !== id);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname.startsWith('/configuration/themes/') && method === 'DELETE') {
      const themeId = decodeURIComponent(pathname.substring('/configuration/themes/'.length));
      state.themes = state.themes.filter(theme => theme.id !== themeId);
      await route.fulfill({ status: 204, body: '' });
      return;
    }

    if (pathname === '/configuration/locales') {
      if (method === 'GET') {
        await route.fulfill(json({ locales: state.locales }));
        return;
      }

      if (method === 'PUT') {
        const payload = body as { add?: LocaleDto[]; delete?: string[] };
        const removed = new Set(payload.delete ?? []);
        state.locales = state.locales.filter(locale => !removed.has(locale.code));
        for (const locale of payload.add ?? []) {
          const existing = state.locales.find(candidate => candidate.code === locale.code);
          if (existing) {
            existing.name = locale.name;
            existing.active = locale.active;
            existing.isDefault = locale.isDefault;
          } else {
            state.locales.push({ ...locale });
          }
        }
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/configuration/locales/default' && method === 'PUT') {
      const payload = body as { code: string };
      state.locales = state.locales.map(locale => ({ ...locale, isDefault: locale.code === payload.code }));
      await route.fulfill({ status: 204, body: '' });
      return;
    }

    if (pathname === '/configuration/challenges/otp-templates') {
      if (method === 'GET') {
        await route.fulfill(json({ templates: state.otpTemplates[tenantId] ?? [] }));
        return;
      }

      if (method === 'PUT') {
        const payload = body as OtpTemplateDto;
        const templates = state.otpTemplates[payload.tenantId] ?? [];
        const existing = templates.find(template => template.id === payload.id);
        if (existing) {
          existing.localizations = { ...payload.localizations };
        } else {
          templates.push({ id: payload.id, tenantId: payload.tenantId, localizations: { ...payload.localizations } });
        }
        state.otpTemplates[payload.tenantId] = templates;
        await route.fulfill({ status: 204, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const payload = body as { id: string; tenantId: string };
        state.otpTemplates[payload.tenantId] = (state.otpTemplates[payload.tenantId] ?? [])
          .filter(template => template.id !== payload.id);
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    if (pathname === '/configuration/challenges/challenge-settings') {
      if (method === 'GET') {
        const settings = state.challengeSettings[tenantId] ?? defaultChallengeSettings(tenantId);
        await route.fulfill(json({ settings }));
        return;
      }

      if (method === 'PUT') {
        const payload = body as ChallengeSettingsDto;
        state.challengeSettings[payload.tenantId] = {
          tenantId: payload.tenantId,
          allowedPrefixes: [...payload.allowedPrefixes],
          passwordRegex: payload.passwordRegex ?? null,
          submissionLimits: payload.submissionLimits,
          otpLength: payload.otpLength,
          otpResendAfter: payload.otpResendAfter,
          passkeySettings: payload.passkeySettings ?? null,
        };
        await route.fulfill({ status: 204, body: '' });
        return;
      }
    }

    await route.fulfill(json({ message: `No mock handler for ${method} ${pathname}` }, 404));
  });

  await page.route('**/users**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    // Requests are routed through the edge proxy at `resources/central/`; strip it to match user paths.
    const pathname = url.pathname.replace(/^\/resources\/central/, '');
    const method = request.method();
    const body = readBody(request);

    if (!pathname.startsWith('/users')) {
      await route.fallback();
      return;
    }

    requests.push({
      method,
      pathname,
      searchParams: Object.fromEntries(url.searchParams.entries()),
      body,
    });

    const userToRecord = (user: UserDto) => ({
      id: user.id,
      ...(user.email !== undefined ? { email: user.email } : {}),
      ...(user.phone !== undefined ? { phone: user.phone } : {}),
      ...(user.login !== undefined ? { login: user.login } : {}),
      claims: user.claims ?? {},
    });

    if (pathname === '/users') {
      if (method === 'GET') {
        const id = url.searchParams.get('id');
        const email = url.searchParams.get('email');
        const phone = url.searchParams.get('phone');
        const login = url.searchParams.get('login');
        const matches = state.users.filter(user => {
          if (id) return user.id === id;
          if (email) return user.email === email;
          if (phone) return user.phone === phone;
          if (login) return user.login === login;
          return false;
        });
        if (matches.length === 0) {
          await route.fulfill({ status: 404, body: '' });
          return;
        }
        await route.fulfill(json({ users: matches.map(userToRecord) }));
        return;
      }

      if (method === 'POST') {
        const payload = body as { email?: string; phone?: string; login?: string };
        const conflict = state.users.some(user =>
          (payload.email && user.email === payload.email) ||
          (payload.phone && user.phone === payload.phone) ||
          (payload.login && user.login === payload.login),
        );
        if (conflict) {
          await route.fulfill(json({ message: 'User already exists' }, 409));
          return;
        }
        const generatedId = `00000000-0000-0000-0000-${String(state.users.length + 1).padStart(12, '0')}`;
        state.users.push({
          id: generatedId,
          email: payload.email,
          phone: payload.phone,
          login: payload.login,
          claims: {},
          rolesByTenant: {},
        });
        await route.fulfill(json({ id: generatedId }, 201));
        return;
      }

      if (method === 'PATCH') {
        const payload = body as { id: string; email?: string | null; phone?: string | null; login?: string | null };
        const user = state.users.find(candidate => candidate.id === payload.id);
        if (!user) {
          await route.fulfill(json({ message: `User ${payload.id} not found` }, 404));
          return;
        }
        if (payload.email !== undefined) user.email = payload.email ?? undefined;
        if (payload.phone !== undefined) user.phone = payload.phone ?? undefined;
        if (payload.login !== undefined) user.login = payload.login ?? undefined;
        await route.fulfill({ status: 202, body: '' });
        return;
      }
    }

    if (pathname === '/users/claims' && method === 'PATCH') {
      const payload = body as { id: string; claims: Record<string, unknown> };
      const user = state.users.find(candidate => candidate.id === payload.id);
      if (!user) {
        await route.fulfill(json({ message: `User ${payload.id} not found` }, 404));
        return;
      }
      const next = { ...user.claims };
      for (const [key, value] of Object.entries(payload.claims)) {
        if (value === null) delete next[key];
        else next[key] = value;
      }
      user.claims = next;
      await route.fulfill({ status: 202, body: '' });
      return;
    }

    if (pathname === '/users/roles') {
      if (method === 'GET') {
        const id = url.searchParams.get('id') ?? '';
        const tenantId = url.searchParams.get('tenantId') ?? '';
        const user = state.users.find(candidate => candidate.id === id);
        const roles = user?.rolesByTenant?.[tenantId] ?? [];
        await route.fulfill(json({ roles }));
        return;
      }

      if (method === 'PATCH') {
        const payload = body as { userId: string; tenantId: string; add: string[]; remove: string[] };
        const user = state.users.find(candidate => candidate.id === payload.userId);
        if (!user) {
          await route.fulfill(json({ message: `User ${payload.userId} not found` }, 404));
          return;
        }
        user.rolesByTenant = user.rolesByTenant ?? {};
        const current = new Set(user.rolesByTenant[payload.tenantId] ?? []);
        for (const roleId of payload.remove) current.delete(roleId);
        for (const roleId of payload.add) current.add(roleId);
        user.rolesByTenant[payload.tenantId] = [...current];
        await route.fulfill({ status: 202, body: '' });
        return;
      }
    }

    if (pathname === '/users/passkeys') {
      if (method === 'GET') {
        const id = url.searchParams.get('id') ?? '';
        const user = state.users.find(candidate => candidate.id === id);
        if (!user) {
          await route.fulfill(json({ message: `User ${id} not found` }, 404));
          return;
        }
        await route.fulfill(json({ passkeys: user.passkeys ?? [] }));
        return;
      }

      if (method === 'PATCH') {
        const payload = body as { userId: string; credentialId: string; name: string | null };
        const user = state.users.find(candidate => candidate.id === payload.userId);
        if (!user) {
          await route.fulfill(json({ message: `User ${payload.userId} not found` }, 404));
          return;
        }
        const passkey = (user.passkeys ?? []).find(candidate => candidate.id === payload.credentialId);
        if (!passkey) {
          await route.fulfill(json({ message: `Passkey ${payload.credentialId} not found` }, 404));
          return;
        }
        passkey.name = payload.name;
        await route.fulfill({ status: 202, body: '' });
        return;
      }

      if (method === 'DELETE') {
        const id = url.searchParams.get('id') ?? '';
        const credentialId = url.searchParams.get('credentialId') ?? '';
        const user = state.users.find(candidate => candidate.id === id);
        if (user) {
          user.passkeys = (user.passkeys ?? []).filter(passkey => passkey.id !== credentialId);
        }
        await route.fulfill({ status: 202, body: '' });
        return;
      }
    }

    if (pathname === '/users/limits/reset' && method === 'POST') {
      const payload = body as { userId: string };
      const user = state.users.find(candidate => candidate.id === payload.userId);
      if (!user) {
        await route.fulfill(json({ message: `User ${payload.userId} not found` }, 404));
        return;
      }
      await route.fulfill({ status: 202, body: '' });
      return;
    }

    await route.fulfill(json({ message: `No mock handler for ${method} ${pathname}` }, 404));
  });

  await page.route('**/permissions/me**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = url.pathname.replace(/^\/resources\/central/, '');
    const method = request.method();

    if (pathname === '/permissions/me' && method === 'GET') {
      requests.push({ method, pathname, searchParams: Object.fromEntries(url.searchParams.entries()), body: undefined });
      await route.fulfill(json(state.myPermissions));
      return;
    }

    await route.fallback();
  });

  return { state, requests };
}