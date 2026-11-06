import { defaultChallengeSettings, type MockConfigState } from './mocks';

/** The shop's resources, with everything the record shows: an internal one with a rotation open,
 *  endpoints that carry every kind of rule, a public one, a long ID and host, and one with no
 *  endpoints and no clients. Shared by the Resources spec, the axe scan and the docs screenshots
 *  (example.com data). */
export const shopTenant = 'tenant-alpha';

const client = (id: string, name: string) => ({
  id,
  clientName: { en: name },
  redirectUris: [`https://${id}.example.com/callback`],
  scope: ['openid'],
  permissions: [],
  secretRotation: false,
});

export const shopClients = [
  client('storefront-web', 'Storefront Web'),
  client('shop-mobile', 'Shop mobile'),
  client('admin-console', 'Admin console'),
  client('partner-portal', 'Partner portal'),
];

export const ordersApi = {
  resourceId: 'orders-api',
  resource: 'https://orders.internal.example.com',
  audience: ['storefront-web', 'shop-mobile', 'admin-console', 'partner-portal'],
  internal: true,
  secretRotation: true,
  endpoints: [
    { id: 1, method: 'GET', path: '/orders', fetchUserInfo: false, inject: [{ target: 'header', name: 'X-User-Id', expression: 'token.sub' }] },
    {
      id: 2, method: 'GET', path: '/orders/{orderId}', fetchUserInfo: true,
      allow: 'token.sub == user.id || "orders:read-all" in token.scope',
      inject: [{ target: 'header', name: 'X-User-Id', expression: 'token.sub' }],
    },
    {
      id: 3, method: 'POST', path: '/orders', fetchUserInfo: false,
      inject: [{ target: 'header', name: 'X-Tenant', expression: '"example-shop"' }, { target: 'body', name: 'customerId', expression: 'token.sub' }],
    },
    { id: 4, method: 'PUT', path: '/orders/{orderId}/address', fetchUserInfo: false, allow: 'has(request.body.postalCode)', inject: [] },
    {
      id: 5, method: 'POST', path: '/orders/{orderId}/refund', fetchUserInfo: true,
      allow: 'request.body.amount <= 50000 && "shop-manager" in user.roles',
      stepUpCondition: 'request.body.amount > 10000', stepUpAcr: 'mfa', maxAge: 300,
      inject: [{ target: 'header', name: 'X-User-Id', expression: 'token.sub' }, { target: 'body', name: 'refundedBy', expression: 'token.sub' }],
    },
    { id: 6, method: 'DELETE', path: '/orders/{orderId}', fetchUserInfo: false, allow: '"shop-manager" in user.roles', maxAge: 600, inject: [] },
  ],
};

export const catalogApi = {
  resourceId: 'catalog-api',
  resource: 'https://catalog.example.com',
  audience: ['storefront-web', 'shop-mobile'],
  endpoints: [
    { id: 11, method: 'GET', path: '/products', fetchUserInfo: false, inject: [] },
    { id: 12, method: 'GET', path: '/products/{sku}', fetchUserInfo: false, inject: [] },
    { id: 13, method: 'PUT', path: '/products/{sku}', fetchUserInfo: false, inject: [] },
  ],
};

export const paymentsGateway = {
  resourceId: 'payments-gateway-eu-central-1',
  resource: 'https://payments-gateway.eu-central-1.prod.internal.example-payments-platform.com',
  audience: ['admin-console'],
  internal: true,
  endpoints: [
    { id: 21, method: 'POST', path: '/v2/payment-intents', fetchUserInfo: false, inject: [] },
    { id: 22, method: 'POST', path: '/v2/payment-intents/{id}/capture', fetchUserInfo: false, inject: [] },
    { id: 23, method: 'GET', path: '/v2/payment-intents/{id}', fetchUserInfo: false, inject: [] },
  ],
};

export const supportDesk = {
  resourceId: 'support-desk',
  resource: 'https://support.example.com',
  audience: ['admin-console', 'partner-portal', 'shop-mobile'],
  endpoints: [
    { id: 31, method: 'GET', path: '/tickets', fetchUserInfo: false, inject: [] },
    { id: 32, method: 'POST', path: '/tickets', fetchUserInfo: false, inject: [] },
  ],
};

export const loyaltyApi = { resourceId: 'loyalty-api', resource: 'https://loyalty.example.com', audience: [], endpoints: [] };

export const shopResources = [ordersApi, catalogApi, paymentsGateway, supportDesk, loyaltyApi];

export const shopPermissions = [
  { permission: 'orders:read', description: { en: 'Read orders' }, endpointIds: [1, 2] },
  { permission: 'orders:write', description: { en: 'Create and change orders' }, endpointIds: [3, 4] },
  { permission: 'orders:refund', description: { en: 'Refund orders' }, endpointIds: [5, 6] },
  { permission: 'orders:admin', description: { en: 'Administer orders' }, endpointIds: [6] },
];

export const shopChallengeSettings = {
  ...defaultChallengeSettings(shopTenant),
  acrVocabulary: { mfa: ['password', 'otp'], pwd: ['password'], hwk: ['passkey'] },
};

/** The tenant's state for every Resources test that needs the shop. */
export const shopState: Partial<MockConfigState> = {
  resources: { [shopTenant]: shopResources },
  clients: { [shopTenant]: shopClients },
  permissions: { [shopTenant]: shopPermissions },
  challengeSettings: { [shopTenant]: shopChallengeSettings },
};
