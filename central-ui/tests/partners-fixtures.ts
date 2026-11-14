import type { MockConfigState } from './mocks';

/**
 * The partner-edge data of the specs and the review screenshots (#549): the `pilot-pay` partner of
 * `tenant-alpha` with an approved client, one waiting approval, one decided, a resource and one
 * enrolled edge, plus a suspended partner and one still onboarding. Dates are fixed so a screenshot
 * taken under `page.clock.install({ time: PARTNER_NOW })` never changes.
 */
export const PARTNER_NOW = '2026-11-07T10:00:00Z';
export const partnerTenant = 'tenant-alpha';

const at = (daysFromNow: number, hour = 10) => {
  const date = new Date(PARTNER_NOW);
  date.setUTCDate(date.getUTCDate() + daysFromNow);
  date.setUTCHours(hour, 0, 0, 0);
  return date.toISOString();
};

export const partnerScopes = [
  { scope: 'openid', description: { en: 'Sign in' }, claims: [{ claim: 'sub', description: { en: 'Subject' } }] },
  { scope: 'profile', description: { en: 'Name and picture' }, claims: [{ claim: 'name', description: { en: 'Full name' } }] },
  { scope: 'email', description: { en: 'Email address' }, claims: [{ claim: 'email', description: { en: 'Email' } }] },
  { scope: 'phone', description: { en: 'Phone number' }, claims: [{ claim: 'phone_number', description: { en: 'Phone' } }] },
  { scope: 'offline_access', description: { en: 'Stay signed in' }, claims: [] },
  { scope: 'orders', description: { en: 'Orders' }, claims: [] },
];

export const pilotPay = {
  id: 'pilot-pay',
  version: 1,
  tenantId: partnerTenant,
  displayName: 'Pilot Pay',
  logoUri: 'https://pay.pilotpay.example/brand/pilot-pay.svg',
  contact: { email: 'security@pilotpay.example', phone: '+7 727 300 1100' },
  status: 'active' as const,
  limits: {
    scopes: ['openid', 'profile', 'email', 'phone'],
    redirectDomains: ['pilotpay.example', 'pilotpay.app'],
    maxClients: 10,
    maxEdges: 3,
    accessTokenTtlMax: 86_400,
    refreshTokenTtlMax: 30 * 86_400,
    clientAuthMethods: ['tls_client_auth' as const],
  },
  groupId: 'pilot-pay',
  consoleClientId: 'pilot-pay-console',
  reviewAt: at(20),
  createdAt: at(-66),
};

export const nomadTravel = {
  id: 'nomad-travel',
  version: 1,
  tenantId: partnerTenant,
  displayName: 'Nomad Travel',
  logoUri: null,
  contact: { email: 'it@nomad.example' },
  status: 'suspended' as const,
  limits: { ...pilotPay.limits, scopes: ['openid', 'profile'], redirectDomains: ['nomad.example'], maxClients: 3, maxEdges: 1, clientAuthMethods: ['tls_client_auth' as const] },
  groupId: 'nomad-travel',
  consoleClientId: 'nomad-travel-console',
  reviewAt: at(-3),
  createdAt: at(-120),
};

export const beeMarket = {
  id: 'bee-market',
  version: 1,
  tenantId: partnerTenant,
  displayName: 'Bee Market',
  logoUri: null,
  contact: { email: 'dev@beemarket.example' },
  status: 'onboarding' as const,
  limits: { ...pilotPay.limits, scopes: ['openid', 'email'], redirectDomains: ['beemarket.example'], clientAuthMethods: ['tls_client_auth' as const] },
  groupId: 'bee-market',
  consoleClientId: 'bee-market-console',
  reviewAt: null,
  createdAt: at(-1),
};

const partnerFixed = {
  authMethod: 'tls_client_auth' as const,
  mtlsAuth: { type: 'san_uri', value: 'urn:versola:edge-group:pilot-pay' },
  requirePushedAuthorizationRequests: true,
  certificateBoundAccessTokens: false,
  consentFlow: { allowPartial: false, rememberDuration: 90 * 86_400 },
  authFlow: { primary: { credentials: ['phone'], inlinePassword: false, factors: [{ type: 'otp', required: true }] }, passkey: null, otpType: 'sms' as const },
};

export const pilotWeb = {
  id: 'pilot-pay-web',
  kind: 'web' as const,
  clientName: { en: 'Pilot Pay Shop' },
  redirectUris: ['https://pay.pilotpay.example/complete', 'https://staging.pilotpay.example/complete'],
  scope: ['openid', 'profile', 'email'],
  permissions: [],
  secretRotation: false,
  accessTokenTtl: 300,
  refreshTokenTtl: 14 * 86_400,
  backChannelLogoutUri: 'https://login.pilotpay.example/logout/backchannel',
  logoUri: 'https://pay.pilotpay.example/brand/pilot-pay.svg',
  createdAt: at(-60),
  partnerId: 'pilot-pay',
  approval: 'approved' as const,
  ...partnerFixed,
};

export const pilotCheckout = {
  ...pilotWeb,
  id: 'pilot-pay-checkout',
  clientName: { en: 'Pilot Pay Checkout' },
  redirectUris: ['https://checkout.pilotpay.example/complete', 'https://checkout.pilot-partner.net/complete'],
  logoUri: null,
  createdAt: at(-2),
  approval: 'pending' as const,
};

export const pilotConsole = {
  id: 'pilot-pay-console',
  kind: 'web' as const,
  clientName: { en: 'Pilot Pay console' },
  redirectUris: ['https://login.pilotpay.example/complete'],
  scope: ['openid', 'phone', 'email'],
  permissions: [],
  secretRotation: false,
  accessTokenTtl: 600,
  refreshTokenTtl: 12 * 3600,
  createdAt: at(-66),
  partnerId: 'pilot-pay',
  approval: 'approved' as const,
  authMethod: 'tls_client_auth' as const,
  mtlsAuth: { type: 'san_uri', value: 'urn:versola:edge-group:pilot-pay' },
  requirePushedAuthorizationRequests: true,
  certificateBoundAccessTokens: false,
  authFlow: partnerFixed.authFlow,
  consentFlow: null,
};

export const alphaWeb = {
  id: 'alpha-web',
  kind: 'web' as const,
  clientName: { en: 'Alpha Web' },
  redirectUris: ['https://alpha.example/callback'],
  scope: ['openid', 'profile'],
  permissions: [],
  secretRotation: false,
  accessTokenTtl: 3600,
  authFlow: partnerFixed.authFlow,
};

export const pilotOrders = {
  resourceId: 'pilot-orders',
  resource: 'http://orders.pilotpay.internal:8080',
  audience: ['pilot-pay-web'],
  endpoints: [
    { id: 401, method: 'GET', path: '/orders', fetchUserInfo: false, allow: "'profile' in token.scope", inject: [{ target: 'header' as const, name: 'X-User', expression: 'token.sub' }] },
    { id: 402, method: 'POST', path: '/orders', fetchUserInfo: true, allow: "'profile' in token.scope && userinfo.email_verified", inject: [{ target: 'header' as const, name: 'X-User', expression: 'token.sub' }], stepUpCondition: 'request.body.amount > 50000', stepUpAcr: 'sensitive', maxAge: 300 },
    { id: 403, method: 'GET', path: '/orders/{id}', fetchUserInfo: false, allow: "'profile' in token.scope", inject: [] },
  ],
  internal: true,
  secretRotation: false,
  partnerId: 'pilot-pay',
};

export const partnerEdges = [
  { id: 'edge-alpha', hasOldKey: false, requireDpopNonce: true, groupId: 'default', groupKind: 'first_party' as const, partnerId: null, publicUrl: 'https://id.alpha.example', certificateNotAfter: at(12), lastRenewedAt: at(-2) },
  { id: 'edge-pilot-eu-1', hasOldKey: false, requireDpopNonce: true, groupId: 'pilot-pay', groupKind: 'partner' as const, partnerId: 'pilot-pay', publicUrl: 'https://login.pilotpay.example', certificateNotAfter: at(10), lastRenewedAt: at(-4, 2) },
  { id: 'edge-pilot-kz-1', hasOldKey: false, requireDpopNonce: true, groupId: 'pilot-pay', groupKind: 'partner' as const, partnerId: 'pilot-pay', publicUrl: 'https://login-kz.pilotpay.example' },
  { id: 'edge-nomad-1', hasOldKey: false, requireDpopNonce: true, groupId: 'nomad-travel', groupKind: 'partner' as const, partnerId: 'nomad-travel', publicUrl: 'https://login.nomad.example', certificateNotAfter: at(3), lastRenewedAt: at(-11) },
];

export const partnerApprovals = [
  {
    id: 'apr-1001', partnerId: 'pilot-pay', kind: 'client_create' as const, targetId: 'pilot-pay-checkout',
    requested: {
      kind: 'web', clientName: 'Pilot Pay Checkout', logoUri: 'https://pay.pilotpay.example/brand/pilot-pay.svg',
      redirectUris: ['https://checkout.pilotpay.example/complete', 'https://checkout.pilot-partner.net/complete'],
      scope: ['openid', 'profile', 'email'], accessTokenTtl: 300, refreshTokenTtl: 14 * 86_400,
      backChannelLogoutUri: 'https://login.pilotpay.example/logout/backchannel',
    },
    outsideLimits: ['clientName', 'logoUri', 'redirectUris'],
    requestedBy: 'u_7f3a9c2e', requestedAt: at(-2, 14), status: 'waiting' as const,
  },
  {
    id: 'apr-1002', partnerId: 'pilot-pay', kind: 'client_change' as const, targetId: 'pilot-pay-web',
    requested: { scope: ['openid', 'profile', 'email', 'offline_access'] },
    current: { scope: ['openid', 'profile', 'email'] },
    outsideLimits: ['scope'],
    requestedBy: 'u_2b91d4e0', requestedAt: at(-3, 9), status: 'waiting' as const,
  },
  {
    id: 'apr-0998', partnerId: 'pilot-pay', kind: 'client_create' as const, targetId: 'pilot-pay-web',
    requested: { kind: 'web', clientName: 'Pilot Pay Shop', redirectUris: ['https://pay.pilotpay.example/complete'], scope: ['openid', 'profile', 'email'] },
    outsideLimits: ['clientName'],
    requestedBy: 'u_7f3a9c2e', requestedAt: at(-61), status: 'approved' as const, decidedBy: 'b.argin', decidedAt: at(-60), note: 'Name matches the partner.',
  },
];

/** The whole state of the partner-edge screens, for `loadAdminApp(page, { state })`. */
export const partnerState: Partial<MockConfigState> = {
  tenants: [{ id: partnerTenant, description: 'Alpha Workspace', edgeId: 'edge-alpha' }, { id: 'tenant-bravo', description: 'Bravo Workspace', edgeId: null }],
  scopes: { [partnerTenant]: partnerScopes },
  clients: { [partnerTenant]: [alphaWeb, pilotWeb, pilotCheckout, pilotConsole] },
  resources: { [partnerTenant]: [pilotOrders] },
  edges: partnerEdges,
  partners: [pilotPay, nomadTravel, beeMarket],
  partnerApprovals,
  serverMetadata: { issuer: 'https://id.alpha.example' },
};

export const partnersPath = `/?view=partners&tenant=${partnerTenant}`;
