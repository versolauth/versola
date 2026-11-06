import type { ClientDto, MockConfigState, ResourceDto, RoleDto } from './mock-backend';

/**
 * What a visitor finds in the public demo console: one fictional retailer with enough of everything
 * that every screen has something to open, edit and delete. The dates are taken from the clock when
 * the page loads so sessions read as live.
 */
const tenant = 'acme-retail';
const staging = 'acme-staging';

const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
const daysAgo = (days: number) => minutesAgo(days * 24 * 60);
const daysAhead = (days: number) => new Date(Date.now() + days * 24 * 60 * 60_000).toISOString();

const scopes = [
  { scope: 'openid', description: { en: 'Sign in with Acme' }, claims: [{ claim: 'sub', description: { en: 'Stable user id' } }] },
  { scope: 'profile', description: { en: 'Name and picture' }, claims: [{ claim: 'name', description: { en: 'Full name' } }, { claim: 'picture', description: { en: 'Avatar URL' } }] },
  { scope: 'email', description: { en: 'Email address' }, claims: [{ claim: 'email', description: { en: 'Email address' } }, { claim: 'email_verified', description: { en: 'Whether the address was confirmed' } }] },
  { scope: 'phone', description: { en: 'Phone number' }, claims: [{ claim: 'phone_number', description: { en: 'Phone in E.164' } }] },
  { scope: 'offline_access', description: { en: 'Stay signed in on this device' }, claims: [] },
  { scope: 'orders', description: { en: 'Orders and returns' }, claims: [{ claim: 'loyalty_tier', description: { en: 'Loyalty programme tier' } }] },
];

const permissions = [
  { permission: 'orders:read', description: { en: 'Read orders' }, endpointIds: [301, 303] },
  { permission: 'orders:write', description: { en: 'Create and change orders' }, endpointIds: [302] },
  { permission: 'orders:refund', description: { en: 'Refund an order' }, endpointIds: [304] },
  { permission: 'catalog:read', description: { en: 'Browse the catalog' }, endpointIds: [311] },
  { permission: 'catalog:write', description: { en: 'Edit products and prices' }, endpointIds: [312, 313] },
  { permission: 'customers:read', description: { en: 'Read customer profiles' }, endpointIds: [321] },
];

const ordersApi: ResourceDto = {
  resourceId: 'orders-api',
  resource: 'http://orders.acme.svc.cluster.local:8080',
  audience: ['https://api.acme.example/orders'],
  endpoints: [
    { id: 301, method: 'GET', path: '/orders', fetchUserInfo: false, allow: 'true', inject: [{ target: 'header', name: 'X-Customer-Id', expression: 'token.sub' }] },
    { id: 302, method: 'POST', path: '/orders', fetchUserInfo: false, allow: "'orders:write' in token.scope", inject: [{ target: 'header', name: 'X-Customer-Id', expression: 'token.sub' }] },
    { id: 303, method: 'GET', path: '/orders/{id}', fetchUserInfo: false, allow: 'true', inject: [] },
    {
      id: 304, method: 'POST', path: '/orders/{id}/refund', fetchUserInfo: true,
      allow: "'orders:refund' in token.scope && userinfo.email_verified",
      inject: [],
      stepUpCondition: 'request.query.amount > 100', stepUpAcr: 'sensitive', maxAge: 300,
    },
  ],
  internal: false,
  secretRotation: false,
};

const catalogApi: ResourceDto = {
  resourceId: 'catalog-api',
  resource: 'http://catalog.acme.svc.cluster.local:8080',
  audience: ['https://api.acme.example/catalog'],
  endpoints: [
    { id: 311, method: 'GET', path: '/products', fetchUserInfo: false, allow: 'true', inject: [] },
    { id: 312, method: 'PUT', path: '/products/{id}', fetchUserInfo: false, allow: "'catalog:write' in token.scope", inject: [] },
    { id: 313, method: 'POST', path: '/products/{id}/price', fetchUserInfo: false, allow: "'catalog:write' in token.scope", inject: [], stepUpCondition: 'true', stepUpAcr: 'sensitive', maxAge: 600 },
  ],
  internal: false,
  secretRotation: false,
};

const customersApi: ResourceDto = {
  resourceId: 'customers-api',
  resource: 'http://customers.acme.svc.cluster.local:8080',
  audience: ['https://api.acme.example/customers'],
  endpoints: [{ id: 321, method: 'GET', path: '/customers/{id}', fetchUserInfo: true, allow: "'customers:read' in token.scope", inject: [{ target: 'query', name: 'region', expression: 'userinfo.region' }] }],
  internal: true,
  secretRotation: false,
};

const smsLogin = {
  primary: { credentials: ['phone'], inlinePassword: false, factors: [{ type: 'otp', required: true }] },
  passkey: null,
  otpType: 'sms' as const,
};

const emailPassword = {
  primary: { credentials: ['email'], inlinePassword: true, factors: [{ type: 'passkeyEnroll', required: false }] },
  passkey: { factors: [] },
  otpType: 'email' as const,
};

const clients: ClientDto[] = [
  {
    id: 'storefront-web',
    kind: 'web' as const,
    clientName: { en: 'Storefront Web', ru: 'Магазин' },
    redirectUris: ['https://shop.acme.example/callback'],
    scope: ['openid', 'profile', 'email', 'phone', 'orders'],
    permissions: ['orders:read', 'orders:write', 'catalog:read'],
    secretRotation: false,
    authMethod: 'client_secret' as const,
    refreshTokenTtl: 30 * 24 * 60 * 60,
    authFlow: smsLogin,
    frontChannelLogoutUri: 'https://shop.acme.example/logout/frontchannel',
    frontChannelLogoutSessionRequired: true,
    backChannelLogoutUri: null,
    createdAt: daysAgo(41),
  },
  {
    id: 'acme-mobile',
    kind: 'device' as const,
    clientName: { en: 'Acme Mobile App', ru: 'Мобильное приложение' },
    redirectUris: ['kz.acme.app:/callback'],
    scope: ['openid', 'profile', 'offline_access', 'orders'],
    permissions: ['orders:read', 'orders:write', 'catalog:read'],
    secretRotation: false,
    authMethod: 'none' as const,
    refreshTokenTtl: 180 * 24 * 60 * 60,
    authFlow: smsLogin,
    createdAt: daysAgo(33),
  },
  {
    id: 'backoffice',
    kind: 'web' as const,
    clientName: { en: 'Back Office', ru: 'Бэк-офис' },
    redirectUris: ['https://backoffice.acme.example/complete'],
    scope: ['openid', 'profile', 'email'],
    permissions: ['orders:read', 'orders:refund', 'catalog:read', 'catalog:write', 'customers:read'],
    secretRotation: true,
    authMethod: 'private_key_jwt' as const,
    refreshTokenTtl: 24 * 60 * 60,
    authFlow: emailPassword,
    createdAt: daysAgo(58),
  },
  {
    id: 'fulfilment-worker',
    kind: 'service' as const,
    clientName: { en: 'Fulfilment Worker' },
    redirectUris: [],
    scope: [],
    permissions: ['orders:read', 'customers:read'],
    secretRotation: false,
    authMethod: 'client_secret' as const,
    createdAt: daysAgo(12),
  },
];

const roles: RoleDto[] = [
  { id: 'customer', description: { en: 'Shopper', ru: 'Покупатель' }, permissions: ['orders:read', 'orders:write', 'catalog:read'], active: true },
  { id: 'support', description: { en: 'Customer support' }, permissions: ['orders:read', 'customers:read', 'catalog:read'], active: true },
  { id: 'catalog-manager', description: { en: 'Catalog manager' }, permissions: ['catalog:read', 'catalog:write'], active: true },
  { id: 'finance', description: { en: 'Finance: can refund' }, permissions: ['orders:read', 'orders:refund'], active: true },
  { id: 'legacy-partner', description: { en: 'Retired partner access' }, permissions: ['catalog:read'], active: false },
];

const passkey = (name: string, lastUsedMinutes: number, createdDays: number) => ({
  id: `pk-${name.toLowerCase().replace(/\W+/g, '-')}`,
  name,
  deviceType: 'multiDevice',
  transports: ['internal', 'hybrid'],
  backedUp: true,
  backupEligible: true,
  lastUsedAt: minutesAgo(lastUsedMinutes),
  createdAt: daysAgo(createdDays),
});

const session = (clientId: string, platform: 'ios' | 'android' | 'desktop', os: string, browser: string, version: string, startedMinutes: number) => ({
  publicId: `s-${clientId}-${platform}-${startedMinutes}`,
  clients: [{ clientId, enteredAt: minutesAgo(startedMinutes), expiresAt: daysAhead(7) }],
  createdAt: minutesAgo(startedMinutes),
  platform,
  os,
  browser,
  version,
  expiresAt: daysAhead(7),
  idleExpiresAt: daysAhead(1),
});

const user = (n: number, name: string, login: string, roleIds: string[], extra: Record<string, unknown> = {}) => ({
  id: `00000000-0000-0000-0000-0000000000${String(n).padStart(2, '0')}`,
  email: `${login}@acme.example`,
  phone: `+7701555${String(1000 + n * 37).slice(-4)}`,
  login,
  claims: { name, locale: 'en', loyalty_tier: n % 3 === 0 ? 'gold' : 'basic', region: n % 2 === 0 ? 'KZ' : 'UZ' },
  rolesByTenant: { [tenant]: roleIds },
  ...extra,
});

const users = [
  user(1, 'Aigerim Sadykova', 'aigerim', ['customer'], {
    passkeys: [passkey('iPhone 15', 12, 40), passkey('MacBook Touch ID', 3 * 24 * 60, 38)],
    sessions: [
      session('storefront-web', 'desktop', 'macOS 15', 'Safari', '18.1', 12),
      session('acme-mobile', 'ios', 'iOS 18', 'Acme Mobile', '3.4.0', 190),
    ],
  }),
  user(2, 'Dmitry Volkov', 'dvolkov', ['support'], { sessions: [session('backoffice', 'desktop', 'Windows 11', 'Chrome', '130', 45)] }),
  user(3, 'Elena Park', 'elena.park', ['finance'], { passkeys: [passkey('YubiKey 5C', 90, 70)], sessions: [session('backoffice', 'desktop', 'Ubuntu 24.04', 'Firefox', '132', 5)] }),
  user(4, 'Timur Nurlanov', 'timur', ['customer']),
  user(5, 'Sofia Marchetti', 'sofia.m', ['catalog-manager'], { sessions: [session('backoffice', 'desktop', 'macOS 15', 'Chrome', '130', 260)] }),
  user(6, 'Bakhyt Omarov', 'bakhyt', ['customer'], { sessions: [session('acme-mobile', 'android', 'Android 15', 'Acme Mobile', '3.4.0', 20)] }),
  user(7, 'Chloé Dubois', 'chloe', ['customer']),
  user(8, 'Rustam Karimov', 'rustam', ['support', 'catalog-manager']),
];

const passwordRegex = '^(?=.*[A-Za-z])(?=.*\\d).{10,}$';

const challenge = (tenantId: string) => ({
  tenantId,
  securityProfile: 'fapi2' as const,
  phoneRegions: [
    { region: 'KZ', dialCode: '+7', length: 10, regex: '^7\\d{9}$', registration: true },
    { region: 'UZ', dialCode: '+998', length: 9, regex: '^\\d{9}$', registration: true },
  ],
  passwordRegex,
  emailRegistration: { mode: 'allowlist' as const, domains: ['acme.example'], blockSubaddress: true },
  defaultRoles: ['customer'],
  submissionLimits: {
    otpRequest: [{ maxAttempts: 3, windowSeconds: 600 }],
    otpSubmit: [{ maxAttempts: 5, windowSeconds: 300 }],
    passwordSubmit: [{ maxAttempts: 5, windowSeconds: 300 }],
    banDurationSeconds: 900,
  },
  otpLength: 6,
  otpResendAfter: 60,
  passkeySettings: { rpId: 'acme.example', rpName: 'Acme', origins: ['https://id.acme.example'], userVerification: 'preferred' },
});

export const demoSeed: Partial<MockConfigState> = {
  tenants: [
    { id: tenant, description: 'Acme Retail: production', edgeId: 'edge-main', securityProfile: 'fapi2' },
    { id: staging, description: 'Acme Retail: staging', edgeId: null, securityProfile: 'fapi2' },
  ],
  scopes: { [tenant]: scopes, [staging]: scopes.slice(0, 3) },
  permissions: { [tenant]: permissions, [staging]: permissions.slice(0, 2) },
  resources: { [tenant]: [ordersApi, catalogApi, customersApi], [staging]: [] },
  clients: { [tenant]: clients, [staging]: [clients[0]] },
  roles: { [tenant]: roles, [staging]: roles.slice(0, 2) },
  users,
  edges: [{ id: 'edge-main', hasOldKey: false, requireDpopNonce: true, tenants: [tenant], clients: [{ tenantId: tenant, clientId: 'backoffice' }] }],
  locales: [
    { code: 'en', name: 'English', isDefault: true, active: true },
    { code: 'ru', name: 'Русский', isDefault: false, active: true },
    { code: 'kk', name: 'Қазақша', isDefault: false, active: false },
  ],
  formLocales: [{ code: 'en', name: 'English' }, { code: 'ru', name: 'Русский' }],
  challengeSettings: { [tenant]: challenge(tenant), [staging]: challenge(staging) },
  otpTemplates: {
    [tenant]: [
      { id: 'login-sms', tenantId: tenant, purpose: 'login', channel: 'sms', localizations: { en: 'Your Acme code is {{code}}', ru: 'Ваш код Acme: {{code}}' } },
      { id: 'login-email', tenantId: tenant, purpose: 'login', channel: 'email', localizations: { en: 'Your Acme sign-in code: {{code}}' } },
    ],
  },
  systemSettings: { passwordRegex, passwordHistorySize: 5, passwordNumDifferent: 3 },
  serverMetadata: { issuer: 'https://id.acme.example' },
};
