// Tenant
export interface Tenant {
  id: string;
  name: string;
  description?: string;
  edgeId?: string | null;
}

// Authorization Preset
export interface AuthorizationPreset {
  id: string;
  description: string;
  redirectUri: string;
  postLoginRedirectUri: string;
  postLogoutRedirectUri?: string;
  scope: string[];
  responseType: 'code' | 'code id_token';
  uiLocales?: string[];
  customParameters?: Record<string, string[]>;
  cookieDomain?: string;
  cookiePath?: string;
}

// Authentication flow (cards: credential -> factor -> factor)
export type PrimaryCredential = 'email' | 'phone' | 'login';
export type RegistrationCredential = 'email' | 'phone';
export type OtpType = 'sms' | 'email';
export type AuthFactorType = 'otp' | 'password' | 'passkeyEnroll';

// A challenge the user can pass; used as the key/value type of the equivalences map.
export type PassedAuthFactor = 'otp' | 'password' | 'passkey';

export interface AuthFactor {
  type: AuthFactorType;
  required: boolean;
}

export interface AuthFlow {
  primaryCredentials: PrimaryCredential[];  // first card: one or more options the user can pick
  otpType: OtpType;                          // OTP delivery channel; selected for login + password flows
  inlinePassword: boolean;                  // first card: ask for password inline
  passkey: boolean;                         // first card: offer passkey
  factors: AuthFactor[];                    // subsequent challenge cards for the primary flow (0-2)
  passkeyFactors: AuthFactor[];             // subsequent challenge cards for the passkey flow (0-1)
  equivalents: Record<string, string[]>;    // challenge equivalences: a passed key-factor satisfies each listed value-factor
}

// Registration flow (self-service account creation)
export type RegistrationStepType = 'otp' | 'setPassword' | 'passkeyEnroll';

export interface RegistrationStep {
  type: RegistrationStepType;
}

export interface RegistrationFlow {
  credential: RegistrationCredential;     // entry credential whose ownership is verified
  steps: RegistrationStep[];  // ordered steps the user passes before the account is created
  roleIds: string[];          // roles granted to the account on creation
}

// Consent screen shown before an authorization code is issued. Absent for first-party
// clients, which never prompt.
export interface ConsentFlow {
  allowPartial: boolean;  // let the user deselect optional scopes and grant a subset
  rememberDurationDays: number | null;  // how long a grant is reused; null = until revoked
}

// OAuth Client
export interface OAuthClient {
  id: string;
  clientName: Record<string, string>;
  redirectUris: string[];
  scope: string[];
  hasPreviousSecret: boolean;
  accessTokenTtl: number;
  refreshTokenTtl?: number;
  permissions: string[];
  theme: string;
  otpTemplateId?: string | null;
  authFlow: AuthFlow | null;
  registrationFlow: RegistrationFlow | null;
  frontChannelLogoutUri?: string | null;
  frontChannelLogoutSessionRequired: boolean;
  backChannelLogoutUri?: string | null;
  logoUri?: string | null;
  policyUri?: string | null;
  tosUri?: string | null;
  consentFlow?: ConsentFlow | null;
  tenantId?: string;  // Tenant scope (clients inherit edge from their tenant)
  authorizationPresets?: AuthorizationPreset[];
}

// OTP Template
export interface OtpTemplateRecord {
  id: string;
  tenantId: string;
  localizations: Record<string, string>;
  purpose: string;
  channel: 'sms' | 'email';
}

export interface RateLimit {
  maxAttempts: number;
  windowSeconds: number;
}

export interface SubmissionLimits {
  otpRequest: RateLimit[];
  otpSubmit: RateLimit[];
  passwordSubmit: RateLimit[];
  passkeyAssertion: RateLimit[];
  banDurationSeconds: number;
}

// Passkey (WebAuthn) settings
export interface PasskeySettings {
  rpId: string;
  rpName: string;
  origins: string[];
  userVerification: string;
}

// Challenge Settings
export interface ChallengeSettingsRecord {
  tenantId: string;
  allowedPrefixes: string[];
  submissionLimits: SubmissionLimits;
  otpLength: number;
  otpResendAfter: number;
  passkeySettings?: PasskeySettings | null;
  authConversationTtlSeconds: number;
  sessionTtlSeconds: number;
  sessionIdleTtlSeconds?: number | null;
  userAgentTtlSeconds: number;
  ipHeader: string;
  acrVocabulary?: Record<string, string[]> | null;
  postLogoutRedirectUris: string[];
}

// Global (non-tenant-scoped) password policy
export interface SystemSettingsRecord {
  passwordRegex: string;
  passwordHistorySize: number;
  passwordNumDifferent: number;
  identityProviderLogo?: string | null;
}

// A registered passkey credential for a user
export interface PasskeyInfo {
  id: string;
  name?: string | null;
  deviceType: string;
  transports: string[];
  backedUp: boolean;
  backupEligible: boolean;
  lastUsedAt?: string | null;
  createdAt: string;
}

// OAuth Scope
export interface OAuthScope {
  id: string;
  description: Record<string, string>; // e.g., { "en": "User profile data", "ru": "Данные профиля" }
  claims: OAuthClaim[];
}

// OAuth Claim
export interface OAuthClaim {
  id: string;
  scopeId: string;
  description: Record<string, string>;
}

// RFC 9396 authorization detail type: a registered `type` value clients may request in
// `authorization_details`, and the JSON Schema (2020-12) its objects are validated against.
export interface AuthorizationDetailType {
  type: string;
  description: Record<string, string>;
  schema: Record<string, unknown>;
}

// CEL-based authorization and request injection
export type InjectTarget = 'header' | 'query' | 'body';

export interface InjectRule {
  target: InjectTarget;
  name: string;
  expression: string;
}

// Permission
export interface Permission {
  id: string;  // Renamed from "permission"
  description: Record<string, string>;
  endpointIds?: ResourceEndpointId[];
  resource?: string;
  deprecated?: boolean;
}

// Helper to extract category from permission string
export function getPermissionCategory(permissionId: string): string {
  const parts = permissionId.split(':');
  return parts.length > 1 ? parts[0] : 'other';
}

// Role
export interface Role {
  id: string;
  description: Record<string, string>;
  active: boolean;
  permissions: Permission[];
  deletionInitiatedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export type ResourceEndpointId = string | number;

export interface ResourceEndpoint {
  id: ResourceEndpointId;
  method: string;
  path: string;
  fetchUserInfo: boolean;
  allow?: string;
  inject: InjectRule[];
  stepUpCondition?: string;
  stepUpAcr?: string;
  maxAge?: number;
}

export interface Resource {
  resourceId: string;
  resource: string;
  audience: string[];
  endpoints: ResourceEndpoint[];
  // Internal resources carry a secret edge authenticates with (Basic auth) instead
  // of forwarding the caller's own access token. Public resources have neither.
  hasSecret: boolean;
  hasPreviousSecret: boolean;
}

// Pagination
export interface PaginationParams {
  offset: number;
  limit: number;
}

export interface PaginatedResponse<T> {
  items: T[];
  total: number;
  offset: number;
  limit: number;
}

// Edge
export interface Edge {
  id: string;
  hasOldKey?: boolean;  // True if old key exists during rotation
  revocationCacheSize: number;  // Revocations the edge keeps in memory
}

export interface ServiceKey {
  keyId: string;
  privateKey: string;
}

// User
export interface User {
  id: string;
  email?: string;
  phone?: string;
  login?: string;
  claims: Record<string, unknown>;
}

// Role assignment for a user in a specific tenant
export interface UserRoleAssignment {
  tenantId: string;
  roleId: string;
}

export type UserSearchField = 'id' | 'email' | 'phone' | 'login';

// Theme
export interface ThemeRecord {
  id: string;
  css: string;
  tenantId: string | null;
}

// Backend properties
export interface BooleanProperty {
  type: 'BooleanProperty';
  name: string;
}

export interface StringArrayProperty {
  type: 'StringArrayProperty';
  name: string;
  allowedValues: string[];
}

export interface NumberProperty {
  type: 'NumberProperty';
  name: string;
  default: number;
  min?: number;
  max?: number;
}

export type BackendProperty = BooleanProperty | StringArrayProperty | NumberProperty;

// Auth Forms
export interface FormRecord {
  id: string;
  version: number;
  active: boolean;
  style: string;
  jsSource: string | null;
  jsCompiled: string | null;
  localizations: Record<string, Record<string, string>>;
  properties: BackendProperty[];
}

export interface Locale {
  code: string;
  name: string;
  isDefault: boolean;
  active: boolean;
}

// Form state
export type FormMode = 'create' | 'edit';

// Table sort
export interface SortConfig {
  field: string;
  direction: 'asc' | 'desc';
}

export interface SessionClientEntry {
  clientId: string;
  enteredAt: string;
  expiresAt: string;
}

export interface UserSession {
  clients: SessionClientEntry[];
  createdAt?: string;
  platform?: 'ios' | 'android' | 'desktop';
  os?: string;
  browser?: string;
  version?: string;
}

