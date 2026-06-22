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
  scope: string[];
  responseType: 'code' | 'code id_token';
  uiLocales?: string[];
  customParameters?: Record<string, string[]>;
  cookieDomain?: string;
  cookiePath?: string;
}

// Authentication flow (cards: credential -> factor -> factor)
export type PrimaryCredential = 'email' | 'phone' | 'login';
export type AuthFactorType = 'otp' | 'password';

export interface AuthFactor {
  type: AuthFactorType;
  required: boolean;
}

export interface AuthFlow {
  primaryCredentials: PrimaryCredential[];  // first card: one or more options the user can pick
  inlinePassword: boolean;                  // first card: ask for password inline
  passkey: boolean;                         // first card: offer passkey
  factors: AuthFactor[];                    // subsequent challenge cards for the primary flow (0-2)
  passkeyFactors: AuthFactor[];             // subsequent challenge cards for the passkey flow (0-1)
}

// OAuth Client
export interface OAuthClient {
  id: string;
  clientName: string;
  redirectUris: string[];
  scope: string[];
  externalAudience: string[];
  hasPreviousSecret: boolean;
  accessTokenTtl: number;
  permissions: string[];
  theme: string;
  otpTemplateId?: string | null;
  authFlow: AuthFlow | null;
  tenantId?: string;  // Tenant scope (clients inherit edge from their tenant)
  authorizationPresets?: AuthorizationPreset[];
}

// OTP Template
export interface OtpTemplateRecord {
  id: string;
  tenantId: string;
  localizations: Record<string, string>;
}

export interface RateLimit {
  maxAttempts: number;
  windowSeconds: number;
}

export interface SubmissionLimits {
  otpRequest: RateLimit[];
  otpSubmit: RateLimit[];
  passwordSubmit: RateLimit[];
  banDurationSeconds: number;
}

// Challenge Settings
export interface ChallengeSettingsRecord {
  tenantId: string;
  allowedPrefixes: string[];
  passwordRegex?: string;
  submissionLimits: SubmissionLimits;
  otpLength: number;
  otpResendAfter: number;
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
}

export interface Resource {
  id: number;
  alias: string;
  resource: string;
  endpoints: ResourceEndpoint[];
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

export interface UserSession {
  clientId: string;
  userAgent?: string;
  createdAt?: string;
  platform: 'ios' | 'android' | 'desktop' | 'unknown';
  browser?: string;
  version?: string;
}

