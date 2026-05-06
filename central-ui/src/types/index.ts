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
  scope: string[];
  responseType: 'code' | 'code id_token';
  uiLocales?: string[];
  customParameters?: Record<string, string[]>;
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
  tenantId?: string;  // Tenant scope (clients inherit edge from their tenant)
  authorizationPresets?: AuthorizationPreset[];
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

// ACL Rule types
export type RuleOperator =
  | 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte'
  | 'in' | 'not_in' | 'contains' | 'not_contains'
  | 'starts_with' | 'ends_with' | 'matches';

export interface Rule {
  subject: string;  // "jwt.clearance_level", "userinfo.department", "authorization_details.actions"
  operator: RuleOperator;
  value: string | number | string[];
  pattern?: 'glob' | 'regex';  // For "matches" operator
}

export interface AclRuleNode {
  kind: 'rule';
  rule: Rule;
}

export interface AclRuleGroup {
  kind: 'all' | 'any';
  children: AclRuleTree[];
}

export type AclRuleTree = AclRuleNode | AclRuleGroup;

export interface EndpointRule {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | '*';
  path: string;  // "/api/users/:id" or "/api/users/*"
}

export interface EndpointAclRule extends EndpointRule {
  fetchUserInfo: boolean;
  allowRules: AclRuleGroup;
  denyRules: AclRuleGroup;
  injectHeaders: Record<string, string>;
}

export interface AclRules {
  endpoints: EndpointAclRule[];
}

// Permission
export interface Permission {
  id: string;  // Renamed from "permission"
  description: Record<string, string>;
  endpointIds?: ResourceEndpointId[];
  resource?: string;
  aclRules?: AclRules;
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
  allowRules: AclRuleGroup;
  denyRules: AclRuleGroup;
  injectHeaders: Record<string, string>;
}

export interface Resource {
  id: number;
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

// Form state
export type FormMode = 'create' | 'edit';

// Table sort
export interface SortConfig {
  field: string;
  direction: 'asc' | 'desc';
}

