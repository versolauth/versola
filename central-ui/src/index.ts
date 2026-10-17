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
export { VersolaAuthorizationDetailTypesList } from './components/authorization-detail-types-list';
export { VersolaAuthorizationDetailTypeForm } from './components/authorization-detail-type-form';
export { VersolaPermissionsList } from './components/permissions-list';
export { VersolaResourcesList } from './components/resources-list';
export { VersolaRolesList } from './components/roles-list';
export { VersolaPagination } from './components/pagination';
export { VersolaSidePanel } from './components/side-panel';
export { VersolaDataTable } from './components/data-table';

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
  InjectRule,
  InjectTarget,
} from './types';
export type { NavItem } from './components/navigation';

