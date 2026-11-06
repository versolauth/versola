/** One user with everything the record page shows: two signed-in devices (one with an idle timeout), a
 *  signed-out device that still holds offline access, a signed-out device that is only remembered, three apps with offline access (one DPoP-bound), passkeys, claims and
 *  roles in tenant-alpha. Shared by the Users spec and the docs screenshots (example.com data). */
export const danaId = '0193c2e4-7b1a-7c3e-9f00-5a1d2b3c4d5e';

const inDays = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString();
const daysAgo = (days: number, hour = 9, minute = 12) => {
  const date = new Date(Date.now() - days * 86_400_000);
  date.setHours(hour, minute, 0, 0);
  return date.toISOString();
};

export const chromeSessionId = 'sess-chrome-macos';
export const phoneSessionId = 'sess-shop-ios';
export const goneSessionId = 'sess-firefox-gone';
export const ipadSessionId = 'sess-ipad-ended';

export const chromeAgentId = '0193c2e4-aaaa-7000-8000-000000000001';
export const phoneAgentId = '0193c2e4-aaaa-7000-8000-000000000002';
export const firefoxAgentId = '0193c2e4-aaaa-7000-8000-000000000003';
export const ipadAgentId = '0193c2e4-aaaa-7000-8000-000000000004';

const chromeAgent = {
  id: chromeAgentId,
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
  platform: 'desktop' as const,
  os: 'macOS 14.6',
  browser: 'Chrome',
  version: '130',
  createdAt: daysAgo(40),
  expiresAt: inDays(80),
};
const phoneAgent = {
  id: phoneAgentId,
  userAgent: 'ExampleShop/4.2 (iPhone; iOS 18.1)',
  platform: 'ios' as const,
  os: 'iOS 18.1',
  browser: 'Example Shop app',
  createdAt: daysAgo(90),
  expiresAt: inDays(60),
};
/** Signed out, but its session left offline access behind (`goneSessionId`). */
const firefoxAgent = {
  id: firefoxAgentId,
  userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0',
  platform: 'desktop' as const,
  os: 'Windows 10 / 11',
  browser: 'Firefox',
  version: '131',
  createdAt: daysAgo(60),
  expiresAt: inDays(30),
  endedSessionIds: [goneSessionId],
};
/** Signed out and holding nothing: remembered only. */
const ipadAgent = {
  id: ipadAgentId,
  userAgent: 'Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
  platform: 'ios' as const,
  os: 'iOS 17.5',
  browser: 'Safari',
  version: '17',
  createdAt: daysAgo(120),
  expiresAt: inDays(5),
  endedSessionIds: [ipadSessionId],
};

export const dana = {
  id: danaId,
  email: 'dana@example.com',
  phone: '+14155551234',
  login: 'dana',
  claims: { name: 'Dana Reyes', locale: 'ru', email_verified: true },
  rolesByTenant: { 'tenant-alpha': ['shop-manager', 'user'] } as Record<string, string[]>,
  sessions: [
    {
      publicId: chromeSessionId,
      platform: 'desktop' as const,
      os: 'macOS 14.6',
      browser: 'Chrome',
      version: '130',
      createdAt: daysAgo(1),
      expiresAt: inDays(6),
      idleExpiresAt: inDays(0.04),
      userAgent: chromeAgent,
      clients: [
        { clientId: 'storefront-web', enteredAt: daysAgo(1, 9, 12), expiresAt: daysAgo(1, 10, 12) },
        { clientId: 'admin-console', enteredAt: daysAgo(1, 9, 40), expiresAt: daysAgo(1, 10, 40) },
      ],
    },
    {
      publicId: phoneSessionId,
      platform: 'ios' as const,
      os: 'iOS 18.1',
      browser: 'Example Shop app',
      createdAt: daysAgo(12, 18, 3),
      expiresAt: inDays(18),
      userAgent: phoneAgent,
      clients: [{ clientId: 'shop-mobile', enteredAt: daysAgo(12, 18, 3), expiresAt: daysAgo(12, 19, 3) }],
    },
  ],
  userAgents: [chromeAgent, phoneAgent, firefoxAgent, ipadAgent],
  refreshTokens: [
    { sessionId: chromeSessionId, clientId: 'storefront-web', scope: ['openid', 'profile', 'offline_access'], issuedAt: daysAgo(1), expiresAt: inDays(29), dpopBound: false },
    { sessionId: phoneSessionId, clientId: 'shop-mobile', scope: ['openid', 'orders', 'offline_access'], issuedAt: daysAgo(12, 18, 3), expiresAt: inDays(70), dpopBound: true },
    { sessionId: goneSessionId, clientId: 'storefront-web', scope: ['openid', 'offline_access'], issuedAt: daysAgo(25, 11, 30), expiresAt: inDays(2), dpopBound: false },
  ],
  passkeys: [
    { id: 'cred-icloud', name: 'iCloud Keychain', deviceType: 'multiDevice', transports: ['internal', 'hybrid'], backedUp: true, backupEligible: true, lastUsedAt: daysAgo(1), createdAt: daysAgo(200) },
    { id: 'cred-yubikey', name: null, deviceType: 'singleDevice', transports: ['usb'], backedUp: false, backupEligible: false, lastUsedAt: null, createdAt: daysAgo(200) },
  ],
};

/** A user nobody is signed in as: nothing to sign out, no offline access, no passkeys. */
export const quiet = {
  id: '0193c2e4-7b1a-7c3e-9f00-000000000002',
  email: 'sam@example.com',
  login: 'sam',
  claims: {},
  rolesByTenant: {},
  sessions: [],
  userAgents: [],
  refreshTokens: [],
  passkeys: [],
};

const client = (id: string, name: string) => ({
  id,
  clientName: { en: name },
  redirectUris: [`https://${id}.example.com/callback`],
  scope: ['openid'],
  permissions: [],
  secretRotation: false,
  accessTokenTtl: 3600,
});

export const userClients = [
  client('storefront-web', 'Storefront Web'),
  client('admin-console', 'Admin console'),
  client('shop-mobile', 'Shop mobile'),
];

export const shopManager = { id: 'shop-manager', description: { en: 'Manages the shop' }, permissions: [] as string[], active: true };
export const shopUser = { id: 'user', description: { en: 'A shopper' }, permissions: [] as string[], active: true };
export const shopSupport = { id: 'support', description: { en: 'Answers customers' }, permissions: [] as string[], active: true };
export const userRoles = [shopManager, shopUser, shopSupport];

export const userScopes = [
  { scope: 'profile', description: { en: 'Profile' }, claims: [{ claim: 'name', description: { en: 'Full name' } }, { claim: 'locale', description: { en: 'Locale' } }] },
  { scope: 'email', description: { en: 'Email' }, claims: [{ claim: 'email_verified', description: { en: 'Email verified' } }] },
];
