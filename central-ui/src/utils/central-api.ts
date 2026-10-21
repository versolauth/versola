import { clientKindOf, type ClientKind } from './client-presets';
import type { ThemeVariableCatalog } from './theme-variables';
import type {
  AuthFlow,
  AuthMethod,
  AuthorizationDetailType,
  AuthorizationPreset,
  BackendProperty,
  Edge,
  OverloadPolicy,
  Locale,
  FormPreview,
  FormRecord,
  OtpTemplateRecord,
  ChallengeSettingsChanges,
  ChallengeSettingsRecord,
  ClientTemplate,
  JwksKeySummary,
  MutualTlsAuth,
  SystemSettingsRecord,
  InjectRule,
  OAuthClaim,
  OAuthClient,
  OAuthScope,
  Permission,
  ConsentFlow,
  Resource,
  ResourceEndpoint,
  ResourceEndpointId,
  Role,
  ServiceKey,
  SecurityProfile,
  Tenant,
  ThemeOverrides,
  ThemePreset,
  ThemeRecord,
  ThemeTokens,
} from '../types';

export const DEFAULT_PAGE_SIZE = 30;
const READ_CACHE_TTL_MS = 60_000;
const DEFAULT_REFRESH_TOKEN_TTL_SECONDS = 180 * 24 * 60 * 60;

// Where to send the browser when a request comes back 401 without a Location
// header — i.e. there is no session for the edge to reauthenticate, so it can't
// tell us which login to use (the preset id lives in the session cookie). The
// admin console is always the `central-admin` preset, so this is its login
// entry point on the edge. Overridable via configureCentralApi({ loginUrl }).
const DEFAULT_LOGIN_URL = '/login/central-admin';

type LocalizedDescription = Record<string, string>;
type PagedResult<T> = { items: T[]; total: number; hasNext: boolean };
type QueryValue = string | number | undefined | null;
type ConsoleMode = 'prefix' | 'direct';
type CentralApiConfig = { baseUrl: string | null; loginUrl: string; consoleMode: ConsoleMode };

type ClientSecretResponse = { secret: string };
type CreateClientResponse = { secret?: string | null; createdAt?: string | null };

/** What a registration hands back: the secret, for the one method that gets one, and the
 *  time central recorded - the console holds the client it just sent, which cannot know
 *  either on its own. */
export type CreatedClient = { secret: string | null; createdAt: string | null };
type AuthorizationPresetResponse = {
  id: string;
  clientId: string;
  description: string;
  redirectUri: string;
  postLoginRedirectUri: string;
  postLogoutRedirectUri?: string;
  scope: string[];
  responseType: string;
  uiLocales?: string[];
  customParameters?: Record<string, string[]>;
  cookieDomain?: string;
  cookiePath?: string;
};
type CreateResourceResponse = { resourceId: string; secret: string | null };
type RotateResourceSecretResponse = { secret: string };
export type CreateResourceEndpointPayload = Omit<ResourceEndpoint, 'id' | 'allow' | 'stepUpCondition' | 'stepUpAcr' | 'maxAge'> & {
  allow: string | null;
  stepUpCondition: string | null;
  stepUpAcr: string | null;
  maxAge: number | null;
};
export type SaveResourceEndpointPayload = CreateResourceEndpointPayload & { id?: ResourceEndpointId };
export type ResourceEndpointWriteDto = {
  id?: string | number;
  method: string;
  path: string;
  fetchUserInfo: boolean;
  allow: string | null;
  inject: InjectRule[];
  stepUpCondition?: string | null;
  stepUpAcr?: string | null;
  maxAge?: number | null;
};
type ResourceEndpointDto = {
  id?: ResourceEndpointId;
  method: string;
  path: string;
  fetchUserInfo: boolean;
  allow?: string;
  inject: InjectRule[];
  stepUpCondition?: string;
  stepUpAcr?: string;
  maxAge?: number;
};
type ResourceResponseDto = { resourceId: string; resource: string; audience: string[]; endpoints: Array<ResourceEndpointDto & { id: ResourceEndpointId }>; internal: boolean; secretRotation: boolean };

type EdgeResponseDto = { id: string; hasOldKey?: boolean; requireDpopNonce?: boolean; overloadPolicy?: OverloadPolicy | null };
type EdgesResponse = { edges: EdgeResponseDto[] };
type ServiceKeyResponseDto = { keyId: string; privateKey: string };

type TenantsResponse = {
  tenants: Array<{ id: string; description: string; edgeId?: string | null; securityProfile: SecurityProfile; signingKeyId?: string | null }>;
};
type PermissionsResponse = { permissions: Array<{ permission: string; description: LocalizedDescription; endpointIds: ResourceEndpointId[] }> };
type ScopesResponse = { scopes: Array<{ scope: string; description: LocalizedDescription; claims: Array<{ claim: string; description: LocalizedDescription }> }> };
type BackendAuthFactor = { type: string; required: boolean };
type BackendAuthFlow = {
  primary: { credentials: string[]; inlinePassword: boolean; factors: BackendAuthFactor[] };
  passkey?: { factors: BackendAuthFactor[] } | null;
  equivalents?: Record<string, string[]>;
  otpType: 'sms' | 'email';
};
type BackendConsentFlow = { allowPartial: boolean; rememberDuration?: number | null; requiredScopes?: string[]; offlineAccessOptional?: boolean };
type ClientsResponse = {
  clients: Array<{
    id: string;
    clientName: LocalizedDescription;
    redirectUris: string[];
    scope: string[];
    permissions: string[];
    secretRotation: boolean;
    authMethod?: AuthMethod;
    applicationType?: 'web' | 'native';
    kind?: 'web' | 'device' | 'service';
    accessTokenTtl: number;
    theme: string;
    otpTemplateId: string;
    authFlow?: BackendAuthFlow | null;
    frontChannelLogoutUri?: string | null;
    frontChannelLogoutSessionRequired: boolean;
    backChannelLogoutUri?: string | null;
    refreshTokenTtl?: number;
    logoUri?: string | null;
    policyUri?: string | null;
    tosUri?: string | null;
    consentFlow?: BackendConsentFlow | null;
    dpopBoundAccessTokens?: boolean;
    dpopSigningAlgs?: string[];
    dpopMinRsaKeySize?: number | null;
    mtlsAuth?: MutualTlsAuth | null;
    certificateBoundAccessTokens?: boolean;
    jwks?: Record<string, unknown> | null;
    requireSignedRequestObject?: boolean;
    requirePushedAuthorizationRequests?: boolean;
    template?: ClientTemplate | null;
    createdAt?: string;
    themeOverrides?: ThemeOverrides | null;
  }>;
};
type RolesResponse = { roles: Array<{ id: string; description: LocalizedDescription; permissions: string[]; active: boolean }> };
type ResourcesResponse = { resources: ResourceResponseDto[] };
type AuthorizationDetailTypesResponse = {
  types: Array<{ type: string; description: LocalizedDescription; schema: Record<string, unknown> }>;
};

const apiConfig: CentralApiConfig = { baseUrl: null, loginUrl: DEFAULT_LOGIN_URL, consoleMode: 'prefix' };
const permissionStore = new Map<string, Permission>();
const clientSupplementStore = new Map<string, { accessTokenTtl: number; refreshTokenTtl: number; hasPreviousSecret: boolean }>();
const roleSupplementStore = new Map<string, { createdAt: string; updatedAt: string }>();
const readCache = new Map<string, { expiresAt: number; value: unknown }>();
const inFlightReads = new Map<string, Promise<unknown>>();

// Tenant-keyed reference-data caches shared across pages. Each holds the
// resolved (mapped) list per tenantId and is invalidated on a matching write.
const scopesStore = new Map<string, Promise<OAuthScope[]>>();
const permissionsStore = new Map<string, Promise<Permission[]>>();
const resourcesStore = new Map<string, Promise<Resource[]>>();
const rolesStore = new Map<string, Promise<Role[]>>();

function authFlowToBackend(flow: AuthFlow | null | undefined): BackendAuthFlow | null {
  if (!flow) return null;
  return {
    primary: {
      credentials: flow.primaryCredentials,
      inlinePassword: flow.inlinePassword,
      factors: flow.factors,
    },
    passkey: flow.passkey ? { factors: flow.passkeyFactors ?? [] } : null,
    equivalents: flow.equivalents,
    otpType: flow.otpType,
  };
}

function authFlowFromBackend(flow: BackendAuthFlow | null | undefined): AuthFlow | null {
  if (!flow) return null;
  return {
    primaryCredentials: flow.primary.credentials as AuthFlow['primaryCredentials'],
    otpType: flow.otpType,
    inlinePassword: flow.primary.inlinePassword,
    factors: flow.primary.factors as AuthFlow['factors'],
    passkey: flow.passkey != null,
    passkeyFactors: (flow.passkey?.factors ?? []) as AuthFlow['passkeyFactors'],
    equivalents: flow.equivalents ?? {},
  };
}

function consentFlowToBackend(flow: ConsentFlow | null | undefined): BackendConsentFlow | null {
  if (!flow) return null;
  return {
    allowPartial: flow.allowPartial,
    rememberDuration: flow.rememberDurationDays != null ? flow.rememberDurationDays * 86400 : null,
    requiredScopes: flow.requiredScopes,
    offlineAccessOptional: flow.offlineAccessOptional,
  };
}

function consentFlowFromBackend(flow: BackendConsentFlow | null | undefined): ConsentFlow | null {
  if (!flow) return null;
  return {
    allowPartial: flow.allowPartial,
    rememberDurationDays: flow.rememberDuration != null ? Math.round(flow.rememberDuration / 86400) : null,
    requiredScopes: flow.requiredScopes ?? [],
    offlineAccessOptional: flow.offlineAccessOptional ?? false,
  };
}

/** Set equality for the string lists a client registers, which the backend stores unordered. */
function sameStringSet(a: string[] | null | undefined, b: string[] | null | undefined): boolean {
  const left = [...(a ?? [])].sort();
  const right = [...(b ?? [])].sort();
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

// mtlsAuth and jwks are plain JSON-serializable structures with no ordering ambiguity, so a
// stringify comparison is exact - unlike sameStringSet, there's no set to normalise first.
function sameJsonValue(a: unknown, b: unknown): boolean {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
}

/** The overrides, or null when there are none: absent, null and `{}` all mean the client changes nothing. */
function nonEmptyOverrides(overrides: ThemeOverrides | null | undefined): ThemeOverrides | null {
  return overrides && Object.keys(overrides).length > 0 ? overrides : null;
}

function sameConsentFlow(a: ConsentFlow | null | undefined, b: ConsentFlow | null | undefined): boolean {
  if (!a || !b) return !a && !b;
  return a.allowPartial === b.allowPartial
    && a.rememberDurationDays === b.rememberDurationDays
    && sameStringSet(a.requiredScopes, b.requiredScopes)
    && a.offlineAccessOptional === b.offlineAccessOptional;
}

// Client names are displayed on the consent screen and are stored as locale-keyed objects.
function clientNameToBackend(name: LocalizedDescription): LocalizedDescription {
  return { ...name };
}

function clientNameFromBackend(name: LocalizedDescription | string | null | undefined): LocalizedDescription {
  if (name == null) return {};
  if (typeof name === 'string') return { en: name };
  return { ...name };
}

function sameLocalizedDescription(a: LocalizedDescription, b: LocalizedDescription): boolean {
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length
    && keys.every(key => a[key] === b[key]);
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function entityKey(tenantId: string, id: string): string {
  return `${tenantId}::${id}`;
}

export function resolveBaseUrl(): string {
  return apiConfig.baseUrl?.trim() || window.location.origin;
}

// Everything the console talks to is edge's "central" resource (see
// EdgeController.scala's /resources/{resourceId}/... proxy route --
// "central" is the literal resourceId, not a prefix edge understands).
//
// In 'prefix' mode (the default -- local dev, docker-local, path-based prod;
// see vite.config.ts and docker/versola-tools/nginx.conf.template) the
// console takes a /central/{x} shortcut instead of calling that route
// outright, so the EDGE_SESSION cookie can be scoped to a path (Path=/central)
// and thereby belong to this application alone rather than to the whole
// domain -- a cookie is only sent to URLs beneath its path, so the console's
// assets (/central/admin/) and its API calls have to share one prefix. An
// external proxy (the Vite dev server / gateway nginx) rewrites /central/{x}
// back to /resources/central/{x} before anything reaches edge.
//
// In 'direct' mode (the console hosted on its own domain, e.g. k8s -- see
// #222) there is no such proxy and no shared origin to scope a cookie path
// against, so the console calls edge's real route directly. Set via the
// console-mode attribute on <versola-admin> (see admin-app.ts).
export function centralResourcePath(path: string): string {
  return apiConfig.consoleMode === 'direct' ? `resources/central/${path}` : `central/${path}`;
}

function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const baseUrl = resolveBaseUrl();
  const normalizedBase = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  const normalizedPath = path.replace(/^\//, '');
  const proxiedPath = centralResourcePath(normalizedPath);
  const url = new URL(proxiedPath, normalizedBase);
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

function memoizeRef<T>(store: Map<string, Promise<T>>, tenantId: string, factory: () => Promise<T>): Promise<T> {
  const existing = store.get(tenantId);
  if (existing) {
    return existing;
  }
  const pending = factory().catch((error: unknown) => {
    store.delete(tenantId);
    throw error;
  });
  store.set(tenantId, pending);
  return pending;
}

function invalidateRefData<T>(store: Map<string, Promise<T>>, tenantId: string) {
  store.delete(tenantId);
}

function invalidateAllRefData() {
  scopesStore.clear();
  permissionsStore.clear();
  resourcesStore.clear();
  rolesStore.clear();
}

/** What central answered with, when it was JSON: a screen that can show more than the message
 * (the keys a locale is missing, the lines a JSON Schema fails on) reads it from `error.details`. */
export type ApiError = Error & { status: number; details?: unknown };

function buildErrorMessage(status: number, bodyText: string): string {
  const trimmed = bodyText.trim();

  if (!trimmed) {
    return `Request failed (${status})`;
  }

  try {
    const parsed = JSON.parse(trimmed) as { message?: string; error?: string; locale?: string; missing?: string[]; InvalidSchema?: { errors?: string[] } };
    if (parsed.missing?.length) {
      const prefix = parsed.message || parsed.error || `Locale ${parsed.locale || ''} is incomplete`;
      return `${prefix}. Missing: ${parsed.missing.join(', ')}`;
    }
    if (parsed.InvalidSchema?.errors?.length) {
      return `Invalid JSON Schema: ${parsed.InvalidSchema.errors.join('; ')}`;
    }
    return parsed.message || parsed.error || `Request failed (${status})`;
  } catch {
    return `${trimmed} (${status})`;
  }
}

function parseErrorDetails(bodyText: string): unknown {
  try {
    return JSON.parse(bodyText) as unknown;
  } catch {
    return undefined;
  }
}

// null/empty for either field resets it to its default (host origin for baseUrl,
// DEFAULT_LOGIN_URL for loginUrl) rather than leaving a previously-set value
// stuck — so clearing the api-url / login-url attribute reverts to defaults.
export function configureCentralApi(config: { baseUrl?: string | null; loginUrl?: string | null; consoleMode?: ConsoleMode | null }): void {
  if ('baseUrl' in config) {
    apiConfig.baseUrl = config.baseUrl?.trim() || null;
  }
  if ('loginUrl' in config) {
    apiConfig.loginUrl = config.loginUrl?.trim() || DEFAULT_LOGIN_URL;
  }
  if ('consoleMode' in config) {
    apiConfig.consoleMode = config.consoleMode === 'direct' ? 'direct' : 'prefix';
  }
  invalidateReadCache();
  invalidateAllRefData();
}

// A 401 means the caller isn't authenticated. If the edge included a Location
// (session expired but it knows the preset from the cookie), follow it; if not
// (no session at all — a fresh visit), the edge can't know which login to use,
// so fall back to the console's configured login entry point. Either way this
// is a top-level navigation, so the returned promise never resolves — callers
// await it and stop, rather than surfacing a spurious error mid-redirect.
//
// The target is resolved against the API/edge origin (resolveBaseUrl()), not
// the page origin: login/complete live on the edge alongside the API, and the
// edge's own Location is a root-relative path, so when api-url points at a
// different origin the login must follow it there. In the default same-origin
// deployment resolveBaseUrl() is window.location.origin, so this is a no-op.
//
// Note: cross-origin, the browser won't expose the Location header to JS unless
// the edge sends `Access-Control-Expose-Headers: Location`; without it we
// simply fall back to apiConfig.loginUrl, which is the intended default anyway.
function redirectToLogin(response: Response): Promise<never> {
  const target = response.headers.get('Location') ?? apiConfig.loginUrl;
  window.location.assign(new URL(target, resolveBaseUrl()).toString());
  return new Promise<never>(() => undefined);
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
    const response = await fetch(url, {
      method,
      headers: {
        Accept: 'application/json',
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      },
      ...(options.body ? { body: JSON.stringify(options.body) } : {}),
      credentials: 'include',
    });

    // Not authenticated — navigate to login (following the edge's Location if it
    // gave one) instead of surfacing an error.
    if (response.status === 401) {
      // Drop this URL's in-flight entry before handing off to the redirect
      // (whose promise never resolves): the outer `finally` that normally
      // cleans it up won't run, so without this a concurrent read of the same
      // URL would await a promise that never settles.
      if (useReadCache) {
        inFlightReads.delete(url);
      }
      return await redirectToLogin(response);
    }

    if (!response.ok) {
      const bodyText = await response.text();
      const error = new Error(buildErrorMessage(response.status, bodyText)) as ApiError;
      error.status = response.status;
      error.details = parseErrorDetails(bodyText);
      throw error;
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

function sortById<T extends { id: string | number }>(items: T[]): T[] {
  return [...items].sort((a, b) => String(a.id).localeCompare(String(b.id), undefined, { numeric: true }));
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
    allow: endpoint.allow,
    inject: endpoint.inject.map(rule => ({ ...rule })),
    stepUpCondition: endpoint.stepUpCondition ?? null,
    stepUpAcr: endpoint.stepUpAcr ?? null,
    maxAge: endpoint.maxAge ?? null,
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
    resourceId: resource.resourceId,
    resource: resource.resource,
    audience: [...(resource.audience ?? [])],
    endpoints: resource.endpoints.map(endpoint => ({
      id: endpoint.id,
      method: endpoint.method,
      path: endpoint.path,
      fetchUserInfo: endpoint.fetchUserInfo,
      allow: endpoint.allow,
      inject: endpoint.inject.map(rule => ({ ...rule })),
      ...(endpoint.stepUpCondition != null ? { stepUpCondition: endpoint.stepUpCondition } : {}),
      ...(endpoint.stepUpAcr != null ? { stepUpAcr: endpoint.stepUpAcr } : {}),
      ...(endpoint.maxAge != null ? { maxAge: endpoint.maxAge } : {}),
    })),
    hasSecret: resource.internal,
    hasPreviousSecret: resource.secretRotation,
  };
}

export async function fetchTenants(): Promise<Tenant[]> {
  const response = await request<TenantsResponse>('/configuration/tenants');
  return sortById(response.tenants.map(tenant => ({
    id: tenant.id,
    name: tenant.description || tenant.id,
    description: tenant.description,
    edgeId: tenant.edgeId ?? null,
    securityProfile: tenant.securityProfile,
    signingKeyId: tenant.signingKeyId ?? null,
  })));
}

export async function createTenant(
  id: string,
  description: string,
  edgeId: string | null = null,
  securityProfile: SecurityProfile = 'fapi2',
): Promise<void> {
  await requestVoid('/configuration/tenants', {
    method: 'POST',
    body: { id, description, edgeId, securityProfile },
  });
}

export async function updateTenant(id: string, description: string, edgeId: string | null = null): Promise<void> {
  await requestVoid('/configuration/tenants', {
    method: 'PUT',
    body: { id, description, edgeId },
  });
}

export async function deleteTenant(tenantId: string): Promise<void> {
  await requestVoid('/configuration/tenants', {
    method: 'DELETE',
    query: { tenantId },
  });
}

export async function fetchPermissions(tenantId: string, offset = 0, limit = DEFAULT_PAGE_SIZE): Promise<PagedResult<Permission>> {
  const response = await request<PermissionsResponse>('/configuration/permissions', { query: { tenantId, offset, limit } });
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
  const response = await request<ScopesResponse>('/configuration/scopes', { query: { tenantId, offset, limit } });
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
  const response = await request<ClientsResponse>('/configuration/clients', { query: { tenantId, offset, limit } });
  return toPagedResult(
    response.clients.map(client => {
      const supplement = clientSupplementStore.get(entityKey(tenantId, client.id));
      return {
        id: client.id,
        clientName: clientNameFromBackend(client.clientName),
        redirectUris: [...client.redirectUris],
        scope: [...client.scope],
        // Central names the kind; the application type and a missing credential are what a console
        // that predates it had to guess from.
        clientType: client.kind
          ? (client.kind === 'device' ? 'native' : 'web')
          : client.applicationType === 'native' || client.authMethod === 'none' ? 'native' : 'web',
        kind: client.kind,
        authMethod: client.authMethod ?? 'client_secret',
        hasPreviousSecret: supplement?.hasPreviousSecret ?? client.secretRotation,
        // The backend now returns the real value directly (previously it didn't, and this
        // fell back to a page-memory-only cache that was empty — and silently wrong — after
        // every reload; see clientSupplementStore below).
        accessTokenTtl: client.accessTokenTtl,
        refreshTokenTtl: supplement?.refreshTokenTtl ?? client.refreshTokenTtl ?? DEFAULT_REFRESH_TOKEN_TTL_SECONDS,
        permissions: [...client.permissions],
        theme: client.theme ?? 'default',
        otpTemplateId: client.otpTemplateId ?? null,
        authFlow: authFlowFromBackend(client.authFlow),
        frontChannelLogoutUri: client.frontChannelLogoutUri ?? null,
        frontChannelLogoutSessionRequired: client.frontChannelLogoutSessionRequired,
        backChannelLogoutUri: client.backChannelLogoutUri ?? null,
        logoUri: client.logoUri ?? null,
        policyUri: client.policyUri ?? null,
        tosUri: client.tosUri ?? null,
        consentFlow: consentFlowFromBackend(client.consentFlow),
        dpopBoundAccessTokens: client.dpopBoundAccessTokens ?? false,
        dpopSigningAlgs: client.dpopSigningAlgs ?? [],
        dpopMinRsaKeySize: client.dpopMinRsaKeySize ?? null,
        mtlsAuth: client.mtlsAuth ?? null,
        certificateBoundAccessTokens: client.certificateBoundAccessTokens ?? false,
        jwks: client.jwks ?? null,
        requireSignedRequestObject: client.requireSignedRequestObject ?? false,
        requirePushedAuthorizationRequests: client.requirePushedAuthorizationRequests ?? false,
        template: client.template ?? null,
        createdAt: client.createdAt ?? null,
        themeOverrides: nonEmptyOverrides(client.themeOverrides),
        tenantId,
      };
    }),
    limit,
  );
}

export async function fetchRoles(tenantId: string, offset = 0, limit = DEFAULT_PAGE_SIZE): Promise<PagedResult<Role>> {
  const response = await request<RolesResponse>('/configuration/roles', { query: { tenantId, offset, limit } });
  return toPagedResult(
    response.roles.map(role => {
      const supplement = roleSupplementStore.get(entityKey(tenantId, role.id));
      return {
        id: role.id,
        description: role.description,
        active: role.active,
        permissions: role.permissions.map(permissionId => hydratePermission(tenantId, permissionId)),
        createdAt: supplement?.createdAt ?? new Date(0).toISOString(),
        updatedAt: supplement?.updatedAt ?? new Date(0).toISOString(),
      };
    }),
    limit,
  );
}

export async function fetchAllPermissions(tenantId: string): Promise<Permission[]> {
  return sortById(await fetchAllPages((offset, limit) => fetchPermissions(tenantId, offset, limit)));
}

export async function fetchAllScopes(tenantId: string): Promise<OAuthScope[]> {
  return sortById(await fetchAllPages((offset, limit) => fetchScopes(tenantId, offset, limit)));
}

export async function fetchClientPresets(clientId: string): Promise<AuthorizationPreset[]> {
  const response = await request<AuthorizationPresetResponse[]>('/configuration/auth-request-presets', {
    query: { clientId },
  });
  return response.map(preset => ({
    id: preset.id,
    description: preset.description,
    redirectUri: preset.redirectUri,
    postLoginRedirectUri: preset.postLoginRedirectUri,
    postLogoutRedirectUri: preset.postLogoutRedirectUri,
    scope: [...preset.scope],
    responseType: preset.responseType as 'code' | 'code id_token',
    uiLocales: preset.uiLocales,
    customParameters: preset.customParameters || {},
    cookieDomain: preset.cookieDomain,
    cookiePath: preset.cookiePath,
  }));
}


export async function saveClientPresets(clientId: string, presets: AuthorizationPreset[]): Promise<void> {
  await requestVoid('/configuration/auth-request-presets', {
    method: 'POST',
    body: {
      clientId,
      presets: presets.map(p => ({
        id: p.id,
        description: p.description,
        redirectUri: p.redirectUri,
        postLoginRedirectUri: p.postLoginRedirectUri,
        postLogoutRedirectUri: p.postLogoutRedirectUri,
        scope: p.scope,
        responseType: p.responseType,
        uiLocales: p.uiLocales,
        customParameters: p.customParameters,
        cookieDomain: p.cookieDomain,
        cookiePath: p.cookiePath,
      })),
    },
  });
}
export async function fetchAllClients(tenantId: string): Promise<OAuthClient[]> {
  return sortById(await fetchAllPages((offset, limit) => fetchClients(tenantId, offset, limit)));
}

export async function fetchAllRoles(tenantId: string): Promise<Role[]> {
  return sortById(await fetchAllPages((offset, limit) => fetchRoles(tenantId, offset, limit)));
}

export async function fetchResources(tenantId: string): Promise<Resource[]> {
  const response = await request<ResourcesResponse>('/configuration/resources', { query: { tenantId } });
  return response.resources
    .map(mapResource)
    .sort((a, b) => a.resourceId.localeCompare(b.resourceId, undefined, { numeric: true }));
}

export function getScopes(tenantId: string): Promise<OAuthScope[]> {
  return memoizeRef(scopesStore, tenantId, () => fetchAllScopes(tenantId));
}

export function getPermissions(tenantId: string): Promise<Permission[]> {
  return memoizeRef(permissionsStore, tenantId, () => fetchAllPermissions(tenantId));
}

export function getResources(tenantId: string): Promise<Resource[]> {
  return memoizeRef(resourcesStore, tenantId, () => fetchResources(tenantId));
}

export function getRoles(tenantId: string): Promise<Role[]> {
  return memoizeRef(rolesStore, tenantId, () => fetchAllRoles(tenantId));
}

export async function createResource(
  tenantId: string,
  resourceId: string,
  resource: string,
  audience: string[],
  endpoints: CreateResourceEndpointPayload[] = [],
  internal = false,
): Promise<{ resourceId: string; endpoints: Array<ResourceEndpointWriteDto & { id: ResourceEndpointId }>; secret: string | null }> {
  const serializedEndpoints = endpoints.map(endpoint => serializePersistedResourceEndpoint(endpoint));
  const response = await request<CreateResourceResponse>('/configuration/resources', {
    method: 'POST',
    body: { tenantId, resourceId, resource, audience, endpoints: serializedEndpoints, internal },
  });
  resourcesStore.clear();
  return { resourceId: response.resourceId, endpoints: serializedEndpoints, secret: response.secret };
}

export async function rotateResourceSecret(resourceId: string): Promise<string> {
  const response = await request<RotateResourceSecretResponse>('/configuration/resources/rotate-secret', {
    method: 'POST',
    query: { resourceId },
  });
  resourcesStore.clear();
  return response.secret;
}

export async function deletePreviousResourceSecret(resourceId: string): Promise<void> {
  await requestVoid('/configuration/resources/previous-secret', {
    method: 'DELETE',
    query: { resourceId },
  });
  resourcesStore.clear();
}

/**
 * One PUT for any group of a resource: the URI, the audience (sent as a patch against what the
 * caller last saw) and the endpoints. Endpoints are a patch too: `deleteEndpoints` leaves the
 * server's set, `createEndpoints` are added, and one whose id already exists overwrites it, so an
 * edited endpoint is sent only in `createEndpoints` and keeps its id (and the permissions linked
 * to it). A group the caller does not touch is left out and is not changed.
 */
export async function updateResource(
  resourceId: string,
  patch: {
    resource?: string;
    audience?: { existing: string[]; next: string[] };
    deleteEndpoints?: ResourceEndpointId[];
    createEndpoints?: SaveResourceEndpointPayload[];
  },
): Promise<Array<ResourceEndpointWriteDto & { id: ResourceEndpointId }>> {
  const createEndpoints = (patch.createEndpoints ?? []).map(endpoint => serializePersistedResourceEndpoint(endpoint));

  await requestVoid('/configuration/resources', {
    method: 'PUT',
    body: {
      resourceId,
      ...(patch.resource !== undefined ? { resource: patch.resource } : {}),
      audience: patch.audience ? patchSet(patch.audience.existing, patch.audience.next) : { add: [], remove: [] },
      deleteEndpoints: patch.deleteEndpoints ?? [],
      createEndpoints,
    },
  });

  resourcesStore.clear();
  return createEndpoints;
}

export async function deleteResource(resourceId: string): Promise<void> {
  await requestVoid('/configuration/resources', {
    method: 'DELETE',
    query: { resourceId },
  });

  resourcesStore.clear();
}

export async function createPermission(tenantId: string, permission: Permission): Promise<void> {
  await requestVoid('/configuration/permissions', {
    method: 'POST',
    body: {
      tenantId,
      permission: permission.id,
      description: permission.description,
      endpointIds: unique(permission.endpointIds ?? []),
    },
  });

  permissionStore.set(entityKey(tenantId, permission.id), clone(permission));
  invalidateRefData(permissionsStore, tenantId);
}

export async function updatePermission(tenantId: string, existing: Permission, permission: Permission): Promise<void> {
  await requestVoid('/configuration/permissions', {
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
  invalidateRefData(permissionsStore, tenantId);
}

export async function deletePermission(tenantId: string, permissionId: string): Promise<void> {
  await requestVoid('/configuration/permissions', {
    method: 'DELETE',
    query: { tenantId, permission: permissionId },
  });

  permissionStore.delete(entityKey(tenantId, permissionId));
  invalidateRefData(permissionsStore, tenantId);
}

export async function createScope(tenantId: string, scope: OAuthScope): Promise<void> {
  await requestVoid('/configuration/scopes', {
    method: 'POST',
    body: {
      tenantId,
      id: scope.id,
      description: scope.description,
      claims: scope.claims.map(toCreateClaim),
    },
  });

  invalidateRefData(scopesStore, tenantId);
}

export async function updateScope(tenantId: string, existing: OAuthScope, scope: OAuthScope): Promise<void> {
  await requestVoid('/configuration/scopes', {
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

  invalidateRefData(scopesStore, tenantId);
}

export async function deleteScope(tenantId: string, scopeId: string): Promise<void> {
  await requestVoid('/configuration/scopes', {
    method: 'DELETE',
    query: { tenantId, scopeId },
  });

  invalidateRefData(scopesStore, tenantId);
}

export async function fetchAuthorizationDetailTypes(tenantId: string): Promise<AuthorizationDetailType[]> {
  const response = await request<AuthorizationDetailTypesResponse>('/configuration/authorization-detail-types', {
    query: { tenantId },
  });
  return response.types
    .map(type => ({ type: type.type, description: type.description, schema: type.schema }))
    .sort((a, b) => a.type.localeCompare(b.type, undefined, { numeric: true }));
}

export async function createAuthorizationDetailType(tenantId: string, detailType: AuthorizationDetailType): Promise<void> {
  await requestVoid('/configuration/authorization-detail-types', {
    method: 'POST',
    body: {
      tenantId,
      type: detailType.type,
      description: detailType.description,
      schema: detailType.schema,
    },
  });
}

export async function updateAuthorizationDetailType(tenantId: string, detailType: AuthorizationDetailType): Promise<void> {
  await requestVoid('/configuration/authorization-detail-types', {
    method: 'PUT',
    body: {
      tenantId,
      type: detailType.type,
      description: detailType.description,
      schema: detailType.schema,
    },
  });
}

export async function deleteAuthorizationDetailType(tenantId: string, type: string): Promise<void> {
  await requestVoid('/configuration/authorization-detail-types', {
    method: 'DELETE',
    query: { tenantId, type },
  });
}

export async function createRole(tenantId: string, role: Role): Promise<void> {
  await requestVoid('/configuration/roles', {
    method: 'POST',
    body: {
      tenantId,
      id: role.id,
      description: role.description,
      permissions: unique(role.permissions.map(permission => permission.id)),
      active: role.active,
    },
  });

  roleSupplementStore.set(entityKey(tenantId, role.id), {
    createdAt: role.createdAt,
    updatedAt: role.updatedAt,
  });
  invalidateRefData(rolesStore, tenantId);
}

export async function updateRole(tenantId: string, existing: Role, role: Role): Promise<void> {
  await requestVoid('/configuration/roles', {
    method: 'PUT',
    body: {
      tenantId,
      id: role.id,
      description: patchDescription(existing.description, role.description),
      permissions: patchSet(
        existing.permissions.map(permission => permission.id),
        role.permissions.map(permission => permission.id),
      ),
      // Patch semantics: only sent when the switch moved.
      ...(role.active !== existing.active ? { active: role.active } : {}),
    },
  });

  roleSupplementStore.set(entityKey(tenantId, role.id), {
    createdAt: existing.createdAt,
    updatedAt: role.updatedAt,
  });
  invalidateRefData(rolesStore, tenantId);
}

export async function deleteRole(tenantId: string, roleId: string): Promise<void> {
  await requestVoid('/configuration/roles', {
    method: 'DELETE',
    query: { tenantId, roleId },
  });

  roleSupplementStore.delete(entityKey(tenantId, roleId));
  invalidateRefData(rolesStore, tenantId);
}

/**
 * One registration and one update per kind of client (`/configuration/clients/{web,native,service}`).
 * Each endpoint refuses a member its kind does not have, so the body is cut down to the members
 * the kind's request declares: what the kind fixes (a device's DPoP binding, a service's lack of
 * redirect URIs) is not ours to send, and is set by central.
 */
const KIND_PATHS: Record<ClientKind, string> = { web: 'web', device: 'native', service: 'service' };

const COMMON_CREATE_FIELDS = [
  'tenantId', 'id', 'clientName', 'allowedScopes', 'accessTokenTtl', 'authMethod', 'mtlsAuth',
  'dpopSigningAlgs', 'dpopMinRsaKeySize',
] as const;

const SIGN_IN_FIELDS = [
  'redirectUris', 'refreshTokenTtl', 'theme', 'authFlow', 'otpTemplateId', 'frontChannelLogoutUri',
  'frontChannelLogoutSessionRequired', 'backChannelLogoutUri', 'logoUri', 'policyUri', 'tosUri', 'consentFlow',
  'issueEdgeClientCertificate', 'enrollEdgeClientCertificate', 'themeOverrides',
] as const;

const CREATE_FIELDS: Record<ClientKind, readonly string[]> = {
  web: [
    ...COMMON_CREATE_FIELDS, ...SIGN_IN_FIELDS, 'dpopBoundAccessTokens', 'certificateBoundAccessTokens', 'jwks',
    'requireSignedRequestObject', 'requirePushedAuthorizationRequests',
  ],
  device: [...COMMON_CREATE_FIELDS, ...SIGN_IN_FIELDS],
  // Permissions are what a token issued to the client itself may call: only a service client has any.
  service: [...COMMON_CREATE_FIELDS, 'permissions', 'dpopBoundAccessTokens', 'certificateBoundAccessTokens', 'jwks'],
};

const COMMON_UPDATE_FIELDS = [
  'clientId', 'clientName', 'scope', 'accessTokenTtl', 'dpopSigningAlgs', 'dpopMinRsaKeySize',
] as const;

const SIGN_IN_UPDATE_FIELDS = [
  'redirectUris', 'refreshTokenTtl', 'theme', 'authFlow', 'otpTemplateId', 'frontChannelLogoutUri',
  'frontChannelLogoutSessionRequired', 'backChannelLogoutUri', 'logoUri', 'policyUri', 'tosUri', 'consentFlow',
  'themeOverrides',
] as const;

const UPDATE_FIELDS: Record<ClientKind, readonly string[]> = {
  web: [
    ...COMMON_UPDATE_FIELDS, ...SIGN_IN_UPDATE_FIELDS, 'dpopBoundAccessTokens', 'mtlsAuth',
    'certificateBoundAccessTokens', 'jwks', 'requireSignedRequestObject', 'requirePushedAuthorizationRequests',
  ],
  device: [...COMMON_UPDATE_FIELDS, ...SIGN_IN_UPDATE_FIELDS],
  service: [...COMMON_UPDATE_FIELDS, 'permissions', 'dpopBoundAccessTokens', 'mtlsAuth', 'certificateBoundAccessTokens', 'jwks'],
};

function pickFields(body: Record<string, unknown>, fields: readonly string[]): Record<string, unknown> {
  return Object.fromEntries(Object.entries(body).filter(([name]) => fields.includes(name)));
}

/** Resolves to the generated secret, or to `null` for a native client, which has none. */
export async function createClient(tenantId: string, client: OAuthClient): Promise<CreatedClient> {
  const kind = clientKindOf(client);
  const response = await request<CreateClientResponse>(`/configuration/clients/${KIND_PATHS[kind]}`, {
    method: 'POST',
    body: pickFields({
      tenantId,
      id: client.id,
      clientName: clientNameToBackend(client.clientName),
      redirectUris: unique(client.redirectUris),
      allowedScopes: unique(client.scope),
      permissions: unique(client.permissions),
      accessTokenTtl: client.accessTokenTtl,
      theme: client.theme ?? 'default',
      otpTemplateId: client.otpTemplateId ?? undefined,
      authFlow: authFlowToBackend(client.authFlow),
      frontChannelLogoutUri: client.frontChannelLogoutUri ?? null,
      frontChannelLogoutSessionRequired: client.frontChannelLogoutSessionRequired,
      backChannelLogoutUri: client.backChannelLogoutUri ?? null,
      refreshTokenTtl: client.refreshTokenTtl,
      logoUri: client.logoUri ?? null,
      policyUri: client.policyUri ?? null,
      tosUri: client.tosUri ?? null,
      consentFlow: consentFlowToBackend(client.consentFlow),
      dpopBoundAccessTokens: client.dpopBoundAccessTokens,
      dpopSigningAlgs: client.dpopSigningAlgs ?? [],
      dpopMinRsaKeySize: client.dpopMinRsaKeySize ?? null,
      authMethod: client.authMethod,
      mtlsAuth: client.mtlsAuth ?? null,
      certificateBoundAccessTokens: !!client.certificateBoundAccessTokens,
      jwks: client.jwks ?? null,
      requireSignedRequestObject: !!client.requireSignedRequestObject,
      requirePushedAuthorizationRequests: !!client.requirePushedAuthorizationRequests,
      themeOverrides: nonEmptyOverrides(client.themeOverrides) ?? undefined,
      issueEdgeClientCertificate: !!client.edgeFronted && kind !== 'device',
      enrollEdgeClientCertificate: !!client.edgeFronted && kind === 'device',
    }, CREATE_FIELDS[kind]),
  });

  clientSupplementStore.set(entityKey(tenantId, client.id), {
    accessTokenTtl: client.accessTokenTtl,
    refreshTokenTtl: client.refreshTokenTtl ?? DEFAULT_REFRESH_TOKEN_TTL_SECONDS,
    hasPreviousSecret: client.hasPreviousSecret,
  });

  return { secret: response.secret ?? null, createdAt: response.createdAt ?? null };
}

export async function rotateClientSecret(tenantId: string, clientId: string): Promise<string> {
  const response = await request<ClientSecretResponse>('/configuration/clients/rotate-secret', {
    method: 'POST',
    query: { clientId },
  });

  const existing = clientSupplementStore.get(entityKey(tenantId, clientId));
  clientSupplementStore.set(entityKey(tenantId, clientId), {
    accessTokenTtl: existing?.accessTokenTtl ?? 3600,
    refreshTokenTtl: existing?.refreshTokenTtl ?? DEFAULT_REFRESH_TOKEN_TTL_SECONDS,
    hasPreviousSecret: true,
  });

  return response.secret;
}

export async function deletePreviousClientSecret(tenantId: string, clientId: string): Promise<void> {
  await requestVoid('/configuration/clients/previous-secret', {
    method: 'DELETE',
    query: { clientId },
  });

  const existing = clientSupplementStore.get(entityKey(tenantId, clientId));
  clientSupplementStore.set(entityKey(tenantId, clientId), {
    accessTokenTtl: existing?.accessTokenTtl ?? 3600,
    refreshTokenTtl: existing?.refreshTokenTtl ?? DEFAULT_REFRESH_TOKEN_TTL_SECONDS,
    hasPreviousSecret: false,
  });
}

export async function updateClient(tenantId: string, existing: OAuthClient, client: OAuthClient): Promise<void> {
  // The kind the client was registered as: it is fixed, so it is read off what is stored.
  const kind = clientKindOf(existing);
  await requestVoid(`/configuration/clients/${KIND_PATHS[kind]}`, {
    method: 'PUT',
    body: pickFields({
      clientId: client.id,
      clientName: sameLocalizedDescription(existing.clientName, client.clientName)
        ? undefined
        : clientNameToBackend(client.clientName),
      redirectUris: patchSet(existing.redirectUris, client.redirectUris),
      scope: patchSet(existing.scope, client.scope),
      permissions: patchSet(existing.permissions, client.permissions),
      accessTokenTtl: existing.accessTokenTtl !== client.accessTokenTtl ? client.accessTokenTtl : undefined,
      refreshTokenTtl: existing.refreshTokenTtl !== client.refreshTokenTtl ? client.refreshTokenTtl : undefined,
      theme: existing.theme !== client.theme ? client.theme : undefined,
      otpTemplateId: existing.otpTemplateId !== client.otpTemplateId ? (client.otpTemplateId ?? null) : undefined,
      authFlow: authFlowToBackend(client.authFlow),
      frontChannelLogoutUri: existing.frontChannelLogoutUri !== client.frontChannelLogoutUri ? (client.frontChannelLogoutUri ?? null) : undefined,
      frontChannelLogoutSessionRequired: existing.frontChannelLogoutSessionRequired !== client.frontChannelLogoutSessionRequired ? client.frontChannelLogoutSessionRequired : undefined,
      backChannelLogoutUri: existing.backChannelLogoutUri !== client.backChannelLogoutUri ? (client.backChannelLogoutUri ?? null) : undefined,
      logoUri: existing.logoUri !== client.logoUri ? (client.logoUri ?? null) : undefined,
      policyUri: existing.policyUri !== client.policyUri ? (client.policyUri ?? null) : undefined,
      tosUri: existing.tosUri !== client.tosUri ? (client.tosUri ?? null) : undefined,
      // Patch semantics: omitted leaves the stored flow alone, null clears it.
      consentFlow: sameConsentFlow(existing.consentFlow, client.consentFlow)
        ? undefined
        : consentFlowToBackend(client.consentFlow),
      dpopBoundAccessTokens: existing.dpopBoundAccessTokens !== client.dpopBoundAccessTokens ? client.dpopBoundAccessTokens : undefined,
      dpopSigningAlgs: sameStringSet(existing.dpopSigningAlgs, client.dpopSigningAlgs)
        ? undefined
        : [...(client.dpopSigningAlgs ?? [])],
      // Patch semantics: omitted leaves the stored minimum alone, null clears it back to the
      // floor auth applies to every client.
      dpopMinRsaKeySize: (existing.dpopMinRsaKeySize ?? null) === (client.dpopMinRsaKeySize ?? null)
        ? undefined
        : client.dpopMinRsaKeySize ?? null,
      // Patch semantics: omitted leaves the stored credential alone, null clears it.
      mtlsAuth: sameJsonValue(existing.mtlsAuth, client.mtlsAuth) ? undefined : (client.mtlsAuth ?? null),
      certificateBoundAccessTokens: existing.certificateBoundAccessTokens !== client.certificateBoundAccessTokens
        ? client.certificateBoundAccessTokens
        : undefined,
      jwks: sameJsonValue(existing.jwks, client.jwks) ? undefined : (client.jwks ?? null),
      requireSignedRequestObject: existing.requireSignedRequestObject !== client.requireSignedRequestObject
        ? client.requireSignedRequestObject
        : undefined,
      requirePushedAuthorizationRequests: existing.requirePushedAuthorizationRequests !== client.requirePushedAuthorizationRequests
        ? client.requirePushedAuthorizationRequests
        : undefined,
      // Patch semantics: omitted leaves the stored overrides alone, an empty object clears them.
      themeOverrides: sameJsonValue(nonEmptyOverrides(existing.themeOverrides), nonEmptyOverrides(client.themeOverrides))
        ? undefined
        : (nonEmptyOverrides(client.themeOverrides) ?? {}),
    }, UPDATE_FIELDS[kind]),
  });

  clientSupplementStore.set(entityKey(tenantId, client.id), {
    accessTokenTtl: client.accessTokenTtl,
    refreshTokenTtl: client.refreshTokenTtl ?? existing.refreshTokenTtl ?? DEFAULT_REFRESH_TOKEN_TTL_SECONDS,
    hasPreviousSecret: client.hasPreviousSecret,
  });
}

export async function deleteClient(tenantId: string, clientId: string): Promise<void> {
  await requestVoid('/configuration/clients', {
    method: 'DELETE',
    query: { clientId },
  });

  clientSupplementStore.delete(entityKey(tenantId, clientId));
}

export async function fetchJwks(): Promise<{ keys: Record<string, unknown>[] }> {
  return request<{ keys: Record<string, unknown>[] }>('/configuration/jwks');
}

/** Each stored key's `alg`/`kty` and whether central can sign with it -- what the JWKS view
 * needs to mark a key verify-only, and what the signing-key picker chooses from. */
export async function fetchJwksKeys(): Promise<JwksKeySummary[]> {
  const response = await request<{ keys: JwksKeySummary[] }>('/configuration/jwks/keys');
  return response.keys ?? [];
}

export async function createJwk(jwk: Record<string, unknown>): Promise<void> {
  await requestVoid('/configuration/jwks', { method: 'POST', body: jwk });
}

/** Generates a keypair for `alg` and publishes its public half, keeping the private one.
 * Publishing a key does not put it into use -- a tenant still has to select it, which is what
 * makes a rotation safe: every verifier learns the key before anything signs with it. */
export async function generateJwk(alg: string): Promise<{ kid: string; alg: string }> {
  return request<{ kid: string; alg: string }>('/configuration/jwks/generate', {
    method: 'POST',
    query: { alg },
  });
}

export async function updateJwk(jwk: Record<string, unknown>): Promise<void> {
  await requestVoid('/configuration/jwks', { method: 'PUT', body: jwk });
}

export async function deleteJwk(kid: string): Promise<void> {
  await requestVoid('/configuration/jwks', { method: 'DELETE', query: { kid } });
}

/** The stored overrides, or null when none are configured. */
export async function fetchServerMetadata(): Promise<Record<string, unknown> | null> {
  return (await request<Record<string, unknown> | null>('/configuration/server-metadata')) ?? null;
}

export async function upsertServerMetadata(metadata: Record<string, unknown>): Promise<void> {
  await requestVoid('/configuration/server-metadata', { method: 'POST', body: metadata });
}


export async function fetchEdges(): Promise<Edge[]> {
  const response = await request<EdgesResponse>('/configuration/edges');
  return sortById(response.edges.map(edge => ({
    id: edge.id,
    hasOldKey: edge.hasOldKey ?? false,
    // An edge central has not answered for is read as requiring one, which is what every
    // edge did before the setting existed.
    requireDpopNonce: edge.requireDpopNonce ?? true,
    ...(edge.overloadPolicy ? { overloadPolicy: edge.overloadPolicy } : {}),
  })));
}

export async function setEdgeDpopNonce(edgeId: string, requireDpopNonce: boolean): Promise<void> {
  await requestVoid('/configuration/edges/dpop', {
    method: 'PUT',
    query: { edgeId },
    body: { requireDpopNonce },
  });
}

/** Replaces the edge's overload policy as a whole; `{}` removes it. Central answers 400 with the first thing wrong. */
export async function setEdgeOverloadPolicy(edgeId: string, policy: OverloadPolicy): Promise<void> {
  await requestVoid('/configuration/edges/overload', {
    method: 'PUT',
    query: { edgeId },
    body: policy,
  });
}

export async function registerEdge(id: string): Promise<ServiceKey> {
  const response = await request<ServiceKeyResponseDto>('/configuration/edges', {
    method: 'POST',
    body: { id },
  });
  return { keyId: response.keyId, privateKey: response.privateKey };
}

export async function rotateEdgeKey(edgeId: string): Promise<ServiceKey> {
  const response = await request<ServiceKeyResponseDto>('/configuration/edges/rotate-key', {
    method: 'POST',
    query: { edgeId },
  });
  return { keyId: response.keyId, privateKey: response.privateKey };
}

export async function deleteOldEdgeKey(edgeId: string): Promise<void> {
  await requestVoid('/configuration/edges/old-key', {
    method: 'DELETE',
    query: { edgeId },
  });
}

export async function deleteEdge(edgeId: string): Promise<void> {
  await requestVoid('/configuration/edges', {
    method: 'DELETE',
    query: { edgeId },
  });
}

export async function fetchForms(): Promise<FormRecord[]> {
  const response = await request<{ forms: FormRecord[] }>('/configuration/forms');
  return response.forms;
}

// The states of the built-in forms that central previews. A failure here fails the screen's load
// and is shown as its error; there is no fallback that guesses the states.
export async function fetchFormPreviews(): Promise<FormPreview[]> {
  const response = await request<{ previews: FormPreview[] }>('/configuration/forms/previews');
  return response.previews;
}

export async function fetchLocales(): Promise<Locale[]> {
  const response = await request<{ locales: Locale[] }>('/configuration/locales');
  return response.locales;
}

export async function updateLocales(add: Locale[], remove: string[]): Promise<void> {
  await requestVoid('/configuration/locales', {
    method: 'PUT',
    body: { add, delete: remove },
  });
}

export async function setDefaultLocale(code: string): Promise<void> {
  await requestVoid('/configuration/locales/default', {
    method: 'PUT',
    body: { code },
  });
}

export async function updateForm(
  id: string,
  style: string,
  jsSource: string | null,
  jsCompiled: string | null,
  localizations: Record<string, Record<string, string>>,
  properties: BackendProperty[],
): Promise<void> {
  await requestVoid('/configuration/forms', {
    method: 'PUT',
    body: { id, style, jsSource, jsCompiled, localizations, properties },
  });
}

export async function setActiveFormVersion(id: string, version: number): Promise<void> {
  await requestVoid('/configuration/forms/active', {
    method: 'PUT',
    body: { id, version },
  });
}

export async function fetchThemes(tenantId: string): Promise<ThemeRecord[]> {
  const response = await request<{ themes: ThemeRecord[] }>('/configuration/themes', {
    query: { tenantId },
  });
  return response.themes;
}

export async function createTheme(theme: ThemeRecord): Promise<void> {
  await requestVoid('/configuration/themes', {
    method: 'POST',
    body: { id: theme.id, css: theme.css, tenantId: theme.tenantId, ...(theme.tokens ? { tokens: theme.tokens } : {}) },
  });
}

export async function updateTheme(theme: ThemeRecord): Promise<void> {
  await requestVoid('/configuration/themes', {
    method: 'PUT',
    body: { id: theme.id, css: theme.css, ...(theme.tokens ? { tokens: theme.tokens } : {}) },
  });
}

// The built-in themes a theme can be based on; a failure fails the screen's load and is shown
// as its error; there is no reduced editor.
export async function fetchThemePresets(): Promise<ThemePreset[]> {
  const response = await request<{ presets: ThemePreset[] }>('/configuration/themes/presets');
  return response.presets;
}

// The tokens a theme may set one by one: groups, kinds, descriptions and defaults, defined in central. An older central
// that has no such endpoint answers 404; the editor then has no tokens to offer and says so.
export async function fetchThemeVariables(): Promise<ThemeVariableCatalog> {
  try {
    return await request<ThemeVariableCatalog>('/configuration/themes/variables');
  } catch {
    return { groups: [], variables: [] };
  }
}

// The stylesheet central would serve for a theme with these tokens and this CSS: the preview shows
// exactly what users get, because nobody but central builds it. `tokens` null is a theme stored with CSS only (its CSS alone is
// the stylesheet); `overrides` are a client's own, laid over the theme as auth does, so the answer is that client's
// effective stylesheet. Empty overrides are not sent.
export async function renderTheme(
  tokens: ThemeTokens | null,
  css: string,
  overrides?: ThemeOverrides,
): Promise<{ css: string; generated: string }> {
  const own = overrides && Object.keys(overrides).length > 0 ? overrides : undefined;
  return request<{ css: string; generated: string }>('/configuration/themes/css', {
    method: 'POST',
    body: { ...(tokens ? { tokens } : {}), css, ...(own ? { overrides: own } : {}) },
  });
}

export async function deleteTheme(id: string): Promise<void> {
  await requestVoid(`/configuration/themes?id=${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

export async function fetchOtpTemplates(tenantId: string): Promise<OtpTemplateRecord[]> {
  const response = await request<{ templates: OtpTemplateRecord[] }>('/configuration/challenges/otp-templates', {
    query: { tenantId },
  });
  return response.templates;
}

export async function upsertOtpTemplate(
  id: string,
  tenantId: string,
  localizations: Record<string, string>,
  purpose: string,
  channel: 'sms' | 'email',
): Promise<void> {
  await requestVoid('/configuration/challenges/otp-templates', {
    method: 'PUT',
    body: { id, tenantId, localizations, purpose, channel },
  });
}

export async function deleteOtpTemplate(id: string, tenantId: string, purpose: string, channel: 'sms' | 'email'): Promise<void> {
  await requestVoid('/configuration/challenges/otp-templates', {
    method: 'DELETE',
    body: { id, tenantId, purpose, channel },
  });
}

export type MyPermissionsResponse = {
  resources: Record<string, { permissions: string[] }>;
  // Lets the console hide affordances that only exist outside production,
  // such as revealing a generated temporary password.
  isProd: boolean;
};

export async function fetchMyPermissions(): Promise<MyPermissionsResponse> {
  const baseUrl = resolveBaseUrl();
  const normalizedBase = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  // edge serves this at /permissions/me, outside any resource prefix. In
  // 'prefix' mode the call has to carry EDGE_SESSION, and that cookie is
  // scoped to /central, so the browser would withhold it from a root-level
  // URL and every load would bounce to login -- it's requested under the
  // prefix instead, and an external proxy rewrites /central/permissions/me
  // back to /permissions/me. In 'direct' mode there's no such cookie-path
  // scoping to route around, so it's called outright.
  const url = new URL(apiConfig.consoleMode === 'direct' ? 'permissions/me' : 'central/permissions/me', normalizedBase);
  url.searchParams.set('resource', 'central');

  const response = await fetch(url.toString(), {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'include',
  });

  // This is the first call the console makes on load, so it's usually where an
  // unauthenticated visit is detected. Redirect to login rather than throwing
  // (which the caller would swallow into an empty-permissions "Access Denied").
  if (response.status === 401) {
    return await redirectToLogin(response);
  }

  if (!response.ok) {
    const error = new Error(buildErrorMessage(response.status, await response.text())) as Error & { status: number };
    error.status = response.status;
    throw error;
  }

  return JSON.parse(await response.text()) as MyPermissionsResponse;
}

export async function fetchChallengeSettings(tenantId: string): Promise<ChallengeSettingsRecord | null> {
  const response = await request<{ settings: ChallengeSettingsRecord | null }>('/configuration/challenges/challenge-settings', {
    query: { tenantId },
  });
  return response.settings ?? null;
}

/** Creates the tenant's settings, or replaces the whole record: only the first save of a tenant that has none goes
 * through here, because a PATCH needs a record to merge into. Optional members go as explicit nulls, which central
 * reads as "clear", never as "keep". */
export async function upsertChallengeSettings(settings: ChallengeSettingsRecord): Promise<void> {
  await requestVoid('/configuration/challenges/challenge-settings', {
    method: 'PUT',
    body: {
      tenantId: settings.tenantId,
      phoneRegions: settings.phoneRegions,
      submissionLimits: settings.submissionLimits,
      otpLength: settings.otpLength,
      otpResendAfter: settings.otpResendAfter,
      passkeySettings: settings.passkeySettings ?? null,
      authConversationTtlSeconds: settings.authConversationTtlSeconds,
      sessionTtlSeconds: settings.sessionTtlSeconds,
      sessionIdleTtlSeconds: settings.sessionIdleTtlSeconds ?? null,
      userAgentTtlSeconds: settings.userAgentTtlSeconds,
      ipHeader: settings.ipHeader,
      acrVocabulary: settings.acrVocabulary ?? null,
      postLogoutRedirectUris: settings.postLogoutRedirectUris,
      requireDpopNonce: settings.requireDpopNonce,
      mtlsCertificateHeader: settings.mtlsCertificateHeader ?? null,
      mtlsCertificateEncoding: settings.mtlsCertificateEncoding ?? null,
      signingKeyId: settings.signingKeyId ?? null,
      clientAssertionMaxLifetimeSeconds: settings.clientAssertionMaxLifetimeSeconds ?? null,
      emailRegistration: settings.emailRegistration ?? { mode: 'closed', domains: [], blockSubaddress: false },
      defaultRoles: settings.defaultRoles?.length ? settings.defaultRoles : ['user'],
      logoLight: settings.logoLight ?? null,
      logoDark: settings.logoDark ?? null,
    },
  });
}

/** Merge patch (RFC 7396) of the members named in `changes`: the ones left out keep what is stored, so saving one group
 * cannot overwrite a change another admin made to a different one. `null` clears an optional member; the body carries
 * no other nulls. 404 when the tenant has no settings yet: create them with `upsertChallengeSettings`. */
export async function patchChallengeSettings(tenantId: string, changes: ChallengeSettingsChanges): Promise<void> {
  await requestVoid('/configuration/challenges/challenge-settings', {
    method: 'PATCH',
    body: { tenantId, ...changes },
  });
}

export async function fetchSystemSettings(): Promise<SystemSettingsRecord> {
  return request<SystemSettingsRecord>('/configuration/system-settings');
}

export async function upsertSystemSettings(settings: SystemSettingsRecord): Promise<void> {
  await requestVoid('/configuration/system-settings', {
    method: 'PUT',
    body: settings,
  });
}

