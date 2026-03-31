// Export main application component
export { VersolaAdmin } from './components/admin-app';

// Export all components for standalone use
export { VersolaLogo } from './components/versola-logo';
export { TenantSelector } from './components/tenant-selector';
export { ContentHeader } from './components/content-header';
export { VersolaNavigation } from './components/navigation';
export { VersolaClientsList } from './components/clients-list';
export { VersolaClientForm } from './components/client-form';
export { VersolaScopesList } from './components/scopes-list';
export { VersolaScopeForm } from './components/scope-form';
export { VersolaPermissionsList } from './components/permissions-list';
export { VersolaPermissionForm } from './components/permission-form';
export { VersolaResourcesList } from './components/resources-list';
export { VersolaRolesList } from './components/roles-list';
export { VersolaRoleForm } from './components/role-form';
export { VersolaPagination } from './components/pagination';

// Export types
export type {
  Tenant,
  OAuthClient,
  OAuthScope,
  OAuthClaim,
  Permission,
  Resource,
  ResourceEndpoint,
  Role,
  Rule,
  AclRuleNode,
  AclRuleGroup,
  AclRuleTree,
} from './types';
export type { NavItem } from './components/navigation';

