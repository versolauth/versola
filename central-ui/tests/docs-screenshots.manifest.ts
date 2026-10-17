/**
 * The contract between the console and the public documentation: one entry per image a docs page
 * references (versola-website, `public/img/docs/<name>.png`), with the state and the actions that
 * reach the screen. `docs-screenshots.spec.ts` renders every entry in both themes;
 * `scripts/sync-docs-screenshots.mjs` checks the names against the pages and copies the output.
 *
 * Names are the website's paths without extension (`console/...`, `entities/...`, `login/...`); the
 * light twin `<name>-light.png` is implied. A screen that moves or is renamed by the redesign keeps
 * its entry until the docs PR renames the page's `Screenshot` and this name together.
 *
 * Kinds: `frame` is the 1440×900 viewport (`fullPage` grows it to the document while the screen is
 * still taller than one frame); `crop` is one card located by `section`, with 16px of padding;
 * `login` is a login form rendered through the form harness.
 *
 * Node imports this file directly (type stripping), so it carries no runtime import beyond
 * `@playwright/test` and node's own modules.
 */
import { expect, type Locator, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import type { MockConfigState } from './mocks';

export type DocsTheme = 'dark' | 'light';
type MockState = Partial<MockConfigState>;
type Act = (page: Page) => Promise<void>;

type ConsoleShot = {
  name: string;
  path: string;
  state: MockState | (() => Promise<MockState>);
  /** Reaches the state after the app loaded; ends with an expect that proves it. */
  before?: Act;
};
type FrameShot = ConsoleShot & { kind: 'frame'; fullPage?: boolean };
type CropShot = ConsoleShot & { kind: 'crop'; section: (page: Page) => Locator };
type LoginShot = {
  name: string;
  kind: 'login';
  form: string;
  step: Record<string, unknown>;
  extraCss?: string;
  logo?: string;
  width?: number;
  act?: Act;
};
export type DocsShot = FrameShot | CropShot | LoginShot;

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const tenant = 'tenant-alpha';
const standardTenants = [
  { id: tenant, description: 'Alpha Workspace', edgeId: null, securityProfile: 'standard' as const },
  { id: 'tenant-bravo', description: 'Bravo Workspace', edgeId: null },
];

// ---------------------------------------------------------------------------------------------
// Tenant data (example.com)

const scopes = [
  { scope: 'openid', description: { en: 'OpenID scope' }, claims: [{ claim: 'sub', description: { en: 'Subject' } }] },
  { scope: 'profile', description: { en: 'Profile data' }, claims: [{ claim: 'name', description: { en: 'Full name' } }] },
  { scope: 'email', description: { en: 'Email address' }, claims: [{ claim: 'email', description: { en: 'Email' } }, { claim: 'email_verified', description: { en: 'Email verified' } }] },
  { scope: 'phone', description: { en: 'Phone number' }, claims: [{ claim: 'phone_number', description: { en: 'Phone' } }] },
  { scope: 'payments_read', description: { en: 'Read payment limits' }, claims: [{ claim: 'daily_transfer_limit', description: { en: 'Daily transfer limit' } }, { claim: 'account_tier', description: { en: 'Account tier' } }] },
  { scope: 'offline_access', description: { en: 'Long-lived access' }, claims: [] },
];

const permissions = [
  { permission: 'orders.read', description: { en: 'Read orders' }, endpointIds: [301] },
  { permission: 'orders.write', description: { en: 'Create and update orders' }, endpointIds: [302] },
];

const ordersApi = {
  resourceId: 'orders-api',
  resource: 'http://orders-api.orders.svc.cluster.local:8080',
  audience: ['storefront-web'],
  endpoints: [
    { id: 301, method: 'GET', path: '/orders', fetchUserInfo: false, allow: 'true', inject: [] },
    { id: 302, method: 'POST', path: '/orders', fetchUserInfo: false, allow: "'orders.write' in token.scope", inject: [] },
  ],
  internal: true,
  secretRotation: false,
};

const signIn = {
  primary: { credentials: ['phone'], inlinePassword: false, factors: [{ type: 'otp', required: true }, { type: 'passkeyEnroll', required: false }] },
  passkey: { factors: [] },
  otpType: 'sms' as const,
};

// The web app the wizard registers: fronted by edge, bound to its certificate, PAR on.
const webClient = {
  id: 'storefront-web',
  kind: 'web' as const,
  clientName: { en: 'Storefront Web' },
  redirectUris: ['https://storefront.example.com/callback'],
  scope: ['openid', 'profile'],
  permissions: [],
  secretRotation: false,
  accessTokenTtl: 3600,
  authMethod: 'tls_client_auth' as const,
  mtlsAuth: null,
  certificateBoundAccessTokens: true,
  dpopBoundAccessTokens: false,
  requirePushedAuthorizationRequests: true,
  requireSignedRequestObject: false,
  authFlow: signIn,
  consentFlow: null,
  createdAt: '2026-09-01T09:00:00Z',
};

// A web app with its own certificate: the token-binding card is editable (PAR and JAR).
const certificateWebClient = {
  ...webClient,
  mtlsAuth: { type: 'tls_client_auth', subjectType: 'subject_dn', subjectValue: 'CN=storefront-web,O=Example' },
  certificateBoundAccessTokens: false,
};

const mobileClient = {
  ...webClient,
  id: 'storefront-mobile',
  kind: 'device' as const,
  clientName: { en: 'Storefront Mobile' },
  redirectUris: ['https://app.example.com/callback'],
  scope: ['openid', 'profile', 'offline_access'],
  refreshTokenTtl: 180 * 86400,
  certificateBoundAccessTokens: false,
  dpopBoundAccessTokens: true,
  mtlsAuth: { type: 'tls_client_auth', subjectType: 'subject_dn', subjectValue: 'CN=storefront-mobile,OU=tenant-alpha,O=Versola' },
};

const partnerClient = {
  ...webClient,
  id: 'acme-wallet',
  clientName: { en: 'Acme Wallet' },
  redirectUris: ['https://wallet.example.com/callback'],
  scope: ['openid', 'profile', 'phone'],
  consentFlow: { allowPartial: true, rememberDuration: 15552000, requiredScopes: ['phone'], offlineAccessOptional: true },
};

const serviceClient = {
  id: 'billing-job',
  kind: 'service' as const,
  clientName: { en: 'Billing Job' },
  redirectUris: [],
  scope: [],
  permissions: ['orders.read'],
  secretRotation: false,
  accessTokenTtl: 3600,
  authMethod: 'client_secret' as const,
  authFlow: null,
  consentFlow: null,
  dpopBoundAccessTokens: false,
  certificateBoundAccessTokens: false,
  createdAt: '2026-09-03T14:30:00Z',
};

const alphaAdmin = { id: 'alpha_admin', description: { en: 'Alpha admin' }, permissions: ['orders.read', 'orders.write'], active: true };
const userRole = { id: 'user', description: { en: 'Self-registered user' }, permissions: [], active: true };

const dana = {
  id: '00000000-0000-0000-0000-000000000042',
  email: 'dana@example.com',
  phone: '+14155559876',
  login: 'dana',
  claims: { name: 'Dana Reyes', email: 'dana@example.com', email_verified: true, phone_number: '+14155559876', locale: 'en', daily_transfer_limit: 50000, account_tier: 'premium' },
  rolesByTenant: { [tenant]: ['alpha_admin', 'user'] },
  passkeys: [{
    id: 'Zx3VQ6mLk8pR2sT9uWxYz',
    name: 'MacBook',
    deviceType: 'multiDevice',
    transports: ['hybrid', 'internal'],
    backedUp: true,
    backupEligible: true,
    lastUsedAt: '2026-09-14T15:24:30',
    createdAt: '2026-09-12T19:00:35',
  }],
  sessions: [{
    platform: 'desktop' as const,
    os: 'macOS 15',
    browser: 'Chrome',
    version: '145',
    createdAt: '2026-09-20T20:36:00',
    clients: [{ clientId: 'storefront-web', enteredAt: '2026-09-20T20:36:00', expiresAt: '2026-09-20T21:36:00' }],
  }],
};

const challengeSettings = {
  tenantId: tenant,
  phoneRegions: [{ region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}', registration: true }],
  emailRegistration: { mode: 'allowlist' as const, domains: ['example.com', '*.corp.example'], blockSubaddress: false },
  defaultRoles: ['user'],
  submissionLimits: { otpRequest: [{ maxAttempts: 5, windowSeconds: 3600 }], otpSubmit: [{ maxAttempts: 5, windowSeconds: 900 }], passwordSubmit: [], passkeyAssertion: [], banDurationSeconds: 3600 },
  otpLength: 6,
  otpResendAfter: 60,
  authConversationTtlSeconds: 900,
  sessionTtlSeconds: 86400,
  userAgentTtlSeconds: 15552000,
  ipHeader: 'X-Real-IP',
  passkeySettings: { rpId: 'example.com', rpName: 'Example', origins: ['https://example.com'], userVerification: 'preferred' },
  requireDpopNonce: true,
  mtlsCertificateHeader: 'ssl-client-cert',
  mtlsCertificateEncoding: 'urlEncodedPem',
  signingKeyId: 'ps-kid',
  clientAssertionMaxLifetimeSeconds: 300,
  acrVocabulary: { otp: ['otp'], mfa: ['password', 'otp'] },
};

const locales = [
  { code: 'en', name: 'English', isDefault: true, active: true },
  { code: 'ru', name: 'Russian', isDefault: false, active: true },
];

const otpTemplates = [
  { id: 'default', tenantId: tenant, purpose: 'otp', channel: 'sms' as const, localizations: { en: 'Your Example code is {{code}}', ru: 'Ваш код Example: {{code}}' } },
  { id: 'default', tenantId: tenant, purpose: 'otp', channel: 'email' as const, localizations: { en: '<html><body><p>Your Example code is <b>{{code}}</b></p></body></html>', ru: '<html><body><p>Ваш код Example: <b>{{code}}</b></p></body></html>' } },
];

const authorizationDetailTypes = [
  { type: 'payment_initiation', description: { en: 'Payment initiation' }, schema: { type: 'object', required: ['type'] } },
];

// The base of every tenant-scoped screen: the alpha workspace with one of everything.
const alpha: MockState = {
  scopes: { [tenant]: scopes },
  permissions: { [tenant]: permissions },
  resources: { [tenant]: [ordersApi] },
  clients: { [tenant]: [webClient] },
  roles: { [tenant]: [alphaAdmin, userRole] },
  users: [dana],
  locales,
  otpTemplates: { [tenant]: otpTemplates },
  authorizationDetailTypes: { [tenant]: authorizationDetailTypes },
  challengeSettings: { [tenant]: challengeSettings },
};

const withClients = (...clients: object[]): MockState => ({ ...alpha, clients: { [tenant]: clients as MockConfigState['clients'][string] } });

// ---------------------------------------------------------------------------------------------
// Themes and forms (the branding screens and the login forms they style)

const presets = [
  { id: 'versola', title: { en: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } },
  { id: 'studio', title: { en: 'Studio' }, tokens: { brand: '#ff6b4a', shape: 'round', surface: 'bold', mode: 'auto', background: 'glow' } },
  { id: 'fintech', title: { en: 'Fintech' }, tokens: { brand: '#5b5bd6', shape: 'soft', surface: 'neutral', mode: 'auto', background: 'linear' } },
  { id: 'wellness', title: { en: 'Wellness' }, tokens: { brand: '#0e9f8e', shape: 'round', surface: 'soft', mode: 'auto', background: 'horizon' } },
  { id: 'editorial', title: { en: 'Editorial' }, tokens: { brand: '#14130f', shape: 'sharp', surface: 'neutral', mode: 'auto', background: 'flat' } },
  { id: 'sunny', title: { en: 'Sunny' }, tokens: { brand: '#f5c518', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } },
  { id: 'berry', title: { en: 'Berry' }, tokens: { brand: '#b4348a', shape: 'round', surface: 'bold', mode: 'auto', background: 'glow' } },
];

type Tokens = { brand: string; shape: string; surface: string; mode: string; background: string };

// ThemeCss.generate, as central writes it.
function generate(t: Tokens): string {
  const channel = (value: number) => { const u = value / 255; return u <= 0.03928 ? u / 12.92 : ((u + 0.055) / 1.055) ** 2.4; };
  const lum = (hex: string) => 0.2126 * channel(parseInt(hex.slice(1, 3), 16)) + 0.7152 * channel(parseInt(hex.slice(3, 5), 16)) + 0.0722 * channel(parseInt(hex.slice(5, 7), 16));
  const contrast = (a: number, b: number) => (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  const own = lum(t.brand);
  const onBrand = contrast(own, 1) >= contrast(own, lum('#1c1b19')) ? '#ffffff' : '#1c1b19';
  const [field, button, card] = ({ round: ['18px', '999px', '32px'], soft: ['14px', '14px', '24px'], sharp: ['4px', '4px', '6px'] } as Record<string, string[]>)[t.shape];
  const surface = t.surface === 'soft'
    ? '--page-ink:light-dark(#1c1b19,#f3f2f0);--page-light:color-mix(in oklab,var(--brand) 14%,#f7f4ee);--page-dark:color-mix(in oklab,var(--brand) 7%,#0e0e0d);--glow-dark:color-mix(in oklab,var(--brand) 16%,transparent);'
    : t.surface === 'neutral' ? '--page-ink:light-dark(#1c1b19,#f3f2f0);--page-light:#ecebe7;--page-dark:#0b0b0a;--glow-dark:transparent;' : '';
  const scheme = t.mode === 'auto' ? '' : `color-scheme:${t.mode};`;
  // The tint strengths (light, dark) of ThemeCss.tint, per surface.
  const tint = ({
    linear: { bold: [40, 45], soft: [34, 34], neutral: [14, 16] },
    horizon: { bold: [42, 46], soft: [36, 34], neutral: [18, 14] },
  } as Record<string, Record<string, number[]>>)[t.background]?.[t.surface] ?? [0, 0];
  const mixes = (light: string, dark: string) => `light-dark(color-mix(in oklab,var(--brand) ${tint[0]}%,${light}),color-mix(in oklab,var(--brand) ${tint[1]}%,${dark}))`;
  const plain = t.background === 'flat' ? '--page-bg:var(--page);' : '';
  const supported = t.background === 'linear'
    ? `--page-tint:${mixes('var(--page-light)', 'var(--page-dark)')};--page-bg:linear-gradient(160deg,var(--page-tint) 0%,var(--page) 65%);`
    : t.background === 'horizon'
      ? `--page-tint:${mixes('transparent', 'transparent')};--page-bg:radial-gradient(130% 60% at 50% 110%,var(--page-tint),transparent 72%),var(--page);`
      : '';
  return `:root{--brand:${t.brand};--on-brand:${onBrand};--rf:${field};--rb:${button};--rs:${card};${surface}${plain}${scheme}}` + (supported ? `\n@supports (color: light-dark(#000, #fff)){:root{${supported}}}` : '');
}

const resolve = (tokens: { base: string; overrides?: Partial<Tokens> }): Tokens =>
  ({ ...presets.find(p => p.id === tokens.base)!.tokens, ...(tokens.overrides ?? {}) });

const themes = [
  { id: 'default', css: '', tenantId: null, tokens: { base: 'versola' } },
  { id: 'legacy-acme', css: 'body { font-family: Georgia, serif; }', tenantId: null },
  { id: 'acme', css: '', tenantId: null, tokens: { base: 'fintech', overrides: { brand: '#2563eb' } } },
  { id: 'storefront', css: '', tenantId: tenant, tokens: { base: 'studio', overrides: { brand: '#b4348a', shape: 'sharp' } } },
];

const golden = JSON.parse(read('../../central/src/test/resources/form-previews.golden.json')) as {
  previews: Array<{ formId: string; scenarios: Array<{ id: string; title: Record<string, string>; step: Record<string, unknown>; error?: string }> }>;
};
const scenario = (formId: string, id: string) => golden.previews.find(p => p.formId === formId)!.scenarios.find(s => s.id === id)!;

// The built-in forms, compiled from source the way build-forms.mjs does; the Forms screen lists them.
const compiled = new Map<string, string>();
async function activeForms() {
  const { build } = await import('esbuild');
  const { solidPlugin } = await import('esbuild-plugin-solid');
  const out = [];
  for (const id of golden.previews.map(p => p.formId)) {
    if (!compiled.has(id)) {
      const result = await build({ entryPoints: [new URL(`../forms/${id}/${id}.tsx`, import.meta.url).pathname], bundle: true, write: false, format: 'iife', minify: true, target: 'es2019', plugins: [solidPlugin()] });
      compiled.set(id, result.outputFiles[0].text);
    }
    out.push({ id, version: 1, active: true, style: read(`../forms/${id}/${id}.css`), jsSource: null, jsCompiled: compiled.get(id)!, localizations: JSON.parse(read(`../forms/${id}/${id}.i18n.json`)), properties: [] });
  }
  return out;
}

const brandingState = async (): Promise<MockState> => ({
  ...alpha,
  forms: await activeForms(),
  formLocales: [{ code: 'en', name: 'English' }, { code: 'ru', name: 'Russian' }],
  formPreviews: golden.previews,
  themePresets: presets,
  themes,
});

// The preview's stylesheet is what central would build for the tokens (the mock returns a stub).
const realThemeCss: Act = async page => {
  await page.route('**/configuration/themes/css', async route => {
    const { tokens, css } = route.request().postDataJSON() as { tokens: { base: string; overrides?: Partial<Tokens> }; css?: string };
    const generated = generate(resolve(tokens));
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ css: [read('../forms/common.css'), generated, css ?? ''].filter(Boolean).join('\n'), generated }) });
  });
};

// ---------------------------------------------------------------------------------------------
// Global data

const edgeTenants = [
  { id: 'acme', description: 'Acme', edgeId: 'edge-default' },
  { id: 'acme-fapi', description: 'Acme FAPI', edgeId: 'edge-default', securityProfile: 'fapi2' as const },
  { id: 'default', description: 'Default', edgeId: 'edge-default' },
];
const edges = [{ id: 'edge-default', hasOldKey: false, requireDpopNonce: true, tenants: ['acme', 'acme-fapi', 'default'], clients: [] }];
const edgesState: MockState = { tenants: edgeTenants, edges };

const systemSettingsState: MockState = {
  systemSettings: { passwordRegex: '^(?=.*[A-Za-z])(?=.*\\d).{8,}$', passwordHistorySize: 2, passwordNumDifferent: 1, identityProviderLogo: null },
};

const jwksState: MockState = {
  jwksKeys: [
    { kid: 'bootstrap-ps256', kty: 'RSA', alg: 'PS256', use: 'sig', n: 'xGOr-H7A-PWG1KfTqj1aV7fYm0sZk3uB2cQ9dR8eS1tU6vW0xY4zA7bC2dE5fG8h', e: 'AQAB' },
    { kid: 'partner-es256', kty: 'EC', alg: 'ES256', use: 'sig', crv: 'P-256', x: 'f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU', y: 'x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0' },
    { kid: 'central-es256', kty: 'EC', alg: 'ES256', use: 'sig', crv: 'P-256', x: 'WKn-ZIGevcwGIyyrzFoZNBdaq9_TsqzGl96oc0CWuis', y: 'y77t-RvAHRKTsSGdIYUfweuOvwrvDD-Q3Hv5J0fSKbE' },
  ] as MockConfigState['jwksKeys'],
  jwksKeySummaries: [
    { kid: 'bootstrap-ps256', algorithm: 'PS256', keyType: 'RSA', curve: null, canSign: false },
    { kid: 'partner-es256', algorithm: 'ES256', keyType: 'EC', curve: 'P-256', canSign: false },
    { kid: 'central-es256', algorithm: 'ES256', keyType: 'EC', curve: 'P-256', canSign: true },
  ],
};

const serverMetadata = {
  issuer: 'https://auth.example.com',
  authorization_endpoint: 'https://auth.example.com/authorize',
  token_endpoint: 'https://auth.example.com/token',
  pushed_authorization_request_endpoint: 'https://auth.example.com/par',
  jwks_uri: 'https://auth.example.com/.well-known/jwks.json',
  userinfo_endpoint: 'https://auth.example.com/userinfo',
  introspection_endpoint: 'https://auth.example.com/introspect',
  revocation_endpoint: 'https://auth.example.com/revoke',
  end_session_endpoint: 'https://auth.example.com/logout',
  response_types_supported: ['code'],
  grant_types_supported: ['authorization_code', 'refresh_token', 'client_credentials'],
  code_challenge_methods_supported: ['S256'],
  token_endpoint_auth_methods_supported: ['private_key_jwt', 'tls_client_auth', 'self_signed_tls_client_auth', 'client_secret_basic'],
  dpop_signing_alg_values_supported: ['ES256', 'PS256'],
  require_pushed_authorization_requests: true,
  tls_client_certificate_bound_access_tokens: true,
};

// The mock serves a one-line metadata document; the docs show a realistic one.
const realServerMetadata: Act = async page => {
  await page.route('**/configuration/server-metadata', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(serverMetadata) }));
  await page.reload();
  await expect(page.locator('.metadata-json')).toContainText('pushed_authorization_request_endpoint');
};

// ---------------------------------------------------------------------------------------------
// Actions shared by several entries

const openClient = (id: string): Act => async page => {
  await page.getByRole('button', { name: `Open client ${id}`, exact: true }).click();
  await expect(page.locator('versola-clients-list .view-title')).toBeVisible();
};

const editClient = (id: string, group: string, heading: string): Act => async page => {
  await openClient(id)(page);
  await page.getByRole('button', { name: `Edit ${group}`, exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: heading, exact: true })).toBeVisible();
};

const startWizard = (kind: RegExp): Act => async page => {
  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create New Client', exact: true })).toBeVisible();
  await page.getByRole('button', { name: kind }).click();
};

const webBasics: Act = async page => {
  await startWizard(/Web app/)(page);
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
  await page.getByLabel('Client ID').fill('checkout-web');
  await page.getByLabel('Client Name').fill('Checkout Web');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://checkout.example.com/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await expect(page.getByText('https://checkout.example.com/callback')).toBeVisible();
};

const webSignIn: Act = async page => {
  await webBasics(page);
  await page.getByRole('button', { name: 'Continue to sign-in', exact: true }).click();
  await page.getByRole('button', { name: 'profile', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Phone', exact: true })).toHaveAttribute('aria-pressed', 'true');
};

const serviceBasics: Act = async page => {
  await startWizard(/Service app/)(page);
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
  await page.locator('versola-client-form #client-id').fill('billing-job');
  await page.locator('versola-client-form input#client-name').fill('Billing Job');
  await expect(page.getByRole('button', { name: 'client secret', exact: true })).toHaveAttribute('aria-pressed', 'true');
};

const servicePermissions: Act = async page => {
  await serviceBasics(page);
  await page.getByRole('button', { name: 'Continue to permissions', exact: true }).click();
  await page.getByRole('button', { name: 'orders.read', exact: true }).click();
  await expect(page.getByRole('button', { name: 'orders.read', exact: true })).toHaveAttribute('aria-pressed', 'true');
};

const serviceReview: Act = async page => {
  await servicePermissions(page);
  await page.getByRole('button', { name: 'Continue to review', exact: true }).click();
  await expect(page.getByText('Uses client_secret, which FAPI 2.0 does not admit')).toBeVisible();
};

const searchUser = (login: string, name: string): Act => async page => {
  await page.getByPlaceholder('Search users…').fill(login);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.locator('.user-card').filter({ hasText: name }).first()).toBeVisible();
};

const userCard = (page: Page) => page.locator('.user-card').filter({ hasText: 'Dana Reyes' }).first();

const expandResource: Act = async page => {
  await page.locator('.resource-shell').filter({ hasText: 'orders-api.orders.svc.cluster.local' }).first().locator('.resource-header').click();
  await expect(page.locator('.endpoint-card')).toHaveCount(2);
};

// ---------------------------------------------------------------------------------------------
// The manifest

const clientsPath = `/?view=clients&tenant=${tenant}`;
const allClients = withClients(webClient, mobileClient, partnerClient, serviceClient);

const clientsList: FrameShot = {
  name: 'console/clients-list', kind: 'frame', path: clientsPath, state: allClients,
  before: async page => { await expect(page.locator('versola-clients-list tbody tr')).toHaveCount(4); },
};

const clientPageWeb: FrameShot = {
  name: 'console/client-page-web', kind: 'frame', path: clientsPath, state: withClients(webClient),
  before: async page => {
    await openClient('storefront-web')(page);
    await expect(page.getByText('Meets FAPI 2.0')).toBeVisible();
  },
};

const consoleShots: DocsShot[] = [
  // Clients: list, record pages, group editors, wizard, presets
  clientsList,
  { ...clientsList, name: 'entities/clients-list' },
  clientPageWeb,
  { ...clientPageWeb, name: 'entities/clients-list-expanded' },
  {
    name: 'console/client-page-mobile', kind: 'frame', path: clientsPath, state: withClients(mobileClient),
    before: async page => {
      await openClient('storefront-mobile')(page);
      await expect(page.getByRole('region', { name: 'Tokens' })).toContainText('180d');
    },
  },
  {
    name: 'console/client-page-partner', kind: 'frame', path: clientsPath, state: withClients(partnerClient),
    before: async page => {
      await openClient('acme-wallet')(page);
      await expect(page.getByRole('region', { name: 'Ownership' })).toContainText('Partner app');
    },
  },
  {
    name: 'console/client-page-service', kind: 'frame', path: clientsPath, state: { ...withClients(serviceClient), tenants: standardTenants },
    before: async page => {
      await openClient('billing-job')(page);
      await expect(page.getByRole('region', { name: 'Client secret' })).toBeVisible();
    },
  },
  {
    name: 'console/client-created-secret', kind: 'frame', path: clientsPath, state: { ...withClients(), tenants: standardTenants },
    before: async page => {
      await serviceReview(page);
      await page.getByRole('button', { name: 'Create Client', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Client created: Billing Job', exact: true })).toBeVisible();
      await expect(page.locator('.secret-banner .secret-value')).toBeVisible();
    },
  },
  {
    name: 'console/client-edit-general', kind: 'frame', path: clientsPath, state: withClients(webClient),
    before: async page => {
      await editClient('storefront-web', 'general', 'General')(page);
      await expect(page.locator('versola-client-form #client-id')).toHaveValue('storefront-web');
    },
  },
  {
    name: 'console/client-edit-scopes', kind: 'frame', path: clientsPath, state: withClients(webClient),
    before: async page => {
      await editClient('storefront-web', 'scopes', 'Scopes')(page);
      await expect(page.locator('versola-client-form #scope-profile')).toBeChecked();
    },
  },
  {
    name: 'console/client-edit-signin', kind: 'frame', fullPage: true, path: clientsPath, state: withClients(webClient),
    before: async page => {
      await editClient('storefront-web', 'sign-in', 'Sign-in')(page);
      await expect(page.getByText('Primary credentials')).toBeVisible();
    },
  },
  {
    name: 'console/client-edit-tokens', kind: 'frame', path: clientsPath, state: withClients(webClient),
    before: async page => {
      await editClient('storefront-web', 'tokens', 'Tokens')(page);
      await expect(page.locator('versola-client-form .locked-setting', { hasText: 'Refresh token' })).toContainText('offline_access');
    },
  },
  {
    name: 'console/client-edit-tokens-mobile', kind: 'frame', path: clientsPath, state: withClients(mobileClient),
    before: async page => {
      await editClient('storefront-mobile', 'tokens', 'Tokens')(page);
      await expect(page.getByLabel('Refresh Token TTL (days) *')).toHaveValue('180');
    },
  },
  {
    name: 'console/client-edit-token-binding', kind: 'frame', path: clientsPath, state: withClients(certificateWebClient),
    before: async page => {
      await editClient('storefront-web', 'token binding and requests', 'Type')(page);
      await expect(page.getByText("Bound to the client's certificate (RFC 8705 §3.1)")).toBeVisible();
    },
  },
  {
    name: 'console/client-edit-registration-consent', kind: 'frame', path: clientsPath, state: withClients(webClient),
    before: async page => {
      await editClient('storefront-web', 'ownership', 'Ownership')(page);
      await expect(page.getByRole('button', { name: 'First party app', exact: true })).toHaveAttribute('aria-pressed', 'true');
    },
  },
  {
    name: 'console/client-edit-registration-consent-partner', kind: 'frame', fullPage: true, path: clientsPath, state: withClients(partnerClient),
    before: async page => {
      await editClient('acme-wallet', 'ownership', 'Ownership')(page);
      await expect(page.getByRole('group', { name: 'Required scopes' }).getByRole('button', { name: 'phone', exact: true })).toHaveAttribute('aria-pressed', 'true');
    },
  },
  {
    name: 'console/client-edit-permissions-service', kind: 'frame', path: clientsPath, state: { ...withClients(serviceClient), tenants: standardTenants },
    before: async page => {
      await editClient('billing-job', 'permissions', 'Permissions')(page);
      await expect(page.locator('versola-client-form .checkbox-item', { hasText: 'orders.read' }).getByRole('checkbox')).toBeChecked();
    },
  },
  {
    name: 'console/client-edit-branding-logout', kind: 'frame', path: clientsPath, state: { ...withClients(webClient), themePresets: presets, themes },
    before: async page => {
      await editClient('storefront-web', 'branding and logout', 'Branding and logout')(page);
      await expect(page.getByLabel('Forms Theme')).toBeVisible();
    },
  },
  {
    name: 'console/client-edit-appearance', kind: 'frame', path: clientsPath,
    state: { ...withClients({ ...partnerClient, theme: 'acme', themeOverrides: { brand: '#b4348a', shape: 'sharp' } }), themePresets: presets, themes },
    before: async page => {
      await editClient('acme-wallet', 'branding and logout', 'Branding and logout')(page);
      const block = page.getByRole('region', { name: 'Appearance for this app' });
      await expect(block.getByText('Overridden · base: #2563eb')).toBeVisible();
      await block.scrollIntoViewIfNeeded();
    },
  },
  {
    name: 'console/client-authorization-presets', kind: 'crop', path: clientsPath, state: withClients(webClient),
    section: page => page.getByRole('region', { name: 'Authorization presets' }),
    before: async page => {
      await openClient('storefront-web')(page);
      await page.locator('.presets-header').click();
      await expect(page.getByText('No authorization presets configured. Add a preset to simplify OAuth login flows.')).toBeVisible();
    },
  },
  {
    name: 'console/client-authorization-presets-add', kind: 'frame', fullPage: true, path: clientsPath, state: withClients(webClient),
    before: async page => {
      await openClient('storefront-web')(page);
      await page.locator('.presets-header').click();
      await page.getByRole('button', { name: 'Edit presets for storefront-web' }).click();
      await page.getByRole('button', { name: '+ Add Preset' }).click();
      await expect(page.getByRole('heading', { name: 'Add Preset' })).toBeVisible();
      await page.getByLabel('Preset ID *').fill('web-login');
      await page.getByLabel('Description *').fill('Storefront sign-in');
      await page.getByLabel('Post-login redirect URI *').fill('https://storefront.example.com/account');
    },
  },
  {
    name: 'console/client-wizard-web-1-kind', kind: 'frame', fullPage: true, path: clientsPath, state: alpha,
    before: async page => {
      await startWizard(/Web app/)(page);
      await expect(page.getByText('Web app sets:')).toBeVisible();
    },
  },
  {
    name: 'console/client-wizard-mobile-1-kind', kind: 'frame', fullPage: true, path: clientsPath, state: alpha,
    before: async page => {
      await startWizard(/Mobile or desktop app/)(page);
      await expect(page.getByText('Mobile or desktop app sets:')).toBeVisible();
    },
  },
  { name: 'console/client-wizard-web-2-basics', kind: 'frame', fullPage: true, path: clientsPath, state: alpha, before: webBasics },
  { name: 'console/client-wizard-web-3-signin', kind: 'frame', fullPage: true, path: clientsPath, state: alpha, before: webSignIn },
  {
    name: 'console/client-wizard-web-4-review', kind: 'frame', fullPage: true, path: clientsPath, state: alpha,
    before: async page => {
      await webSignIn(page);
      await page.getByRole('button', { name: 'Continue to review', exact: true }).click();
      await expect(page.getByText('Meets FAPI 2.0')).toBeVisible();
    },
  },
  {
    name: 'console/client-wizard-partner-3-consent', kind: 'frame', fullPage: true, path: clientsPath, state: alpha,
    before: async page => {
      await webSignIn(page);
      await page.getByRole('button', { name: 'Partner app', exact: true }).click();
      await expect(page.getByText("How the sign-in and consent screens look for this partner's users.")).toBeVisible();
    },
  },
  { name: 'console/client-wizard-service-2-basics', kind: 'frame', fullPage: true, path: clientsPath, state: { ...alpha, tenants: standardTenants }, before: serviceBasics },
  { name: 'console/client-wizard-service-3-signin', kind: 'frame', fullPage: true, path: clientsPath, state: { ...alpha, tenants: standardTenants }, before: servicePermissions },
  { name: 'console/client-wizard-service-4-review', kind: 'frame', fullPage: true, path: clientsPath, state: { ...alpha, tenants: standardTenants }, before: serviceReview },

  // Edges
  {
    name: 'console/edges-list', kind: 'frame', path: '/?view=edges&tenant=default', state: edgesState,
    before: async page => { await expect(page.getByRole('button', { name: 'Delete edge edge-default' })).toBeVisible(); },
  },
  {
    name: 'console/edges-list-expanded', kind: 'frame', path: '/?view=edges&tenant=default', state: edgesState,
    before: async page => {
      const card = page.locator('.edge-card').filter({ hasText: 'edge-default' });
      await card.locator('.edge-header').click();
      await expect(card.locator('.tenant-badge')).toHaveText(['acme', 'acme-fapi', 'default']);
      await expect(card.locator('.dpop-toggle input[type="checkbox"]')).toBeChecked();
    },
  },
  {
    name: 'console/edge-edit', kind: 'frame', path: '/?view=edges&tenant=default', state: edgesState,
    before: async page => {
      await page.getByRole('button', { name: 'Edit edge edge-default' }).click();
      await expect(page.getByRole('heading', { name: 'Edit Edge', exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Rotate Key', exact: true })).toBeVisible();
    },
  },
  {
    name: 'console/edge-form', kind: 'frame', path: '/?view=edges&tenant=default', state: edgesState,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Edge' }).click();
      await expect(page.getByRole('heading', { name: 'Create New Edge', exact: true })).toBeVisible();
      await expect(page.locator('versola-edge-form .input-prefix')).toHaveText('edge-');
    },
  },

  // Locales
  {
    name: 'console/locales', kind: 'frame', path: `/?view=locales&tenant=${tenant}`, state: { locales },
    before: async page => { await expect(page.locator('.locale-row')).toHaveCount(2); },
  },
  {
    name: 'console/locales-edit', kind: 'frame', path: `/?view=locales&tenant=${tenant}`, state: { locales },
    before: async page => {
      await page.getByRole('button', { name: 'Edit', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Edit Locales', exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Set default' })).toBeVisible();
    },
  },

  // System settings
  {
    name: 'console/system-settings', kind: 'frame', path: '/?view=system-settings', state: systemSettingsState,
    before: async page => { await expect(page.locator('versola-system-settings .card').nth(1).locator('.prop-value')).toHaveText('—'); },
  },
  {
    name: 'console/system-settings-edit', kind: 'frame', path: '/?view=system-settings', state: systemSettingsState,
    before: async page => {
      await page.getByRole('button', { name: 'Edit', exact: true }).click();
      await expect(page.locator('versola-system-settings input[type="url"]')).toBeVisible();
    },
  },

  // Well Known
  {
    name: 'console/well-known', kind: 'frame', fullPage: true, path: '/?view=well-known', state: jwksState,
    before: async page => {
      await realServerMetadata(page);
      await expect(page.locator('.key-card').filter({ hasText: 'central-es256' }).locator('.key-badge')).toHaveText(/Can sign/);
    },
  },
  {
    name: 'console/well-known-add-key', kind: 'frame', path: '/?view=well-known', state: jwksState,
    before: async page => {
      await realServerMetadata(page);
      await page.getByRole('button', { name: '+ Add Key' }).click();
      await expect(page.getByRole('heading', { name: 'Add JWK', exact: true })).toBeVisible();
    },
  },

  // Challenges & Security
  {
    name: 'console/challenges-security', kind: 'frame', fullPage: true, path: `/?view=challenges&tenant=${tenant}`, state: alpha,
    before: async page => { await expect(page.getByRole('heading', { name: 'Challenge Settings', exact: true })).toBeVisible(); },
  },
  {
    name: 'console/challenges-settings-edit', kind: 'frame', fullPage: true, path: `/?view=challenges&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: 'Edit', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Edit Challenge Settings', exact: true })).toBeVisible();
      await expect(page.getByLabel("This tenant's reverse proxy terminates mutual TLS")).toBeChecked();
    },
  },
  {
    // The form opens inline in the middle of the whole Challenges page; the figure is the form.
    name: 'console/challenges-authorization-detail-type-add', kind: 'crop', path: `/?view=challenges&tenant=${tenant}`, state: alpha,
    section: page => page.locator('versola-authorization-detail-type-form'),
    before: async page => {
      await page.getByRole('button', { name: 'Create Type', exact: true }).first().click();
      await expect(page.getByRole('heading', { name: 'Create Authorization Detail Type', exact: true })).toBeVisible();
      await page.getByLabel('Type *').fill('payment_initiation');
      await page.getByLabel('Description *').fill('Payment initiation');
      await expect(page.locator('#detail-type-schema textarea')).toHaveValue(/draft\/2020-12/);
    },
  },
  {
    name: 'console/challenges-template-add', kind: 'frame', fullPage: true, path: `/?view=challenges&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: 'Add Template', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Add OTP Template', exact: true })).toBeVisible();
      await page.getByPlaceholder('e.g. login-otp').fill('signup-otp');
      await page.locator('.edit-loc-card').filter({ hasText: 'en (English)' }).locator('textarea').fill('Your Example sign-up code is {{code}}');
      await page.locator('.edit-loc-card').filter({ hasText: 'ru (Russian)' }).locator('textarea').fill('Ваш код регистрации Example: {{code}}');
      await expect(page.locator('.edit-loc-card')).toHaveCount(2);
    },
  },

  // Forms and Themes
  {
    name: 'console/forms-list', kind: 'frame', path: `/?view=forms&tenant=${tenant}`, state: brandingState,
    before: async page => { await expect(page.locator('.form-id').filter({ hasText: 'consent' })).toBeVisible(); },
  },
  {
    name: 'console/forms-list-expanded', kind: 'frame', fullPage: true, path: `/?view=forms&tenant=${tenant}`, state: brandingState,
    before: async page => {
      await page.locator('.form-header').filter({ hasText: 'consent' }).click();
      await page.locator('.version-card').filter({ hasText: 'v1' }).click();
      await page.getByRole('button', { name: 'Preview', exact: true }).click();
      await page.getByRole('button', { name: 'Everything requested' }).click();
      await page.getByRole('button', { name: 'Mobile', exact: true }).click();
      await expect(page.getByRole('group', { name: 'Preview state' })).toBeVisible();
    },
  },
  {
    name: 'console/form-edit', kind: 'frame', fullPage: true, path: `/?view=forms&tenant=${tenant}`, state: brandingState,
    before: async page => {
      await page.locator('.form-header').filter({ hasText: 'consent' }).click();
      await page.getByRole('button', { name: 'New Version', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'New Version', exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Preview', exact: true }).click();
    },
  },
  {
    name: 'console/themes', kind: 'frame', path: `/?view=themes&tenant=${tenant}`, state: brandingState,
    before: async page => { await expect(page.getByRole('button', { name: 'Edit theme storefront', exact: true })).toBeVisible(); },
  },
  {
    name: 'console/theme-new', kind: 'frame', fullPage: true, path: `/?view=themes&tenant=${tenant}`, state: brandingState,
    before: async page => {
      await realThemeCss(page);
      await page.getByRole('button', { name: '+ New Theme', exact: true }).click();
      await page.getByPlaceholder('e.g. default').fill('acme-web');
      await page.getByRole('button', { name: 'Fintech' }).click();
      await page.getByRole('button', { name: 'Mobile', exact: true }).click();
      await expect.poll(() => page.locator('iframe.preview-iframe').evaluate((f: HTMLIFrameElement) => f.srcdoc)).toContain('--brand:#5b5bd6');
    },
  },
  {
    name: 'console/theme-edit', kind: 'frame', fullPage: true, path: `/?view=themes&tenant=${tenant}`, state: brandingState,
    before: async page => {
      await realThemeCss(page);
      await page.getByRole('button', { name: 'Edit theme storefront', exact: true }).click();
      await page.locator('#preview-form').selectOption('consent');
      await expect(page.getByText('2 overrides on Studio')).toBeVisible();
      await expect.poll(() => page.locator('iframe.preview-iframe').evaluate((f: HTMLIFrameElement) => f.srcdoc)).toContain('--brand:#b4348a');
    },
  },

  // Users
  {
    name: 'console/user-detail', kind: 'frame', fullPage: true, path: `/?view=users&tenant=${tenant}`, state: alpha,
    before: async page => {
      await searchUser('dana', 'Dana Reyes')(page);
      const card = userCard(page);
      await card.getByRole('button', { name: 'Get Claims', exact: true }).click();
      await card.getByRole('button', { name: 'Get Roles', exact: true }).click();
      await card.getByRole('button', { name: 'Get Sessions', exact: true }).click();
      await card.getByRole('button', { name: 'Get Passkeys', exact: true }).click();
      await expect(card.locator('.role-tag', { hasText: 'alpha_admin' })).toBeVisible();
      await expect(card.locator('.session-card')).toBeVisible();
      await expect(card.locator('.passkey-card')).toBeVisible();
    },
  },
  {
    name: 'entities/users-search-empty', kind: 'frame', path: `/?view=users&tenant=${tenant}`, state: alpha,
    before: async page => { await expect(page.getByRole('heading', { name: 'Search for a user', exact: true })).toBeVisible(); },
  },
  { name: 'entities/users-list', kind: 'frame', path: `/?view=users&tenant=${tenant}`, state: alpha, before: searchUser('dana', 'Dana Reyes') },
  {
    name: 'entities/users-list-roles-expanded', kind: 'frame', path: `/?view=users&tenant=${tenant}`, state: alpha,
    before: async page => {
      await searchUser('dana', 'Dana Reyes')(page);
      await userCard(page).getByRole('button', { name: 'Get Roles', exact: true }).click();
      await expect(userCard(page).locator('.role-tag', { hasText: 'alpha_admin' })).toBeVisible();
    },
  },
  {
    name: 'entities/user-form-empty', kind: 'frame', path: `/?view=users&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create User', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Create New User', exact: true })).toBeVisible();
    },
  },
  {
    name: 'entities/user-form-filled', kind: 'frame', path: `/?view=users&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create User', exact: true }).click();
      await page.getByLabel('Email').fill('newowner@example.com');
      await page.getByLabel('Phone').fill('+14155550100');
      await page.getByLabel('Login').fill('newowner');
      await expect(page.getByLabel('Login')).toHaveValue('newowner');
    },
  },
  {
    name: 'entities/user-claims-edit', kind: 'frame', fullPage: true, path: `/?view=users&tenant=${tenant}`, state: alpha,
    before: async page => {
      await searchUser('dana', 'Dana Reyes')(page);
      await userCard(page).getByRole('button', { name: 'Get Claims', exact: true }).click();
      await userCard(page).locator('.icon-action[title="Edit Claims"]').click();
      await expect(page.getByRole('heading', { name: 'Edit Claims', exact: true })).toBeVisible();
      await expect(page.locator('versola-claim-edit .section-label')).toHaveText(['email', 'payments_read', 'phone', 'profile']);
    },
  },

  // Resources
  {
    name: 'entities/resources-list', kind: 'frame', path: `/?view=resources&tenant=${tenant}`, state: alpha,
    before: async page => { await expect(page.locator('.resource-shell')).toHaveCount(1); },
  },
  { name: 'entities/resources-list-expanded', kind: 'frame', path: `/?view=resources&tenant=${tenant}`, state: alpha, before: expandResource },
  {
    name: 'console/resources-list-endpoint-expanded', kind: 'crop', path: `/?view=resources&tenant=${tenant}`,
    state: {
      ...alpha,
      resources: { [tenant]: [{
        ...ordersApi,
        endpoints: [
          ordersApi.endpoints[0],
          { id: 302, method: 'POST', path: '/orders', fetchUserInfo: true, allow: 'request.body.total <= user.orderLimit', inject: [{ target: 'body' as const, name: 'userId', expression: 'token.sub' }], stepUpCondition: 'request.body.total > 100000', stepUpAcr: 'otp', maxAge: 600 },
        ],
      }] },
    },
    section: page => page.locator('.endpoint-card').filter({ hasText: 'user.orderLimit' }),
    before: async page => {
      await expandResource(page);
      await page.locator('.endpoint-card').filter({ hasText: 'POST' }).first().locator('.endpoint-card-header').click();
      const card = page.locator('.endpoint-card').filter({ hasText: 'user.orderLimit' });
      await expect(card.locator('.permission-tag')).toHaveText(['orders.write']);
      await expect(card.getByText('600 seconds')).toBeVisible();
    },
  },
  {
    name: 'entities/resource-form-empty', kind: 'frame', path: `/?view=resources&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Resource', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Create Resource', exact: true })).toBeVisible();
    },
  },
  {
    name: 'entities/resource-form-identity', kind: 'frame', path: `/?view=resources&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Resource', exact: true }).click();
      await page.getByRole('textbox', { name: 'Resource ID' }).fill('invoices-api');
      await page.getByLabel('Absolute resource URI').fill('http://invoices-api.billing.svc.cluster.local:8080');
      await page.getByRole('button', { name: 'internal', exact: true }).click();
      await page.getByRole('textbox', { name: 'Audience client' }).fill('storefront-web');
      await page.getByRole('option', { name: 'storefront-web', exact: true }).click();
      await page.getByRole('button', { name: 'Add audience', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Remove audience storefront-web', exact: true })).toBeVisible();
    },
  },
  {
    name: 'entities/resource-form-endpoint', kind: 'frame', fullPage: true, path: `/?view=resources&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Resource', exact: true }).click();
      await page.getByRole('textbox', { name: 'Resource ID' }).fill('invoices-api');
      await page.getByLabel('Absolute resource URI').fill('http://invoices-api.billing.svc.cluster.local:8080');
      await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
      const endpoint = page.locator('.endpoint-editor').last();
      await endpoint.locator('select').first().selectOption('GET');
      await endpoint.getByPlaceholder('/users').fill('/invoices');
      await endpoint.getByRole('textbox', { name: 'Allow expression' }).click();
      await page.keyboard.type("'orders.read' in token.scope");
      await expect(endpoint.getByText("'orders.read' in token.scope")).toBeVisible();
    },
  },
  {
    name: 'entities/resource-created-secret', kind: 'frame', path: `/?view=resources&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Resource', exact: true }).click();
      await page.getByRole('textbox', { name: 'Resource ID' }).fill('invoices-api');
      await page.getByLabel('Absolute resource URI').fill('http://invoices-api.billing.svc.cluster.local:8080');
      await page.getByRole('button', { name: 'internal', exact: true }).click();
      await page.getByRole('button', { name: 'Create Resource', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Resource created: invoices-api', exact: true })).toBeVisible();
      await expect(page.locator('.secret-banner .secret-value')).toBeVisible();
    },
  },

  {
    name: 'console/resource-form-cel-inject-stepup', kind: 'crop', path: `/?view=resources&tenant=${tenant}`, state: alpha,
    section: page => page.locator('.endpoint-editor').last(),
    before: async page => {
      await page.getByRole('button', { name: '+ Create Resource', exact: true }).click();
      await page.getByRole('textbox', { name: 'Resource ID' }).fill('payments-api');
      await page.getByLabel('Absolute resource URI').fill('http://payments-api.payments.svc.cluster.local:8080');
      await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
      const endpoint = page.locator('.endpoint-editor').last();
      await endpoint.locator('select').first().selectOption('POST');
      await endpoint.getByPlaceholder('/users').fill('/payments/transfer');
      await endpoint.getByRole('checkbox', { name: 'Fetch userinfo' }).check();
      await endpoint.getByRole('textbox', { name: 'Allow expression' }).fill('request.body.amount <= user.daily_transfer_limit');
      await endpoint.getByRole('textbox', { name: 'Step-up condition' }).fill('request.body.amount > 100000');
      await endpoint.getByRole('checkbox', { name: 'otp', exact: true }).check();
      await endpoint.getByPlaceholder('e.g. 3600').fill('600');
      await endpoint.getByRole('button', { name: 'Add rule', exact: true }).click();
      await endpoint.locator('.header-editor-item select').selectOption('body');
      await endpoint.locator('.header-editor-item input[type=text]').fill('userId');
      await endpoint.getByRole('textbox', { name: 'Inject expression' }).fill('token.sub');
      await expect(endpoint.getByRole('textbox', { name: 'Inject expression' })).toHaveValue('token.sub');
    },
  },

  // Tenants
  {
    name: 'entities/tenants-list', kind: 'frame', path: `/?view=tenants&tenant=${tenant}`,
    state: { ...alpha, tenants: [{ id: tenant, description: 'Alpha Workspace', edgeId: null }, { id: 'tenant-bravo', description: 'Bravo Workspace', edgeId: null }, { id: 'acme-prod', description: 'Acme production', edgeId: null, securityProfile: 'standard' }] },
    before: async page => { await expect(page.locator('.tenant-card')).toHaveCount(3); },
  },
  {
    name: 'console/tenant-create-profile-fapi2', kind: 'frame', path: `/?view=tenants&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Tenant', exact: true }).click();
      await page.getByLabel('Tenant ID *').fill('acme-prod');
      await page.getByLabel('Description').fill('Acme production');
      await expect(page.locator('#tenant-security-profile')).toHaveValue('fapi2');
    },
  },
  {
    name: 'console/tenant-create-profile-standard', kind: 'frame', path: `/?view=tenants&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Tenant', exact: true }).click();
      await page.getByLabel('Tenant ID *').fill('acme-prod');
      await page.getByLabel('Description').fill('Acme production');
      await page.getByLabel('Security profile').selectOption('standard');
      await expect(page.getByText('Also admits client secrets, RS256 keys and public native clients. Fixed once the tenant is created.')).toBeVisible();
    },
  },
  {
    name: 'entities/tenant-form-empty', kind: 'frame', path: `/?view=tenants&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Tenant', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Create Tenant', exact: true })).toBeVisible();
    },
  },
  {
    name: 'entities/tenant-form-filled', kind: 'frame', path: `/?view=tenants&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Tenant', exact: true }).click();
      await page.getByLabel('Tenant ID *').fill('acme-prod');
      await page.getByLabel('Description').fill('Acme production');
      await expect(page.getByLabel('Tenant ID *')).toHaveValue('acme-prod');
    },
  },

  // Scopes
  {
    name: 'entities/scopes-list', kind: 'frame', path: `/?view=scopes&tenant=${tenant}`, state: alpha,
    before: async page => { await expect(page.locator('.scope-card')).toHaveCount(scopes.length); },
  },
  {
    name: 'entities/scopes-list-expanded', kind: 'frame', path: `/?view=scopes&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.locator('.scope-card').filter({ hasText: 'OpenID scope' }).first().locator('.scope-header').click();
      await expect(page.getByText('Subject')).toBeVisible();
    },
  },
  {
    name: 'entities/scope-form-empty', kind: 'frame', path: `/?view=scopes&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Create New Scope', exact: true })).toBeVisible();
    },
  },
  {
    name: 'entities/scope-form-filled', kind: 'frame', path: `/?view=scopes&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();
      await page.getByLabel('Scope ID *').fill('orders_read');
      await page.getByLabel('Description *').fill('Read order history');
      await page.getByLabel('Claim ID').fill('order_count');
      await page.getByLabel('Claim Description').fill('Number of orders placed');
      await page.getByRole('button', { name: 'Add Claim', exact: true }).click();
      await expect(page.getByText('order_count')).toBeVisible();
    },
  },

  // Permissions
  {
    name: 'entities/permissions-list', kind: 'frame', path: `/?view=permissions&tenant=${tenant}`, state: alpha,
    before: async page => { await expect(page.locator('.permission-card')).toHaveCount(permissions.length); },
  },
  {
    name: 'entities/permissions-list-expanded', kind: 'frame', path: `/?view=permissions&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.locator('.permission-card').filter({ hasText: 'orders.read' }).first().click();
      await expect(page.locator('.endpoint-row').filter({ hasText: '/orders' }).first()).toBeVisible();
    },
  },
  {
    name: 'entities/permission-form-empty', kind: 'frame', path: `/?view=permissions&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Create Permission', exact: true })).toBeVisible();
    },
  },
  {
    name: 'entities/permission-form-filled', kind: 'frame', fullPage: true, path: `/?view=permissions&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
      await page.getByLabel('Permission ID *').fill('orders.cancel');
      await page.getByLabel('English description').fill('Cancel orders');
      await page.getByLabel('Add resource').selectOption({ label: 'orders-api.orders.svc.cluster.local:8080' });
      await page.getByRole('button', { name: 'Add resource', exact: true }).click();
      await page.getByLabel(/Add endpoint for/).selectOption({ label: 'POST /orders' });
      await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
      await expect(page.locator('.endpoint-row').filter({ hasText: '/orders' })).toBeVisible();
    },
  },

  // Roles
  {
    name: 'entities/roles-list', kind: 'frame', path: `/?view=roles&tenant=${tenant}`, state: alpha,
    before: async page => { await expect(page.locator('.role-card')).toHaveCount(2); },
  },
  {
    name: 'entities/roles-list-expanded', kind: 'frame', path: `/?view=roles&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.locator('.role-card').filter({ hasText: 'Alpha admin' }).first().locator('.role-header').click();
      await expect(page.locator('.permission-item').filter({ hasText: 'orders.read' })).toBeVisible();
    },
  },
  {
    name: 'entities/role-form-empty', kind: 'frame', fullPage: true, path: `/?view=roles&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Create New Role', exact: true })).toBeVisible();
    },
  },
  {
    name: 'entities/role-form-filled', kind: 'frame', fullPage: true, path: `/?view=roles&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
      await page.getByLabel('Role ID *').fill('support');
      await page.getByLabel('Description *').fill('Customer support agent');
      await page.getByRole('checkbox', { name: /orders\.read/ }).check();
      await expect(page.getByRole('checkbox', { name: /orders\.read/ })).toBeChecked();
    },
  },
];

// ---------------------------------------------------------------------------------------------
// Login forms (the real compiled forms, through the harness)

const kz = { region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}' };
const phoneSignIn = { type: 'credential', primaryCredentials: ['phone'], passkey: true, phoneRegions: [kz] };
const partnerLogo = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" rx="14" fill="%23b4348a"/><text x="32" y="43" font-size="30" font-weight="800" text-anchor="middle" fill="white" font-family="sans-serif">S</text></svg>';
const idpLogo = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="140" height="40"><text x="0" y="30" font-size="30" font-weight="800" fill="%231c1b19" font-family="Georgia,serif" font-style="italic">Acme ID</text></svg>';
const typePhone: Act = page => page.locator('.phone-input').fill('7012345678');

const loginShots: DocsShot[] = [
  { name: 'login/sign-in', kind: 'login', form: 'credential', step: phoneSignIn, act: typePhone },
  { name: 'login/sign-in-brand', kind: 'login', form: 'credential', step: phoneSignIn, extraCss: generate(resolve({ base: 'studio', overrides: { brand: '#b4348a', shape: 'sharp' } })), act: typePhone },
  { name: 'login/sign-in-idp-logo', kind: 'login', form: 'credential', step: phoneSignIn, logo: idpLogo, act: typePhone },
  { name: 'login/consent', kind: 'login', form: 'consent', step: { ...scenario('consent', 'typical').step, clientName: 'Storefront', logoUri: partnerLogo, user: { name: 'Anna Petrova' } } },
];

export const docsShots: DocsShot[] = [...consoleShots, ...loginShots];
