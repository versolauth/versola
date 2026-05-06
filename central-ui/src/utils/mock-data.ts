import { OAuthClient, OAuthScope, OAuthClaim, Permission, Role, Rule, Tenant, getPermissionCategory } from '../types';

function toOrAndGroups(rules: Rule[]) {
  return {
    kind: 'any' as const,
    children: rules.length > 0
      ? [{ kind: 'all' as const, children: rules.map(rule => ({ kind: 'rule' as const, rule })) }]
      : [],
  };
}

export const mockTenants: Tenant[] = [
  {
    id: 'default',
    name: 'Default Tenant',
    description: 'Default organization',
  },
  {
    id: 'acme-corp',
    name: 'Acme Corporation',
    description: 'Main production environment',
  },
  {
    id: 'globex',
    name: 'Globex Corporation',
    description: 'Global expansion division',
  },
  {
    id: 'initech',
    name: 'Initech',
    description: 'IT solutions provider',
  },
  {
    id: 'umbrella',
    name: 'Umbrella Corporation',
    description: 'Pharmaceutical research',
  },
  {
    id: 'wayne-enterprises',
    name: 'Wayne Enterprises',
    description: 'Multi-industry conglomerate',
  },
  {
    id: 'stark-industries',
    name: 'Stark Industries',
    description: 'Advanced technology research',
  },
  {
    id: 'hooli',
    name: 'Hooli',
    description: 'Technology company',
  },
  {
    id: 'pied-piper',
    name: 'Pied Piper',
    description: 'Compression algorithms',
  },
  {
    id: 'test-org',
    name: 'Test Organization',
    description: 'Testing and development',
  },
  {
    id: 'demo-company',
    name: 'Demo Company',
    description: 'Demo and sandbox environment',
  },
  {
    id: 'staging-env',
    name: 'Staging Environment',
    description: 'Pre-production testing',
  },
  {
    id: 'dev-sandbox',
    name: 'Developer Sandbox',
    description: 'Development playground',
  },
];

export const mockClients: OAuthClient[] = [
  {
    id: 'web-app',
    clientName: 'Web Application',
    redirectUris: ['https://app.example.com/callback', 'https://app.example.com/silent-renew'],
    scope: ['openid', 'profile', 'email', 'offline_access'],
    externalAudience: ['api.example.com'],
    hasPreviousSecret: false,
    accessTokenTtl: 3600,
    permissions: ['clients:read', 'scopes:read'],
  },
  {
    id: 'mobile-app',
    clientName: 'Mobile Application',
    redirectUris: ['com.example.app://callback'],
    scope: ['openid', 'profile', 'offline_access'],
    externalAudience: [],
    hasPreviousSecret: true,
    accessTokenTtl: 7200,
    permissions: ['users:read'],
  },
  {
    id: 'admin-dashboard',
    clientName: 'Admin Dashboard',
    redirectUris: ['https://admin.example.com/auth/callback'],
    scope: ['openid', 'profile', 'email'],
    externalAudience: ['admin-api.example.com'],
    hasPreviousSecret: false,
    accessTokenTtl: 1800,
    permissions: ['clients:read', 'clients:write', 'clients:delete', 'scopes:read', 'scopes:write', 'roles:read', 'roles:write'],
  },
  {
    id: 'spa-frontend',
    clientName: 'SPA Frontend',
    redirectUris: ['https://spa.example.com/callback'],
    scope: ['openid', 'profile', 'email'],
    externalAudience: [],
    hasPreviousSecret: false,
    accessTokenTtl: 900,
    permissions: ['users:read'],
  },
  {
    id: 'ios-app',
    clientName: 'iOS Application',
    redirectUris: ['com.example.ios://callback'],
    scope: ['openid', 'profile', 'offline_access'],
    externalAudience: ['mobile-api.example.com'],
    hasPreviousSecret: false,
    accessTokenTtl: 7200,
    permissions: ['users:read'],
  },
  {
    id: 'android-app',
    clientName: 'Android Application',
    redirectUris: ['com.example.android://callback'],
    scope: ['openid', 'profile', 'offline_access'],
    externalAudience: ['mobile-api.example.com'],
    hasPreviousSecret: false,
    accessTokenTtl: 7200,
    permissions: ['users:read'],
  },
  {
    id: 'api-gateway',
    clientName: 'API Gateway',
    redirectUris: ['https://gateway.example.com/callback'],
    scope: ['openid'],
    externalAudience: ['internal-services'],
    hasPreviousSecret: true,
    accessTokenTtl: 300,
    permissions: ['clients:read'],
  },
  {
    id: 'monitoring-service',
    clientName: 'Monitoring Service',
    redirectUris: ['https://monitoring.example.com/callback'],
    scope: ['openid'],
    externalAudience: [],
    hasPreviousSecret: false,
    accessTokenTtl: 600,
    permissions: ['users:read', 'clients:read'],
  },
  {
    id: 'test-client',
    clientName: 'Test Client',
    redirectUris: ['http://localhost:3000/callback'],
    scope: ['openid', 'profile', 'email', 'offline_access'],
    externalAudience: [],
    hasPreviousSecret: false,
    accessTokenTtl: 3600,
    permissions: ['users:read', 'clients:read', 'scopes:read'],
  },
  {
    id: 'cli-tool',
    clientName: 'CLI Tool',
    redirectUris: ['http://localhost:8080/callback'],
    scope: ['openid', 'profile'],
    externalAudience: ['api.example.com'],
    hasPreviousSecret: true,
    accessTokenTtl: 1800,
    permissions: ['users:read', 'clients:read'],
  },
  {
    id: 'third-party-integration',
    clientName: 'Third Party Integration',
    redirectUris: ['https://partner.example.com/callback'],
    scope: ['openid', 'email'],
    externalAudience: [],
    hasPreviousSecret: false,
    accessTokenTtl: 3600,
    permissions: ['users:read'],
  },
  {
    id: 'data-pipeline',
    clientName: 'Data Pipeline Service',
    redirectUris: ['https://pipeline.example.com/callback'],
    scope: ['openid'],
    externalAudience: ['data-warehouse'],
    hasPreviousSecret: true,
    accessTokenTtl: 600,
    permissions: ['users:read'],
  },
];

const mockClaims: OAuthClaim[] = [
  { id: 'sub', scopeId: 'openid', description: { en: 'Subject identifier', ru: 'Идентификатор субъекта' } },
  { id: 'name', scopeId: 'profile', description: { en: 'Full name', ru: 'Полное имя' } },
  { id: 'given_name', scopeId: 'profile', description: { en: 'Given name', ru: 'Имя' } },
  { id: 'family_name', scopeId: 'profile', description: { en: 'Family name', ru: 'Фамилия' } },
  { id: 'middle_name', scopeId: 'profile', description: { en: 'Middle name', ru: 'Отчество' } },
  { id: 'nickname', scopeId: 'profile', description: { en: 'Nickname', ru: 'Никнейм' } },
  { id: 'preferred_username', scopeId: 'profile', description: { en: 'Preferred username', ru: 'Предпочитаемое имя пользователя' } },
  { id: 'picture', scopeId: 'profile', description: { en: 'Profile picture URL', ru: 'URL фотографии профиля' } },
  { id: 'birthdate', scopeId: 'profile', description: { en: 'Birthdate', ru: 'Дата рождения' } },
  { id: 'gender', scopeId: 'profile', description: { en: 'Gender', ru: 'Пол' } },
  { id: 'email', scopeId: 'email', description: { en: 'Email address', ru: 'Адрес электронной почты' } },
  { id: 'email_verified', scopeId: 'email', description: { en: 'Email verified flag', ru: 'Флаг подтверждения email' } },
  { id: 'phone_number', scopeId: 'phone', description: { en: 'Phone number', ru: 'Номер телефона' } },
  { id: 'phone_number_verified', scopeId: 'phone', description: { en: 'Phone verified flag', ru: 'Флаг подтверждения телефона' } },
];

export const mockScopes: OAuthScope[] = [
  {
    id: 'openid',
    description: { en: 'OpenID Connect authentication', ru: 'Аутентификация OpenID Connect' },
    claims: mockClaims.filter(c => c.scopeId === 'openid'),
  },
  {
    id: 'profile',
    description: { en: 'User profile data', ru: 'Данные профиля пользователя' },
    claims: mockClaims.filter(c => c.scopeId === 'profile'),
  },
  {
    id: 'email',
    description: { en: 'Email address', ru: 'Адрес электронной почты' },
    claims: mockClaims.filter(c => c.scopeId === 'email'),
  },
  {
    id: 'phone',
    description: { en: 'Phone number', ru: 'Номер телефона' },
    claims: mockClaims.filter(c => c.scopeId === 'phone'),
  },
  {
    id: 'offline_access',
    description: { en: 'Offline access (refresh tokens)', ru: 'Офлайн доступ (refresh токены)' },
    claims: [],
  },
];

export const mockPermissions: Permission[] = [
  {
    id: 'users:read',
    description: { en: 'Read user data', ru: 'Чтение данных пользователей' },
    resource: 'https://api.example.com',
    aclRules: {
      endpoints: [
        {
          method: 'GET',
          path: '/api/users',
          fetchUserInfo: false,
          allowRules: toOrAndGroups([
            { subject: 'authorization_details.actions', operator: 'contains', value: 'read' },
            { subject: 'jwt.clearance_level', operator: 'gte', value: 1 },
          ]),
          denyRules: toOrAndGroups([]),
          injectHeaders: {
            'X-User-ID': 'jwt.sub',
            'X-Clearance-Level': 'jwt.clearance_level',
          },
        },
        {
          method: 'GET',
          path: '/api/users/:id',
          fetchUserInfo: false,
          allowRules: toOrAndGroups([
            { subject: 'authorization_details.actions', operator: 'contains', value: 'read' },
            { subject: 'jwt.clearance_level', operator: 'gte', value: 1 },
          ]),
          denyRules: toOrAndGroups([]),
          injectHeaders: {
            'X-User-ID': 'jwt.sub',
            'X-Clearance-Level': 'jwt.clearance_level',
          },
        },
      ],
    },
  },
  {
    id: 'users:read:managed',
    description: { en: 'Read users you manage', ru: 'Чтение управляемых пользователей' },
    resource: 'https://api.example.com',
    aclRules: {
      endpoints: [
        {
          method: 'GET',
          path: '/api/users/:id',
          fetchUserInfo: true,
          allowRules: toOrAndGroups([
            { subject: 'authorization_details.actions', operator: 'contains', value: 'read' },
            { subject: 'userinfo.department', operator: 'in', value: ['engineering', 'hr'] },
            { subject: 'jwt.clearance_level', operator: 'gte', value: 2 },
          ]),
          denyRules: toOrAndGroups([
            { subject: 'userinfo.status', operator: 'eq', value: 'suspended' },
          ]),
          injectHeaders: {
            'X-User-Department': 'userinfo.department',
            'X-Allowed-User-IDs': 'authorization_details.allowed_identifiers',
          },
        },
      ],
    },
  },
  {
    id: 'users:write',
    description: { en: 'Create and update users', ru: 'Создание и изменение пользователей' },
    resource: 'https://api.example.com',
    aclRules: {
      endpoints: [
        {
          method: 'POST',
          path: '/api/users',
          fetchUserInfo: false,
          allowRules: toOrAndGroups([
            { subject: 'authorization_details.actions', operator: 'contains', value: 'update' },
            { subject: 'jwt.clearance_level', operator: 'gte', value: 2 },
          ]),
          denyRules: toOrAndGroups([]),
          injectHeaders: {
            'X-User-ID': 'jwt.sub',
          },
        },
        {
          method: 'PUT',
          path: '/api/users/:id',
          fetchUserInfo: false,
          allowRules: toOrAndGroups([
            { subject: 'authorization_details.actions', operator: 'contains', value: 'update' },
            { subject: 'jwt.clearance_level', operator: 'gte', value: 2 },
          ]),
          denyRules: toOrAndGroups([]),
          injectHeaders: {
            'X-User-ID': 'jwt.sub',
          },
        },
        {
          method: 'PATCH',
          path: '/api/users/:id',
          fetchUserInfo: false,
          allowRules: toOrAndGroups([
            { subject: 'authorization_details.actions', operator: 'contains', value: 'update' },
            { subject: 'jwt.clearance_level', operator: 'gte', value: 2 },
          ]),
          denyRules: toOrAndGroups([]),
          injectHeaders: {
            'X-User-ID': 'jwt.sub',
          },
        },
      ],
    },
  },
  {
    id: 'users:delete',
    description: { en: 'Delete users', ru: 'Удаление пользователей' },
    resource: 'https://api.example.com',
    aclRules: {
      endpoints: [
        {
          method: 'DELETE',
          path: '/api/users/:id',
          fetchUserInfo: false,
          allowRules: toOrAndGroups([
            { subject: 'jwt.clearance_level', operator: 'gte', value: 3 },
          ]),
          denyRules: toOrAndGroups([]),
          injectHeaders: {},
        },
      ],
    },
  },
  {
    id: 'roles:read',
    description: { en: 'Read roles', ru: 'Чтение ролей' },
    resource: 'https://api.example.com',
    aclRules: {
      endpoints: [
        {
          method: 'GET',
          path: '/api/roles',
          fetchUserInfo: false,
          allowRules: toOrAndGroups([]),
          denyRules: toOrAndGroups([]),
          injectHeaders: {},
        },
        {
          method: 'GET',
          path: '/api/roles/:id',
          fetchUserInfo: false,
          allowRules: toOrAndGroups([]),
          denyRules: toOrAndGroups([]),
          injectHeaders: {},
        },
      ],
    },
  },
  {
    id: 'clients:read',
    description: { en: 'Read OAuth clients', ru: 'Чтение OAuth клиентов' },
    resource: 'https://api.example.com',
    aclRules: {
      endpoints: [
        {
          method: 'GET',
          path: '/v1/configuration/clients',
          fetchUserInfo: false,
          allowRules: toOrAndGroups([]),
          denyRules: toOrAndGroups([]),
          injectHeaders: {},
        },
      ],
    },
  },
];

export const mockRoles: Role[] = [
  {
    id: 'admin',
    description: { en: 'Full administrative access', ru: 'Полный административный доступ' },
    active: true,
    permissions: mockPermissions.slice(0, 4),  // First 4 permissions
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
  },
  {
    id: 'user',
    description: { en: 'Standard user access', ru: 'Стандартный пользовательский доступ' },
    active: true,
    permissions: mockPermissions.filter(p => p.id === 'users:read'),
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
  },
  {
    id: 'readonly',
    description: { en: 'Read-only access', ru: 'Доступ только для чтения' },
    active: true,
    permissions: mockPermissions.filter(p => p.id.endsWith(':read')),
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
  },
  {
    id: 'support',
    description: { en: 'Customer support access', ru: 'Доступ службы поддержки' },
    active: true,
    permissions: mockPermissions.filter(p =>
      p.id === 'users:read' || p.id === 'users:write'
    ),
    createdAt: '2024-02-15T00:00:00Z',
    updatedAt: '2024-02-15T00:00:00Z',
  },
  {
    id: 'moderator',
    description: { en: 'Content moderation access', ru: 'Доступ модератора' },
    active: true,
    permissions: mockPermissions.filter(p =>
      getPermissionCategory(p.id) === 'users' || p.id === 'clients:read'
    ),
    createdAt: '2024-02-20T00:00:00Z',
    updatedAt: '2024-02-20T00:00:00Z',
  },
  {
    id: 'developer',
    description: { en: 'Developer access for testing', ru: 'Доступ разработчика для тестирования' },
    active: true,
    permissions: mockPermissions.filter(p =>
      getPermissionCategory(p.id) === 'clients'
    ),
    createdAt: '2024-03-01T00:00:00Z',
    updatedAt: '2024-03-01T00:00:00Z',
  },
  {
    id: 'security-auditor',
    description: { en: 'Security audit read-only access', ru: 'Доступ аудитора безопасности' },
    active: true,
    permissions: mockPermissions.filter(p => p.id.endsWith(':read')),
    createdAt: '2024-03-05T00:00:00Z',
    updatedAt: '2024-03-05T00:00:00Z',
  },
  {
    id: 'api-manager',
    description: { en: 'API and client management', ru: 'Управление API и клиентами' },
    active: true,
    permissions: mockPermissions.filter(p =>
      getPermissionCategory(p.id) === 'clients'
    ),
    createdAt: '2024-03-10T00:00:00Z',
    updatedAt: '2024-03-10T00:00:00Z',
  },
  {
    id: 'user-manager',
    description: { en: 'User management only', ru: 'Только управление пользователями' },
    active: true,
    permissions: mockPermissions.filter(p => getPermissionCategory(p.id) === 'users'),
    createdAt: '2024-03-12T00:00:00Z',
    updatedAt: '2024-03-12T00:00:00Z',
  },
  {
    id: 'guest',
    description: { en: 'Limited guest access', ru: 'Ограниченный гостевой доступ' },
    active: true,
    permissions: [mockPermissions.find(p => p.id === 'users:read')!],
    createdAt: '2024-03-15T00:00:00Z',
    updatedAt: '2024-03-15T00:00:00Z',
  },
  {
    id: 'inactive-role',
    description: { en: 'Deprecated role', ru: 'Устаревшая роль' },
    active: false,
    permissions: [],
    deletionInitiatedAt: '2024-03-20T00:00:00Z',
    createdAt: '2024-01-15T00:00:00Z',
    updatedAt: '2024-03-20T00:00:00Z',
  },
  {
    id: 'super-admin',
    description: { en: 'Super administrator with all permissions', ru: 'Суперадминистратор со всеми правами' },
    active: true,
    permissions: mockPermissions,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-03-25T00:00:00Z',
  },
];

