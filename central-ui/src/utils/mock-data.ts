import { OAuthClient, OAuthScope, OAuthClaim, Permission, Role, Tenant, getPermissionCategory } from '../types';
import { createDefaultAuthFlow } from './helpers';

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

const baseClients: Omit<OAuthClient, 'authFlow'>[] = [
  {
    id: 'web-app',
    clientName: 'Web Application',
    redirectUris: ['https://app.example.com/callback', 'https://app.example.com/silent-renew', 'http://localhost:3000'],
    scope: ['openid', 'profile', 'email', 'offline_access'],
    externalAudience: ['api-gateway'],
    hasPreviousSecret: false,
    accessTokenTtl: 3600,
    permissions: ['clients:read', 'scopes:read'],
    theme: 'default',
  },
  {
    id: 'mobile-app',
    clientName: 'Mobile Application',
    redirectUris: ['com.example.app://callback', 'http://localhost:3000'],
    scope: ['openid', 'profile', 'offline_access'],
    externalAudience: [],
    hasPreviousSecret: true,
    accessTokenTtl: 7200,
    permissions: ['users:read'],
    theme: 'default',
  },
  {
    id: 'admin-dashboard',
    clientName: 'Admin Dashboard',
    redirectUris: ['https://admin.example.com/auth/callback', 'http://localhost:3000'],
    scope: ['openid', 'profile', 'email'],
    externalAudience: ['api-gateway'],
    hasPreviousSecret: false,
    accessTokenTtl: 1800,
    permissions: ['clients:read', 'clients:write', 'clients:delete', 'scopes:read', 'scopes:write', 'roles:read', 'roles:write'],
    theme: 'default',
  },
  {
    id: 'spa-frontend',
    clientName: 'SPA Frontend',
    redirectUris: ['https://spa.example.com/callback', 'http://localhost:3000'],
    scope: ['openid', 'profile', 'email'],
    externalAudience: [],
    hasPreviousSecret: false,
    accessTokenTtl: 900,
    permissions: ['users:read'],
    theme: 'default',
  },
  {
    id: 'ios-app',
    clientName: 'iOS Application',
    redirectUris: ['com.example.ios://callback', 'http://localhost:3000'],
    scope: ['openid', 'profile', 'offline_access'],
    externalAudience: ['api-gateway'],
    hasPreviousSecret: false,
    accessTokenTtl: 7200,
    permissions: ['users:read'],
    theme: 'default',
  },
  {
    id: 'android-app',
    clientName: 'Android Application',
    redirectUris: ['com.example.android://callback', 'http://localhost:3000'],
    scope: ['openid', 'profile', 'offline_access'],
    externalAudience: ['api-gateway'],
    hasPreviousSecret: false,
    accessTokenTtl: 7200,
    permissions: ['users:read'],
    theme: 'default',
  },
  {
    id: 'api-gateway',
    clientName: 'API Gateway',
    redirectUris: ['https://gateway.example.com/callback', 'http://localhost:3000'],
    scope: ['openid'],
    externalAudience: ['monitoring-service', 'data-pipeline'],
    hasPreviousSecret: true,
    accessTokenTtl: 300,
    permissions: ['clients:read'],
    theme: 'default',
  },
  {
    id: 'monitoring-service',
    clientName: 'Monitoring Service',
    redirectUris: ['https://monitoring.example.com/callback', 'http://localhost:3000'],
    scope: ['openid'],
    externalAudience: [],
    hasPreviousSecret: false,
    accessTokenTtl: 600,
    permissions: ['users:read', 'clients:read'],
    theme: 'default',
  },
  {
    id: 'test-client',
    clientName: 'Test Client',
    redirectUris: ['http://localhost:3000/callback', 'http://localhost:3000'],
    scope: ['openid', 'profile', 'email', 'offline_access'],
    externalAudience: [],
    hasPreviousSecret: false,
    accessTokenTtl: 3600,
    permissions: ['users:read', 'clients:read', 'scopes:read'],
    theme: 'default',
  },
  {
    id: 'cli-tool',
    clientName: 'CLI Tool',
    redirectUris: ['http://localhost:8080/callback', 'http://localhost:3000'],
    scope: ['openid', 'profile'],
    externalAudience: ['api-gateway'],
    hasPreviousSecret: true,
    accessTokenTtl: 1800,
    permissions: ['users:read', 'clients:read'],
    theme: 'default',
  },
  {
    id: 'third-party-integration',
    clientName: 'Third Party Integration',
    redirectUris: ['https://partner.example.com/callback', 'http://localhost:3000'],
    scope: ['openid', 'email'],
    externalAudience: [],
    hasPreviousSecret: false,
    accessTokenTtl: 3600,
    permissions: ['users:read'],
    theme: 'default',
  },
  {
    id: 'data-pipeline',
    clientName: 'Data Pipeline Service',
    redirectUris: ['https://pipeline.example.com/callback', 'http://localhost:3000'],
    scope: ['openid'],
    externalAudience: ['monitoring-service'],
    hasPreviousSecret: true,
    accessTokenTtl: 600,
    permissions: ['users:read'],
    theme: 'default',
  },
];

export const mockClients: OAuthClient[] = baseClients.map(client => ({
  ...client,
  authFlow: createDefaultAuthFlow(),
}));

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
  { id: 'users:read', description: { en: 'Read user data', ru: 'Чтение данных пользователей' }, resource: 'https://api.example.com' },
  { id: 'users:read:managed', description: { en: 'Read users you manage', ru: 'Чтение управляемых пользователей' }, resource: 'https://api.example.com' },
  { id: 'users:write', description: { en: 'Create and update users', ru: 'Создание и изменение пользователей' }, resource: 'https://api.example.com' },
  { id: 'users:delete', description: { en: 'Delete users', ru: 'Удаление пользователей' }, resource: 'https://api.example.com' },
  { id: 'roles:read', description: { en: 'Read roles', ru: 'Чтение ролей' }, resource: 'https://api.example.com' },
  { id: 'clients:read', description: { en: 'Read OAuth clients', ru: 'Чтение OAuth клиентов' }, resource: 'https://api.example.com' },
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
    id: 'oauth-admin',
    description: { en: 'System Administrator (Super Admin)', ru: 'Системный администратор' },
    active: true,
    permissions: mockPermissions,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-03-25T00:00:00Z',
  },
];

