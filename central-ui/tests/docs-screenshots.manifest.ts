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

/** The side panel of a small-entity section (scopes, permissions, roles, tenants, edges), found by
 *  its heading. Its body is slotted light DOM, so locators chain from the host, not the dialog. */
const sidePanel = (page: Page, heading: string) =>
  page.locator('versola-side-panel').filter({ has: page.locator('#panel-title', { hasText: new RegExp(`^${heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }) });
const sidePanelDialog = (page: Page, heading: string) => sidePanel(page, heading).getByRole('dialog');

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
  logo?: { light: string; dark: string };
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

// The tenant's logo pair, as artwork with its text already outlined (the wordmark is Inter Bold converted to paths) (an <img> has no fonts): dark ink for the light
// scheme, light ink for the dark one. The console shows them by URL, so the docs spec serves these bodies for them.
const acmeMark = (ink: string) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="126" height="40" viewBox="0 0 126 40"><rect x="2" y="4" width="32" height="32" rx="9" fill="none" stroke="${ink}" stroke-width="3"/><path d="M10.5 27 L18 11 L25.5 27 M13.3 22 H22.7" fill="none" stroke="${ink}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/><path transform="translate(44 28.3)" fill="${ink}" d="M6.4 0.3C8.9 0.3 10.4-0.7 11.2-2.3L11.4-2.3L11.4 0L15.5 0L15.5-11C15.5-15 12.1-16.6 8.6-16.6C4.9-16.6 2.3-15 1.5-12.1L5.5-11.4C5.8-12.5 6.9-13.3 8.6-13.3C10.2-13.3 11.1-12.5 11.1-11.1L11.1-11.1C11.1-10 10-9.9 7.2-9.6C4-9.3 1-8.2 1-4.6C1-1.3 3.3 0.3 6.4 0.3M7.7-2.8C6.2-2.8 5.2-3.4 5.2-4.7C5.2-6 6.3-6.7 7.9-6.9C8.9-7 10.6-7.3 11.2-7.6L11.2-5.9C11.2-4.1 9.7-2.8 7.7-2.8M26.2 0.3C30.1 0.3 32.9-1.8 33.5-5.2L29.4-6C29.1-4.2 28-3.1 26.3-3.1C23.9-3.1 22.7-5.2 22.7-8.1C22.7-11.1 23.9-13.1 26.3-13.1C28-13.1 29-12.1 29.4-10.4L33.5-11.1C32.9-14.5 30.1-16.6 26.2-16.6C21.3-16.6 18.2-13.2 18.2-8.1C18.2-3.1 21.3 0.3 26.2 0.3M36.1 0L40.5 0L40.5-9.9C40.5-11.9 41.7-12.9 43.3-12.9C44.8-12.9 45.8-11.9 45.8-10.3L45.8 0L50.1 0L50.1-10.1C50.1-11.8 51.1-12.9 52.8-12.9C54.2-12.9 55.4-12.1 55.4-10.2L55.4 0L59.8 0L59.8-11C59.8-14.6 57.5-16.6 54.5-16.6C52.3-16.6 50.2-15.5 49.3-12.9C48.5-15.4 47.2-16.6 45.1-16.6C43.1-16.6 41.3-15.6 40.4-13.2L40.2-16.4L36.1-16.4M70.5 0.3C74.3 0.3 76.9-1.5 77.7-4.2L73.8-4.9C73.3-3.7 72.1-3 70.6-3C68.4-3 66.9-4.4 66.8-7L78-7L78-8.3C78-13.9 74.6-16.6 70.3-16.6C65.5-16.6 62.4-13.2 62.4-8.1C62.4-2.9 65.5 0.3 70.5 0.3M66.8-9.8C67-11.8 68.2-13.3 70.3-13.3C72.4-13.3 73.5-11.9 73.8-9.8"/></svg>`;
export const tenantLogoBodies: Record<string, string> = {
  'https://assets.example.com/acme-light.svg': acmeMark('#1c1b19'),
  'https://assets.example.com/acme-dark.svg': acmeMark('#f3f2f0'),
};
const [tenantLogoLight, tenantLogoDark] = Object.keys(tenantLogoBodies);

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
  logoLight: tenantLogoLight,
  logoDark: tenantLogoDark,
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
    ? '--page-light:color-mix(in oklab,var(--brand) 14%,#f7f4ee);--page-dark:color-mix(in oklab,var(--brand) 7%,#0e0e0d);--glow-light:color-mix(in oklab,var(--brand) 16%,transparent);--glow-dark:color-mix(in oklab,var(--brand) 16%,transparent);'
    : t.surface === 'neutral' ? '--page-light:#ecebe7;--page-dark:#0b0b0a;--glow-light:transparent;--glow-dark:transparent;' : '';
  const scheme = t.mode === 'auto' ? '' : `color-scheme:${t.mode};`;
  // The tint strengths (light, dark) of ThemeCss.tint, per surface.
  const tint = ({
    linear: { bold: [34, 45], soft: [34, 34], neutral: [14, 16] },
    horizon: { bold: [36, 46], soft: [36, 34], neutral: [18, 14] },
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
  systemSettings: { passwordRegex: '^(?=.*[A-Za-z])(?=.*\\d).{8,}$', passwordHistorySize: 2, passwordNumDifferent: 1 },
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

// The keys screen also shows who signs with a key, from the tenants' own settings.
const keysState: MockState = {
  ...jwksState,
  tenants: [{ id: tenant, description: 'Alpha Workspace', edgeId: null, securityProfile: 'fapi2' as const, signingKeyId: 'central-es256' }],
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

// The mock serves a one-line metadata document; the docs show a realistic one, as the overrides.
const realServerMetadata: Act = async page => {
  await page.route('**/configuration/server-metadata', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(serverMetadata) }));
  await page.reload();
  await expect(page.getByRole('region', { name: 'Server metadata overrides' })).toContainText('pushed_authorization_request_endpoint');
};

// ---------------------------------------------------------------------------------------------
// Actions shared by several entries

const openClient = (id: string): Act => async page => {
  await page.getByRole('button', { name: new RegExp(`^Open client (.* )?${id}$`) }).click();
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
    before: async page => {
      await expect(page.getByRole('button', { name: 'Open edge edge-default' })).toBeVisible();
      await expect(page.getByRole('columnheader')).toHaveText(['Edge', 'Tenants', 'Key', 'DPoP nonce', 'Actions']);
    },
  },
  {
    name: 'console/edges-list-expanded', kind: 'frame', path: '/?view=edges&tenant=default', state: edgesState,
    before: async page => {
      await page.getByRole('button', { name: 'Open edge edge-default' }).click();
      const edge = sidePanel(page, 'edge-default');
      await expect(edge.getByRole('region', { name: 'Linked tenants' })).toContainText('acme');
      await expect(edge.getByRole('region', { name: 'DPoP nonce' })).toContainText('Required on every proxied call');
    },
  },
  {
    name: 'console/edge-edit', kind: 'frame', path: '/?view=edges&tenant=default', state: edgesState,
    before: async page => {
      await page.getByRole('button', { name: 'Open edge edge-default' }).click();
      await sidePanel(page, 'edge-default').getByRole('button', { name: 'Edit dpop nonce', exact: true }).click();
      await expect(sidePanelDialog(page, 'Edit DPoP nonce')).toBeVisible();
      await expect(sidePanel(page, 'Edit DPoP nonce').getByRole('button', { name: 'Save DPoP nonce' })).toBeVisible();
    },
  },
  {
    name: 'console/edge-form', kind: 'frame', path: '/?view=edges&tenant=default', state: edgesState,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Edge' }).click();
      await expect(sidePanelDialog(page, 'Register edge')).toBeVisible();
      await expect(sidePanel(page, 'Register edge').getByText('Registered as')).toBeVisible();
      await expect(sidePanel(page, 'Register edge').getByLabel('Edge ID *')).toBeVisible();
    },
  },

  // Locales (a card of System settings)
  {
    name: 'console/locales', kind: 'frame', path: '/?view=system-settings', state: { ...systemSettingsState, locales: [...locales, { code: 'kk', name: 'Kazakh', isDefault: false, active: false }] },
    before: async page => { await expect(page.getByRole('row').filter({ hasText: 'Kazakh' })).toContainText('Inactive'); },
  },
  {
    name: 'console/locales-edit', kind: 'frame', path: '/?view=system-settings', state: { ...systemSettingsState, locales },
    before: async page => {
      await page.getByRole('button', { name: 'Actions for ru' }).click();
      await expect(page.getByRole('menuitem', { name: 'Set as default' })).toBeVisible();
    },
  },
  {
    name: 'console/locales-add', kind: 'frame', path: '/?view=system-settings', state: { ...systemSettingsState, locales },
    before: async page => {
      await page.getByRole('button', { name: '+ Add locale' }).click();
      await page.getByLabel('Language *').selectOption('de');
      await expect(page.getByRole('button', { name: 'Add locale', exact: true })).toBeVisible();
    },
  },
  {
    name: 'console/locales-activation-failure', kind: 'frame', path: '/?view=system-settings',
    state: { ...systemSettingsState, locales: [...locales, { code: 'kk', name: 'Kazakh', isDefault: false, active: false }], localeActivationMissing: { kk: ['forms.sign-in.title', 'scopes.email.description', 'otp-templates.default.sms'] } },
    before: async page => {
      await page.getByRole('button', { name: 'Actions for kk' }).click();
      await page.getByRole('menuitem', { name: 'Activate kk' }).click();
      await expect(page.getByRole('alert').filter({ hasText: 'Cannot activate kk' })).toBeVisible();
    },
  },

  // System settings
  {
    name: 'console/system-settings', kind: 'frame', path: '/?view=system-settings', state: systemSettingsState,
    before: async page => { await expect(page.getByRole('region', { name: 'Password policy' })).toContainText('previous passwords'); },
  },
  {
    name: 'console/system-settings-edit', kind: 'frame', path: '/?view=system-settings', state: systemSettingsState,
    before: async page => {
      await page.getByRole('button', { name: 'Edit password policy' }).click();
      await expect(page.getByLabel('Password regex *')).toBeVisible();
    },
  },

  // Keys & metadata
  {
    name: 'console/well-known', kind: 'frame', fullPage: true, path: '/?view=keys', state: keysState,
    before: async page => {
      await realServerMetadata(page);
      await expect(page.getByRole('row').filter({ hasText: 'central-es256' })).toContainText('Can sign');
    },
  },
  {
    name: 'console/well-known-add-key', kind: 'frame', path: '/?view=keys', state: keysState,
    before: async page => {
      await page.getByRole('button', { name: 'Add key (paste JWK)' }).click();
      await expect(sidePanelDialog(page, 'Add key')).toBeVisible();
    },
  },
  {
    name: 'console/keys-panel', kind: 'frame', path: '/?view=keys&id=central-es256', state: keysState,
    before: async page => { await expect(sidePanel(page, 'central-es256').getByRole('region', { name: 'Used by tenants' })).toContainText(tenant); },
  },
  {
    name: 'console/keys-generate', kind: 'frame', path: '/?view=keys', state: keysState,
    before: async page => {
      await page.getByRole('button', { name: 'Generate key', exact: true }).click();
      await expect(sidePanelDialog(page, 'Generate key')).toBeVisible();
    },
  },

  // Security (was Challenges & Security)
  {
    name: 'console/challenges-security', kind: 'frame', fullPage: true, path: `/?view=security&tenant=${tenant}`, state: alpha,
    before: async page => { await expect(page.getByRole('region', { name: 'Submission limits', exact: true })).toBeVisible(); },
  },
  {
    name: 'console/challenges-logo', kind: 'crop', path: `/?view=security&tenant=${tenant}`, state: alpha,
    section: page => page.getByRole('region', { name: 'Branding', exact: true }),
    before: async page => {
      await expect(page.getByTestId('logo-preview-light').locator('img')).toBeVisible();
      await expect(page.getByTestId('logo-preview-dark').locator('img')).toBeVisible();
    },
  },
  {
    name: 'console/challenges-settings-edit', kind: 'frame', fullPage: true, path: `/?view=security&tenant=${tenant}&edit=tokens`, state: alpha,
    before: async page => {
      await expect(page.getByRole('heading', { name: 'Edit tokens & keys', level: 1 })).toBeVisible();
      await expect(page.getByLabel("This tenant's reverse proxy terminates mutual TLS")).toBeChecked();
    },
  },
  ...(['methods', 'codes', 'passkeys', 'registration', 'stepup', 'sessions', 'logout', 'limits'] as const).map((group): DocsShot => ({
    name: `console/security-edit-${group}`, kind: 'frame', fullPage: true, path: `/?view=security&tenant=${tenant}&edit=${group}`, state: alpha,
    before: async page => { await expect(page.getByRole('button', { name: /^Save / })).toBeVisible(); },
  })),

  // OTP templates
  {
    name: 'console/otp-templates', kind: 'frame', path: `/?view=otp-templates&tenant=${tenant}`, state: alpha,
    before: async page => { await expect(page.getByRole('row').filter({ hasText: 'Email' })).toContainText('2 of 2'); },
  },
  {
    name: 'console/otp-templates-panel', kind: 'frame', path: `/?view=otp-templates&tenant=${tenant}&id=default:otp:email`, state: alpha,
    before: async page => { await expect(sidePanel(page, 'default').locator('iframe.email-preview').first()).toBeVisible(); },
  },
  {
    name: 'console/challenges-template-add', kind: 'frame', path: `/?view=otp-templates&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Template', exact: true }).click();
      const dialog = sidePanel(page, 'Create template');
      await expect(dialog.getByRole('dialog')).toBeVisible();
      await dialog.getByLabel('Template ID *').fill('signup-otp');
      await dialog.locator('#message-en').fill('Your Example sign-up code is {{code}}');
      await dialog.locator('#message-ru').fill('Ваш код регистрации Example: {{code}}');
      await expect(dialog.locator('#message-ru')).toHaveValue(/регистрации/);
    },
  },

  // Authorization details
  {
    name: 'console/authorization-details', kind: 'frame', path: `/?view=authorization-details&tenant=${tenant}`, state: alpha,
    before: async page => { await expect(page.getByRole('row').filter({ hasText: 'payment_initiation' })).toContainText('required: type'); },
  },
  {
    name: 'console/authorization-details-panel', kind: 'frame', path: `/?view=authorization-details&tenant=${tenant}&id=payment_initiation`, state: alpha,
    before: async page => { await expect(sidePanel(page, 'Payment initiation').getByRole('region', { name: 'JSON Schema' })).toBeVisible(); },
  },
  {
    // The type form is the panel's create form; the figure is the panel with the default schema.
    name: 'console/challenges-authorization-detail-type-add', kind: 'frame', path: `/?view=authorization-details&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Type', exact: true }).click();
      const dialog = sidePanel(page, 'Create type');
      await expect(dialog.getByRole('dialog')).toBeVisible();
      await dialog.getByLabel('Type *').fill('payment_initiation_v2');
      await dialog.locator('#detail-description').fill('Payment initiation');
      await expect(dialog.locator('versola-code-editor#detail-schema textarea')).toHaveValue(/draft\/2020-12/);
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
      await realThemeCss(page);
      // The forms screen fetches the previews' stylesheets on load, so reload to fetch them through the real one.
      await page.reload();
      await page.locator('.form-header').filter({ hasText: 'consent' }).click();
      await page.locator('.version-card').filter({ hasText: 'v1' }).click();
      await page.getByRole('button', { name: 'Preview', exact: true }).click();
      await page.getByRole('button', { name: 'Everything requested' }).click();
      await page.getByRole('button', { name: 'Mobile', exact: true }).click();
      await expect(page.getByRole('group', { name: 'Preview state' })).toBeVisible();
      await expect.poll(() => page.locator('iframe.preview-iframe').evaluate((f: HTMLIFrameElement) => f.srcdoc)).toContain('--brand:');
    },
  },
  {
    name: 'console/form-edit', kind: 'frame', fullPage: true, path: `/?view=forms&tenant=${tenant}`, state: brandingState,
    before: async page => {
      await realThemeCss(page);
      // The forms screen fetches the previews' stylesheets on load, so reload to fetch them through the real one.
      await page.reload();
      await page.locator('.form-header').filter({ hasText: 'consent' }).click();
      await page.getByRole('button', { name: 'New Version', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'New Version', exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Preview', exact: true }).click();
      await expect.poll(() => page.locator('iframe.preview-iframe').evaluate((f: HTMLIFrameElement) => f.srcdoc)).toContain('--brand:');
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
    before: async page => {
      await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open tenant/ })).toHaveCount(3);
    },
  },
  {
    name: 'console/tenant-create-profile-fapi2', kind: 'frame', path: `/?view=tenants&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Tenant', exact: true }).click();
      const dialog = sidePanel(page, 'Create tenant');
      await dialog.getByLabel('Tenant ID *').fill('acme-prod');
      await dialog.getByLabel('Description *').fill('Acme production');
      await expect(dialog.getByRole('button', { name: /FAPI 2\.0/ })).toHaveAttribute('aria-pressed', 'true');
    },
  },
  {
    name: 'console/tenant-create-profile-standard', kind: 'frame', path: `/?view=tenants&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Tenant', exact: true }).click();
      const dialog = sidePanel(page, 'Create tenant');
      await dialog.getByLabel('Tenant ID *').fill('acme-prod');
      await dialog.getByLabel('Description *').fill('Acme production');
      await dialog.getByRole('button', { name: /^Standard/ }).click();
      await expect(dialog.getByText('Also admits client secrets, RS256 keys and public native clients.')).toBeVisible();
    },
  },
  {
    name: 'entities/tenant-form-empty', kind: 'frame', path: `/?view=tenants&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Tenant', exact: true }).click();
      await expect(sidePanelDialog(page, 'Create tenant')).toBeVisible();
    },
  },
  {
    name: 'entities/tenant-form-filled', kind: 'frame', path: `/?view=tenants&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Tenant', exact: true }).click();
      const dialog = sidePanel(page, 'Create tenant');
      await dialog.getByLabel('Tenant ID *').fill('acme-prod');
      await dialog.getByLabel('Description *').fill('Acme production');
      await expect(dialog.getByLabel('Tenant ID *')).toHaveValue('acme-prod');
    },
  },

  // Scopes
  {
    name: 'entities/scopes-list', kind: 'frame', path: `/?view=scopes&tenant=${tenant}`, state: alpha,
    before: async page => {
      await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open scope/ })).toHaveCount(scopes.length);
    },
  },
  {
    name: 'entities/scopes-list-expanded', kind: 'frame', path: `/?view=scopes&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: /^Open scope (.* )?openid$/ }).click();
      await expect(sidePanel(page, 'OpenID scope').getByRole('region', { name: 'Claims' })).toContainText('Subject');
    },
  },
  {
    name: 'entities/scope-form-empty', kind: 'frame', path: `/?view=scopes&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();
      await expect(sidePanelDialog(page, 'Create scope')).toBeVisible();
    },
  },
  {
    name: 'entities/scope-form-filled', kind: 'frame', path: `/?view=scopes&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();
      const dialog = sidePanel(page, 'Create scope');
      await dialog.getByLabel('Scope ID *').fill('orders_read');
      await dialog.locator('#scope-description').fill('Read order history');
      await dialog.getByRole('button', { name: '+ Add claim' }).click();
      await dialog.getByLabel('Claim ID').fill('order_count');
      await dialog.locator('versola-localized-text-editor').nth(1).locator('input').fill('Number of orders placed');
      await expect(dialog.getByLabel('Claim ID')).toHaveValue('order_count');
    },
  },

  // Permissions
  {
    name: 'entities/permissions-list', kind: 'frame', path: `/?view=permissions&tenant=${tenant}`, state: alpha,
    before: async page => {
      await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open permission/ })).toHaveCount(permissions.length);
    },
  },
  {
    name: 'entities/permissions-list-expanded', kind: 'frame', path: `/?view=permissions&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: /^Open permission (.* )?orders\.read$/ }).click();
      await expect(sidePanel(page, 'Read orders').getByRole('region', { name: 'Endpoints' })).toContainText('/orders');
    },
  },
  {
    name: 'entities/permission-form-empty', kind: 'frame', path: `/?view=permissions&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
      await expect(sidePanelDialog(page, 'Create permission')).toBeVisible();
    },
  },
  {
    name: 'entities/permission-form-filled', kind: 'frame', fullPage: true, path: `/?view=permissions&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
      const dialog = sidePanel(page, 'Create permission');
      await dialog.getByLabel('Permission ID *').fill('orders.cancel');
      await dialog.locator('#permission-description').fill('Cancel orders');
      await dialog.getByLabel('Add resource').selectOption({ label: 'orders-api.orders.svc.cluster.local:8080' });
      await dialog.getByRole('button', { name: 'Add resource', exact: true }).click();
      await dialog.getByLabel(/Add endpoint for/).selectOption({ label: 'POST /orders' });
      await dialog.getByRole('button', { name: 'Add endpoint', exact: true }).click();
      await expect(dialog.locator('.endpoint-path', { hasText: '/orders' })).toBeVisible();
    },
  },

  // Roles
  {
    name: 'entities/roles-list', kind: 'frame', path: `/?view=roles&tenant=${tenant}`, state: alpha,
    before: async page => {
      await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open role/ })).toHaveCount(2);
    },
  },
  {
    name: 'entities/roles-list-expanded', kind: 'frame', path: `/?view=roles&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: /^Open role (.* )?alpha_admin$/ }).click();
      await expect(sidePanel(page, 'Alpha admin').getByRole('region', { name: 'Permissions (2)' })).toContainText('orders.read');
    },
  },
  {
    name: 'entities/role-form-empty', kind: 'frame', fullPage: true, path: `/?view=roles&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
      await expect(sidePanelDialog(page, 'Create role')).toBeVisible();
    },
  },
  {
    name: 'entities/role-form-filled', kind: 'frame', fullPage: true, path: `/?view=roles&tenant=${tenant}`, state: alpha,
    before: async page => {
      await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
      const dialog = sidePanel(page, 'Create role');
      await dialog.getByLabel('Role ID *').fill('support');
      await dialog.locator('#role-description').fill('Customer support agent');
      await dialog.getByRole('checkbox', { name: /orders\.read/ }).check();
      await expect(dialog.getByRole('checkbox', { name: /orders\.read/ })).toBeChecked();
    },
  },
];

// ---------------------------------------------------------------------------------------------
// Login forms (the real compiled forms, through the harness)

const kz = { region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}' };
const phoneSignIn = { type: 'credential', primaryCredentials: ['phone'], passkey: true, phoneRegions: [kz] };
const partnerLogo = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" rx="14" fill="%23b4348a"/><text x="32" y="43" font-size="30" font-weight="800" text-anchor="middle" fill="white" font-family="sans-serif">S</text></svg>';
const asDataUri = (svg: string) => `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
const tenantLogo = { light: asDataUri(tenantLogoBodies[tenantLogoLight]), dark: asDataUri(tenantLogoBodies[tenantLogoDark]) };
const typePhone: Act = page => page.locator('.phone-input').fill('7012345678');

const loginShots: DocsShot[] = [
  { name: 'login/sign-in', kind: 'login', form: 'credential', step: phoneSignIn, act: typePhone },
  { name: 'login/sign-in-brand', kind: 'login', form: 'credential', step: phoneSignIn, extraCss: generate(resolve({ base: 'studio', overrides: { brand: '#b4348a', shape: 'sharp' } })), act: typePhone },
  { name: 'login/sign-in-idp-logo', kind: 'login', form: 'credential', step: phoneSignIn, logo: tenantLogo, act: typePhone },
  { name: 'login/consent', kind: 'login', form: 'consent', step: { ...scenario('consent', 'typical').step, clientName: 'Storefront', logoUri: partnerLogo, user: { name: 'Anna Petrova' } } },
];

export const docsShots: DocsShot[] = [...consoleShots, ...loginShots];
