import { expect, test, type Page } from '@playwright/test';
import { INVALID_FIELD_BORDER, clientWrites, findClientWrite, findRequest, loadAdminApp } from './fixtures';

const clientsPath = '/?view=clients&tenant=tenant-alpha';

const alphaClient = {
  id: 'alpha-web',
  clientName: { en: 'Alpha Web' },
  redirectUris: ['https://alpha.example/callback'],
  scope: ['openid'],
  permissions: ['alpha.read'],
  secretRotation: false,
  // Required and NaN-checked by the Access Token TTL input - omitting it leaves the field's
  // native "required" constraint failing on an empty value, which silently swallows every
  // edit-and-submit test below without a JS error to point at.
  accessTokenTtl: 3600,
  authFlow: {
    primary: {
      credentials: ['phone'],
      inlinePassword: false,
      factors: [{ type: 'otp', required: true }],
    },
    passkey: null,
    otpType: 'sms',
  },
};

const serviceClient = {
  id: 'service-client',
  clientName: { en: 'Service Client' },
  redirectUris: ['https://service.example/callback'],
  scope: ['email'],
  permissions: ['alpha.write'],
  secretRotation: false,
};

const offlineClient = {
  ...alphaClient,
  scope: ['openid', 'offline_access'],
  refreshTokenTtl: 180 * 24 * 60 * 60,
};

/** A client registered from the device/high combination and still holding what it asked for. */
const templatedClient = {
  ...alphaClient,
  id: 'mobile-checkout',
  clientName: { en: 'Mobile Checkout' },
  authMethod: 'none',
  accessTokenTtl: 3600,
  dpopBoundAccessTokens: true,
  certificateBoundAccessTokens: false,
  requirePushedAuthorizationRequests: true,
  requireSignedRequestObject: false,
  template: { kind: 'device' },
  createdAt: '2026-02-01T09:00:00Z',
};

/** The same client after two of the template's settings were moved. */
const driftedClient = {
  ...templatedClient,
  accessTokenTtl: 4 * 3600,
  requirePushedAuthorizationRequests: false,
};

/** A web client moved onto a shared secret afterwards: the template asked for certificate-bound
  * tokens, which nothing about a secret can carry. */
const secretBackedHighClient = {
  ...alphaClient,
  id: 'web-downgraded',
  clientName: { en: 'Web Downgraded' },
  authMethod: 'client_secret',
  accessTokenTtl: 3600,
  dpopBoundAccessTokens: false,
  certificateBoundAccessTokens: false,
  requirePushedAuthorizationRequests: true,
  requireSignedRequestObject: false,
  template: { kind: 'web' },
  createdAt: '2026-02-01T09:00:00Z',
};

/** A tenant that is not held to FAPI 2.0: what admits a client secret, so a service app can pick one. */
const standardTenants = [
  { id: 'tenant-alpha', description: 'Alpha Workspace', edgeId: null, securityProfile: 'standard' as const },
  { id: 'tenant-bravo', description: 'Bravo Workspace', edgeId: null },
];

type Profile = 'fapi2' | 'standard';

/** The tenant under test, on the given profile; a tenant is on FAPI 2.0 unless seeded otherwise. */
const tenantsOn = (profile: Profile) => (profile === 'standard' ? standardTenants : undefined);

const mtlsTerminatingSettings = {
  tenantId: 'tenant-alpha',
  allowedPrefixes: [],
  passwordRegex: null,
  submissionLimits: { otpRequest: [], otpSubmit: [], passwordSubmit: [], passkeyAssertion: [], banDurationSeconds: 0 },
  otpLength: 6,
  otpResendAfter: 60,
  passkeySettings: null,
  mtlsCertificateHeader: 'x-client-cert',
};

function clientCard(page: Page, text: string) {
  return page.locator('versola-clients-list tbody tr').filter({ hasText: text }).first();
}

/** A client's page: read-only, with an Edit button per group of settings. */
async function openClientPage(page: Page, id: string) {
  await page.getByRole('button', { name: `Open client ${id}`, exact: true }).click();
  await expect(page.locator('versola-clients-list .view-title')).toBeVisible();
}

/** What the edit page used to call each group, and what the client page calls it now. */
const SECTION_TITLES: Record<string, string> = {
  'Token lifetimes': 'Tokens',
  'How users sign in': 'Sign-in',
  'Scopes and permissions': 'Scopes and permissions',
  'Registration and consent': 'Registration and consent',
  'Branding and logout': 'Branding and logout',
  'Client credential and binding': 'Type',
  'Request integrity': 'Type',
};

/**
 * Creation opens on step 1, which decides the client's credential and request-integrity
 * settings together with the tenant's profile. A web or mobile client gets its credential from
 * edge; a Service app is where a credential is chosen (a secret only on a standard tenant).
 */
async function startCreate(page: Page, kind = 'Web app') {
  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: new RegExp(kind) }).click();
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
}

/** Step 2 gates on these two, so every creation has to fill them before it can go on. */
async function fillBasics(page: Page, id: string, name: string) {
  await page.locator('versola-client-form #client-id').fill(id);
  await page.locator('versola-client-form input#client-name').fill(name);
}

async function addRedirectUri(page: Page, uri: string) {
  await page.locator('versola-client-form #redirect-uri').fill(uri);
  await page.locator('versola-client-form #redirect-uri').press('Enter');
}

/** A key set is what a high-assurance client authenticates with, so step 2 will not pass without one. */
const SAMPLE_JWKS = JSON.stringify({ keys: [{ kty: 'EC', crv: 'P-256', x: 'x-coordinate', y: 'y-coordinate' }] });

/** The step-2 row that reveals the logout channel picker; closed until it is asked for. */
async function openLogout(page: Page) {
  await page.getByRole('button', { name: /Logout/ }).click();
}

/** A step-3 checkbox is a row carrying its own explanation, so it is matched on its label. */
function checkRow(page: Page, label: string) {
  return page.locator('versola-client-form .check-row').filter({ hasText: label }).first();
}

async function continueToThirdStep(page: Page) {
  await page.getByRole('button', { name: /^Continue to (sign-in|permissions)$/ }).click();
}

async function continueToReview(page: Page) {
  await page.getByRole('button', { name: 'Continue to review', exact: true }).click();
}

async function submitCreate(page: Page) {
  await page.getByRole('button', { name: 'Create Client', exact: true }).click();
}

/**
 * The edit page keeps every group of settings collapsed behind its summary, so a test that
 * touches one of its fields opens that group first. Idempotent: opening an open section
 * would close it again.
 */
async function openEditSection(page: Page, title: string) {
  const section = SECTION_TITLES[title] ?? title;
  await page.getByRole('button', { name: `Edit ${section.toLowerCase()}`, exact: true }).click();
}

async function saveEdit(page: Page) {
  await page.getByRole('button', { name: /^Save /, exact: false }).click();
}

/** Step 2 to the created client, for a test with nothing to say about steps 3 and 4. */
async function finishCreate(page: Page) {
  await continueToThirdStep(page);
  await continueToReview(page);
  await submitCreate(page);
}

test('renders client details and filters by client id', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaClient, serviceClient] },
      scopes: { 'tenant-alpha': [{ scope: 'openid', description: { en: 'OpenID scope' }, claims: [] }, { scope: 'email', description: { en: 'Email scope' }, claims: [] }] },
      permissions: {
        'tenant-alpha': [
          { permission: 'alpha.read', description: { en: 'Read alpha resources' }, endpointIds: [101] },
          { permission: 'alpha.write', description: { en: 'Write alpha resources' }, endpointIds: [101] },
        ],
      },
    },
  });

  const search = page.getByLabel('Search clients');
  await search.fill('service-client');
  await expect(clientCard(page, 'Service Client')).toBeVisible();
  await expect(page.locator('versola-clients-list tbody tr').filter({ hasText: 'alpha-web' })).toHaveCount(0);

  await search.fill('missing-client');
  await expect(page.getByRole('heading', { name: 'No clients match', exact: true })).toBeVisible();
  await search.fill('');

  // A row opens the client's own page, which states everything the list leaves out.
  await openClientPage(page, 'alpha-web');
  const alpha = page.locator('versola-clients-list');
  await expect(alpha).toContainText('https://alpha.example/callback');
  await expect(alpha).toContainText('openid');
  await expect(alpha).toContainText('alpha.read');
  await expect(alpha).toContainText('1h');
  await expect(alpha.getByRole('region', { name: 'Sign-in' })).toBeVisible();
  await expect(alpha.getByRole('region', { name: 'Branding and logout' })).toContainText('Forms theme');
  await expect(alpha.getByRole('region', { name: 'General' })).toContainText('Redirect URIs');
});

test('filters the list by client type', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient, serviceClient, edgeNativeClient] } },
  });

  const filter = page.getByRole('group', { name: 'Filter by type' });
  await filter.getByRole('button', { name: /^Mobile or desktop app/ }).click();
  await expect(page.locator('versola-clients-list tbody tr')).toHaveCount(1);
  await expect(clientCard(page, 'Edge Mobile')).toBeVisible();
  await filter.getByRole('button', { name: /^All/ }).click();
  await expect(page.locator('versola-clients-list tbody tr')).toHaveCount(3);
});

test('shows and updates refresh token TTL in days for offline clients', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [offlineClient] },
      scopes: {
        'tenant-alpha': [
          { scope: 'openid', description: { en: 'OpenID scope' }, claims: [] },
          { scope: 'offline_access', description: { en: 'Offline access scope' }, claims: [] },
        ],
      },
    },
  });

  await openClientPage(page, 'alpha-web');
  const client = page.getByRole('region', { name: 'Tokens' });
  await expect(client).toContainText('Refresh token');
  await expect(client).toContainText('180d');

  await openEditSection(page, 'Token lifetimes');
  await expect(page.getByLabel('Refresh Token TTL (days) *')).toHaveValue('180');
  await page.getByLabel('Refresh Token TTL (days) *').fill('120');
  await saveEdit(page);

  expect((await findClientWrite(api.requests, 'PUT')).body).toMatchObject({
    refreshTokenTtl: 120 * 24 * 60 * 60,
  });
});

test('shows refresh token TTL only after selecting offline_access when creating a client', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [] },
      scopes: {
        'tenant-alpha': [
          { scope: 'openid', description: { en: 'OpenID scope' }, claims: [] },
          { scope: 'offline_access', description: { en: 'Offline access scope' }, claims: [] },
        ],
      },
    },
  });

  await startCreate(page);
  await fillBasics(page, 'offline-client', 'Offline Client');
  await continueToThirdStep(page);

  // Creation asks for the scope, not the lifetime - a new client takes the 180-day default and
  // the edit page is where that is changed.
  await page.getByRole('button', { name: 'offline_access', exact: true }).click();
  await continueToReview(page);
  await expect(page.locator('versola-client-form .review-row').filter({ hasText: 'Refresh token' }))
    .toContainText('180 days');
  await submitCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    refreshTokenTtl: 180 * 24 * 60 * 60,
  });

  await openEditSection(page, 'Token lifetimes');
  await page.getByLabel('Refresh Token TTL (days) *').fill('45');
  await saveEdit(page);
  await expect(page.getByRole('region', { name: 'Tokens' })).toContainText('45d');
});

test('creates a client and shows the generated secret banner', async ({ page }) => {
  // A secret is only on offer to a service app on a tenant that is not held to FAPI 2.0.
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { tenants: standardTenants, clients: { 'tenant-alpha': [alphaClient, serviceClient] } },
  });

  await startCreate(page, 'Service app');

  await fillBasics(page, 'dashboard-client', 'Dashboard Client');
  await continueToThirdStep(page);
  await continueToReview(page);
  await submitCreate(page);

  // Creating lands on the new client's own page, where the secret is shown.
  const created = page.locator('versola-clients-list .view-head');
  const secretBanner = page.locator('.secret-banner').first();
  const secretValue = secretBanner.locator('.secret-value');
  await expect(page.getByRole('heading', { name: 'Client created: Dashboard Client', exact: true })).toBeVisible();
  await expect(secretBanner.getByRole('button', { name: 'Copy secret', exact: true })).toBeVisible();
  await expect(secretValue).toBeVisible();
  expect((await secretValue.textContent())?.trim().length ?? 0).toBeGreaterThan(0);
  await expect(created).toContainText('dashboard-client');
  await expect(page.locator('versola-clients-list')).toContainText('1h');

  expect((await findClientWrite(api.requests, 'POST')).body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'dashboard-client',
    clientName: { en: 'Dashboard Client' },
    allowedScopes: [],
    permissions: [],
    accessTokenTtl: 3600,
    authMethod: 'client_secret',
    dpopBoundAccessTokens: false,
    dpopSigningAlgs: [],
    dpopMinRsaKeySize: null,
    mtlsAuth: null,
    certificateBoundAccessTokens: false,
    jwks: null,
  });
  // A service client is registered at its own endpoint, which has no redirect URIs, flows,
  // branding, PAR or JAR to send: the endpoint records what kind it is.
  expect((await findClientWrite(api.requests, 'POST')).pathname).toBe('/configuration/clients/service');
});

test('dates a just-created client by the time the registration answered with', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page);

  await fillBasics(page, 'dashboard-client', 'Dashboard Client');
  await addRedirectUri(page, 'https://dashboard.example/callback');
  await finishCreate(page);

  // Central settles the registration time; without it coming back in the response the client
  // would read as undated until the list was loaded again.
  await expect(page.locator('versola-clients-list .view-meta')).toContainText('Created today');
});

test('creates a native client authenticated by edge, without a secret or rotation controls', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page, 'Mobile or desktop app');
  await fillBasics(page, 'mobile-app', 'Mobile App');
  await addRedirectUri(page, 'https://app.example.com/callback');
  await finishCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    id: 'mobile-app',
    authMethod: 'tls_client_auth',
    enrollEdgeClientCertificate: true,
  });
  expect((await findClientWrite(api.requests, 'POST')).pathname).toBe('/configuration/clients/native');

  // Edge enrols for the certificate, so there is no secret to copy and nothing to register.
  await expect(page.getByRole('heading', { name: 'Client created: Mobile App', exact: true })).toBeVisible();
  await expect(page.locator('.secret-banner .secret-value')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Copy secret', exact: true })).toHaveCount(0);

  // The page says what kind of client it is, in the same words the wizard used.
  await expect(page.locator('versola-clients-list .view-meta')).toContainText('Mobile or desktop app');

  await expect(page.getByRole('button', { name: 'Rotate secret', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Delete old secret', exact: true })).toHaveCount(0);
});

test('settles the client type from the kind step, and fixes it once created', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  // Step 1 already answered this, so the sign-in step does not ask it again - and cannot be
  // used to contradict it.
  await startCreate(page);
  await fillBasics(page, 'typed-client', 'Typed Client');
  await continueToThirdStep(page);
  await expect(page.getByText('Client type', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Authorization Flow', { exact: true })).toHaveCount(0);

  // An existing client keeps whatever it was registered as - a secret can neither be
  // added to a native client nor taken away from a web one.
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(clientCard(page, 'Alpha Web')).toContainText('Web app');
  await openClientPage(page, 'alpha-web');
  await expect(page.locator('versola-clients-list .view-meta')).toContainText('Web app');
  await openEditSection(page, 'How users sign in');
  await expect(page.getByText('Client type', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'native', exact: true })).toHaveCount(0);
});

test('gates each step behind its own answer and marks the stepper as the wizard advances', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [] } } });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();

  const stepper = page.locator('versola-client-form .wizard-step');
  const kindStep = stepper.filter({ hasText: 'What you are building' });
  const basicsStep = stepper.filter({ hasText: 'Basics' });
  const signInStep = stepper.filter({ hasText: 'Sign-in' });
  const reviewStep = stepper.filter({ hasText: 'Review' });

  // Step 1 is the kind alone: the tenant's profile decides the rest, so there is no tier to pick.
  await expect(page.getByRole('button', { name: 'High assurance', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Compatibility', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /Web app/ })).toHaveAttribute('aria-pressed', 'false');
  await expect(kindStep).toHaveAttribute('aria-current', 'step');
  await expect(basicsStep).not.toHaveClass(/active|done/);

  // No kind chosen yet - step 1 cannot be left.
  await expect(page.getByRole('button', { name: 'Continue to basics', exact: true })).toBeDisabled();

  await page.getByRole('button', { name: /Web app/ }).click();
  await expect(page.getByRole('button', { name: /Web app/ })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: 'Continue to basics', exact: true })).toBeEnabled();

  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();

  await expect(kindStep).toHaveClass(/done/);
  await expect(kindStep.locator('.wizard-step-dot')).toHaveText('✓');
  await expect(basicsStep).toHaveAttribute('aria-current', 'step');
  await expect(signInStep).not.toHaveClass(/active|done/);

  // Basics is missing a client name - step 2 cannot be left either.
  await page.locator('versola-client-form #client-id').fill('gated-client');
  const continueToSignIn = page.getByRole('button', { name: 'Continue to sign-in', exact: true });
  await expect(continueToSignIn).toBeDisabled();
  await page.locator('versola-client-form input#client-name').fill('Gated Client');
  await expect(continueToSignIn).toBeEnabled();
  await continueToSignIn.click();

  await expect(basicsStep).toHaveClass(/done/);
  await expect(signInStep).toHaveAttribute('aria-current', 'step');
  await expect(reviewStep).not.toHaveClass(/active|done/);

  await continueToReview(page);

  await expect(signInStep).toHaveClass(/done/);
  await expect(reviewStep).toHaveAttribute('aria-current', 'step');
});

test('keeps earlier answers when navigating back through the wizard', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [] },
      scopes: {
        'tenant-alpha': [
          { scope: 'openid', description: { en: 'OpenID scope' }, claims: [] },
          { scope: 'profile', description: { en: 'Profile data' }, claims: [] },
        ],
      },
    },
  });

  await startCreate(page);
  await fillBasics(page, 'back-nav-client', 'Back Nav Client');
  await addRedirectUri(page, 'https://backnav.example/callback');
  await continueToThirdStep(page);
  await page.getByRole('button', { name: 'profile', exact: true }).click();

  // Back to basics: the identity fields typed before advancing are still there.
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.locator('versola-client-form #client-id')).toHaveValue('back-nav-client');
  await expect(page.locator('versola-client-form input#client-name')).toHaveValue('Back Nav Client');
  await expect(page.getByText('https://backnav.example/callback')).toBeVisible();

  // Forward again: the scope picked before going back is still picked.
  await continueToThirdStep(page);
  await expect(page.getByRole('button', { name: 'profile', exact: true })).toHaveAttribute('aria-pressed', 'true');

  await continueToReview(page);
  await expect(page.locator('versola-client-form .review-row').filter({ hasText: 'Scopes' }))
    .toContainText('profile');

  // All the way back to step 1: the kind chosen at the very start survives three Backs.
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.getByRole('button', { name: /Web app/ })).toHaveAttribute('aria-pressed', 'true');

  // And all the way forward again to submit - nothing was lost along the way.
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
  await expect(page.locator('versola-client-form #client-id')).toHaveValue('back-nav-client');
  await continueToThirdStep(page);
  await continueToReview(page);
  await submitCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    id: 'back-nav-client',
    redirectUris: ['https://backnav.example/callback'],
    allowedScopes: ['openid', 'profile'],
  });
});

test('jumps to the right step from a review Change link, and returns with the edit applied', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page);
  await fillBasics(page, 'change-link-client', 'Change Link Client');
  await addRedirectUri(page, 'https://changelink.example/callback');
  await continueToThirdStep(page);
  await continueToReview(page);

  // The General group's Change link is step 2's, not some other group's.
  await page.locator('versola-client-form .review-group').filter({ hasText: 'Client ID' })
    .getByRole('button', { name: /^Change/ }).click();
  await expect(page.locator('versola-client-form #client-id')).toHaveValue('change-link-client');
  await page.locator('versola-client-form input#client-name').fill('Renamed Client');

  await continueToThirdStep(page);
  await continueToReview(page);
  await expect(page.locator('versola-client-form .review-row').filter({ hasText: 'Client name' }))
    .toContainText('Renamed Client');

  // The Type group's Change link is step 1's - the kind survives the round trip.
  await page.getByRole('region', { name: 'Type', exact: true }).getByRole('button', { name: /^Change/ }).click();
  await expect(page.getByRole('button', { name: /Web app/ })).toHaveAttribute('aria-pressed', 'true');

  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
  await expect(page.locator('versola-client-form input#client-name')).toHaveValue('Renamed Client');
  await continueToThirdStep(page);
  await continueToReview(page);
  await submitCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    id: 'change-link-client',
    clientName: { en: 'Renamed Client' },
    redirectUris: ['https://changelink.example/callback'],
  });
});

test('cancels out of creation from step 1 without registering a client', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: /Web app/ }).click();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();

  await expect(page.getByRole('button', { name: '+ Create Client', exact: true })).toBeVisible();
  await expect(clientCard(page, 'Alpha Web')).toBeVisible();
  expect(clientWrites(api.requests, 'POST').length > 0).toBeFalsy();
});

test('registers a client that authenticates with an mTLS certificate', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [] },
      challengeSettings: { 'tenant-alpha': mtlsTerminatingSettings },
    },
  });

  // A credential is only a choice for a service app; web and mobile apps get edge's certificate.
  await startCreate(page, 'Service app');
  await fillBasics(page, 'mtls-client', 'mTLS Client');
  await page.getByRole('button', { name: 'certificate', exact: true }).click();

  // The tenant terminates TLS, so no warning.
  await expect(page.getByText('no mTLS certificate header configured')).toHaveCount(0);

  await page.getByLabel('Recognised by').selectOption('san_dns');
  await page.getByLabel('Expected value').fill('client.example.com');
  await continueToThirdStep(page);
  await continueToReview(page);

  // Binding follows the credential rather than being asked for again.
  await expect(page.locator('versola-client-form .review-row').filter({ hasText: 'Token binding' }))
    .toContainText('certificate-bound');
  await submitCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    id: 'mtls-client',
    authMethod: 'tls_client_auth',
    mtlsAuth: { type: 'tls_client_auth', subjectType: 'san_dns', subjectValue: 'client.example.com' },
    // tls_client_auth binds the token to the certificate by itself, so no separate flag is sent.
    certificateBoundAccessTokens: false,
    dpopBoundAccessTokens: false,
    jwks: null,
  });
});

test('blocks an mTLS client with no subject value, and one whose tenant terminates no TLS', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page, 'Service app');
  await fillBasics(page, 'mtls-client', 'mTLS Client');
  await page.getByRole('button', { name: 'certificate', exact: true }).click();

  await expect(page.getByText('no mTLS certificate header configured')).toBeVisible();

  // The basics step will not hand a broken credential on to the steps after it.
  const continueButton = page.getByRole('button', { name: 'Continue to permissions', exact: true });
  await expect(page.getByText('Subject value is required')).toBeVisible();
  await expect(continueButton).toBeDisabled();

  // Central refuses the registration outright without a header to read the certificate from,
  // so a complete form is no more submittable than an incomplete one.
  await page.getByLabel('Expected value').fill('client.example.com');
  await expect(page.getByText('Subject value is required')).toHaveCount(0);
  await expect(continueButton).toBeDisabled();
  expect(clientWrites(api.requests, 'POST')).toHaveLength(0);
});

test('registers a self-signed mTLS client against the key set of its certificate', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [] },
      challengeSettings: { 'tenant-alpha': mtlsTerminatingSettings },
    },
  });

  await startCreate(page, 'Service app');
  await fillBasics(page, 'self-signed-client', 'Self Signed Client');
  await page.getByRole('button', { name: 'certificate', exact: true }).click();
  await page.getByRole('button', { name: 'Self-signed', exact: true }).click();

  const keySet = { keys: [{ kty: 'EC', crv: 'P-256', x: 'x', y: 'y' }] };
  await page.getByLabel('Key set of the certificate').fill(JSON.stringify(keySet));
  await finishCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    id: 'self-signed-client',
    authMethod: 'self_signed_tls_client_auth',
    mtlsAuth: { type: 'self_signed_tls_client_auth' },
    jwks: keySet,
    certificateBoundAccessTokens: false,
  });

  // No secret is generated for a client whose method is not client_secret, so the banner has
  // none to offer.
  await expect(page.getByRole('heading', { name: 'Client created: Self Signed Client', exact: true })).toBeVisible();
  await expect(page.getByText('authenticates with its certificate')).toBeVisible();
  await expect(page.locator('.secret-banner .secret-value')).toHaveCount(0);
});

for (const profile of ['fapi2', 'standard'] as const) {
  test(`applies edge's certificate and the request integrity a web client implies on a ${profile} tenant`, async ({ page }) => {
    const api = await loadAdminApp(page, {
      path: clientsPath,
      state: { tenants: tenantsOn(profile), clients: { 'tenant-alpha': [] } },
    });

    await startCreate(page, 'Web app');

    // The credential is not a choice: edge is the client and authenticates with its certificate.
    await expect(page.getByText('mTLS by edge', { exact: true })).toBeVisible();
    await expect(page.locator('versola-client-form .seg')).toHaveCount(0);
    await expect(page.getByLabel('Key set')).toHaveCount(0);
    await expect(page.getByLabel('Expected value')).toHaveCount(0);

    await fillBasics(page, 'edge-web', 'Edge Web');
    await addRedirectUri(page, 'https://edge.example/callback');
    await continueToThirdStep(page);
    await continueToReview(page);

    // The kind settles these, so review is where they are stated rather than asked for.
    await expect(page.locator('versola-client-form .review-row').filter({ hasText: 'Pushed authorization requests' }))
      .toContainText('required');
    await expect(page.getByRole('region', { name: 'Type', exact: true })).toContainText('Web app');
    await expect(page.getByText('tls_client_auth · certificate issued by central for edge')).toBeVisible();
    // Edge's certificate and PAR make it conformant whichever profile the tenant is on, and the review says so.
    await expect(page.getByText('Meets FAPI 2.0', { exact: true })).toBeVisible();
    await submitCreate(page);

    expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
      id: 'edge-web',
      authMethod: 'tls_client_auth',
      // Tokens bound to edge's certificate need every replica to present the same one, so
      // central issues it; only a native app's edge enrols for its own.
      issueEdgeClientCertificate: true,
      enrollEdgeClientCertificate: false,
      mtlsAuth: null,
      jwks: null,
      requirePushedAuthorizationRequests: true,
      requireSignedRequestObject: false,
      certificateBoundAccessTokens: true,
      dpopBoundAccessTokens: false,
    });
    expect((await findClientWrite(api.requests, 'POST')).pathname).toBe('/configuration/clients/web');
    await expect(page.locator('.secret-banner .secret-value')).toHaveCount(0);
  });

  test(`binds a mobile client to a device key and has edge authenticate it on a ${profile} tenant`, async ({ page }) => {
    const api = await loadAdminApp(page, {
      path: clientsPath,
      state: { tenants: tenantsOn(profile), clients: { 'tenant-alpha': [] } },
    });

    await startCreate(page, 'Mobile or desktop app');

    // A shipped binary has no credential to register: edge holds one, and the device holds a key.
    await expect(page.getByText('mTLS by edge', { exact: true })).toBeVisible();
    await expect(page.getByText('DPoP proof key', { exact: true })).toBeVisible();
    await expect(page.getByText('Public client, no secret', { exact: true })).toHaveCount(0);
    await expect(page.locator('versola-client-form .seg')).toHaveCount(0);

    await fillBasics(page, 'mobile-client', 'Mobile Client');
    await addRedirectUri(page, 'https://app.example.com/callback');
    await continueToThirdStep(page);
    await continueToReview(page);
    await expect(page.getByText('tls_client_auth · certificate enrolled by edge')).toBeVisible();
    await submitCreate(page);

    expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
      id: 'mobile-client',
      authMethod: 'tls_client_auth',
      issueEdgeClientCertificate: false,
      enrollEdgeClientCertificate: true,
      mtlsAuth: null,
    });
    // DPoP, PAR and the rest of what a device fixes are central's to set, not members of its
    // request: the endpoint refuses a body that names them.
    const sent = (await findClientWrite(api.requests, 'POST')).body as Record<string, unknown>;
    expect((await findClientWrite(api.requests, 'POST')).pathname).toBe('/configuration/clients/native');
    for (const fixed of ['dpopBoundAccessTokens', 'certificateBoundAccessTokens', 'requirePushedAuthorizationRequests',
      'requireSignedRequestObject', 'applicationType', 'template', 'jwks']) {
      expect(sent).not.toHaveProperty(fixed);
    }
  });
}

test('refuses a custom-scheme redirect for an app fronted by edge', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [] } } });

  await startCreate(page, 'Mobile or desktop app');
  await fillBasics(page, 'mobile-app', 'Mobile App');
  await addRedirectUri(page, 'com.example.app://callback');

  // Central refuses every non-https redirect for this client, so the form says so up front.
  await expect(page.getByText('accepts only https redirect URIs')).toBeVisible();
  await expect(page.locator('versola-client-form .tag').filter({ hasText: 'com.example.app://callback' })).toHaveCount(0);
});

/** A native app fronted by edge as central stores it: enrolled, so the subject is registered. */
const edgeNativeClient = {
  ...alphaClient,
  id: 'edge-mobile',
  clientName: { en: 'Edge Mobile' },
  authMethod: 'tls_client_auth',
  applicationType: 'native',
  mtlsAuth: { type: 'tls_client_auth', subjectType: 'subject_dn', subjectValue: 'CN=edge-mobile,OU=tenant-alpha,O=Versola' },
  dpopBoundAccessTokens: true,
  certificateBoundAccessTokens: false,
  requirePushedAuthorizationRequests: true,
  requireSignedRequestObject: false,
  template: { kind: 'device' },
  createdAt: '2026-02-01T09:00:00Z',
};

test('keeps the registered subject when an edge-fronted client is edited', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [edgeNativeClient] } },
  });

  await openClientPage(page, 'edge-mobile');
  await openEditSection(page, 'Token lifetimes');
  await page.locator('versola-client-form #ttl').fill('2');
  await saveEdit(page);

  // Absent leaves the stored subject alone; null would clear it, which central refuses.
  expect((await findClientWrite(api.requests, 'PUT')).body.mtlsAuth).toBeUndefined();
});

test('does not report an edge-fronted client as drifted from its template', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [{ ...edgeNativeClient, template: { kind: 'web' }, applicationType: 'web',
      dpopBoundAccessTokens: false, certificateBoundAccessTokens: true }] } },
  });

  await openClientPage(page, 'edge-mobile');

  // The certificate credential binds the token, which is what the web template asks for.
  await expect(page.locator('versola-client-form .template-badge.drift')).toHaveCount(0);
});

test('leaves a service client with no sign-in flow to configure', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page, 'Service app');

  await expect(page.getByPlaceholder('https://app.example.com/callback')).toHaveCount(0);
  await expect(page.getByText('No redirect URIs', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'key', exact: true })).toHaveAttribute('aria-pressed', 'true');

  await fillBasics(page, 'batch-service', 'Batch Service');
  await page.getByLabel('Key set').fill(SAMPLE_JWKS);

  // A service app has no user, so its third step asks what it may call instead of how anyone
  // signs in.
  await continueToThirdStep(page);
  await expect(page.getByText('What it is allowed to call', { exact: true })).toBeVisible();
  await expect(page.getByText('First screen', { exact: true })).toHaveCount(0);
  await continueToReview(page);
  await submitCreate(page);

  const body = (await findClientWrite(api.requests, 'POST')).body as Record<string, unknown>;
  expect(body).toMatchObject({
    id: 'batch-service',
    authMethod: 'private_key_jwt',
    dpopBoundAccessTokens: true,
    certificateBoundAccessTokens: false,
  });
  expect(body).not.toHaveProperty('redirectUris');
});

test('offers a service app a key or a certificate on a FAPI 2.0 tenant, and no secret', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [] } } });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: /Service app/ }).click();
  await expect(page.getByText('This tenant is not FAPI 2.0', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();

  await expect(page.getByRole('button', { name: 'key', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: 'certificate', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByRole('button', { name: 'client secret', exact: true })).toHaveCount(0);
  await expect(page.getByText('Client secret.', { exact: true })).toHaveCount(0);
});

test('offers a service app a secret, a key or a certificate on a standard tenant, secret first', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { tenants: standardTenants, clients: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: /Service app/ }).click();
  await expect(page.locator('versola-client-form').getByText('This tenant is not FAPI 2.0', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();

  await expect(page.getByRole('button', { name: 'client secret', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: 'key', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByRole('button', { name: 'certificate', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByText('Client secret.', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Key set')).toHaveCount(0);

  await fillBasics(page, 'secret-service', 'Secret Service');
  await continueToThirdStep(page);
  await continueToReview(page);
  // A secret is a bearer credential, so the review says this client would not meet FAPI 2.0.
  await expect(page.getByText('Standard profile client, not FAPI 2.0')).toBeVisible();
  await submitCreate(page);

  // A secret is a bearer credential: nothing binds the token.
  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    id: 'secret-service',
    authMethod: 'client_secret',
    dpopBoundAccessTokens: false,
    certificateBoundAccessTokens: false,
    jwks: null,
  });
});

test('says a tenant is not FAPI 2.0 under the kind, and only on a standard tenant', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [] } } });
  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: /Web app/ }).click();
  await expect(page.getByText('This tenant is not FAPI 2.0', { exact: true })).toHaveCount(0);

  const standard = await page.context().newPage();
  await loadAdminApp(standard, { path: clientsPath, state: { tenants: standardTenants, clients: { 'tenant-alpha': [] } } });
  await standard.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await standard.getByRole('button', { name: /Web app/ }).click();
  await expect(standard.getByText('This tenant is not FAPI 2.0', { exact: true })).toBeVisible();
});

test('registers a private_key_jwt service client with a key set that must be valid JSON', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page, 'Service app');
  await fillBasics(page, 'assertion-client', 'Assertion Client');

  await page.getByLabel('Key set').fill('not json');
  await expect(page.getByText('Must be valid JSON')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue to permissions', exact: true })).toBeDisabled();
  expect(clientWrites(api.requests, 'POST')).toHaveLength(0);

  await page.getByLabel('Key set').fill(SAMPLE_JWKS);
  await finishCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    id: 'assertion-client',
    authMethod: 'private_key_jwt',
    mtlsAuth: null,
    jwks: JSON.parse(SAMPLE_JWKS),
    dpopBoundAccessTokens: true,
  });

  // private_key_jwt is a confidential method, but not client_secret - Central issues no
  // secret for it, so there is none to rotate or forget.
  await expect(page.getByRole('button', { name: 'Rotate secret', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Delete old secret', exact: true })).toHaveCount(0);
});

test('creates a client with localized consent name', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [] },
      locales: [
        { code: 'en', name: 'English', isDefault: true, active: true },
        { code: 'ru', name: 'Русский', isDefault: false, active: true },
      ],
    },
  });

  await startCreate(page);
  await page.getByLabel('Client ID').fill('localized-client');
  const nameEditor = page.locator('versola-client-form versola-localized-text-editor');
  await page.getByLabel('Client Name').fill('Localized Client');
  await page.getByRole('tab', { name: 'ru', exact: true }).click();
  await nameEditor.locator('input').fill('Локализованный клиент');
  await finishCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    clientName: { en: 'Localized Client', ru: 'Локализованный клиент' },
  });
});

test('shows OTP settings for OTP factors and locks channel for phone credentials', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaClient] },
      otpTemplates: {
        'tenant-alpha': [
          { id: 'otp-template', tenantId: 'tenant-alpha', localizations: {}, purpose: 'otp', channel: 'sms' },
          { id: 'email-template', tenantId: 'tenant-alpha', localizations: {}, purpose: 'otp', channel: 'email' },
          { id: 'password-template', tenantId: 'tenant-alpha', localizations: {}, purpose: 'password', channel: 'email' },
        ],
      },
    },
  });

  // OTP delivery is not asked for at creation - the credential picks the channel - so this is
  // the edit page, where the template and the channel are both settable.
  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'How users sign in');
  await expect(page.getByText('OTP Settings', { exact: true })).toBeVisible();
  await expect(page.getByLabel('OTP Template')).toBeVisible();
  await expect(page.getByLabel('OTP Template').locator('option')).toHaveCount(1);
  await expect(page.getByLabel('OTP Template').locator('option')).toHaveText('otp-template');
  await expect(page.getByLabel('OTP channel')).toBeDisabled();
  await expect(page.getByLabel('OTP channel')).toHaveValue('sms');

  await page.getByRole('button', { name: 'email', exact: true }).click();
  await expect(page.getByLabel('OTP channel')).toHaveValue('email');
  await expect(page.getByLabel('OTP Template').locator('option')).toHaveCount(1);
  await expect(page.getByLabel('OTP Template').locator('option')).toHaveText('email-template');

  await page.getByRole('button', { name: 'login + password', exact: true }).click();
  await expect(page.getByLabel('OTP channel')).toBeEnabled();

  const secondFactor = page.getByText('Second factor', { exact: true }).locator('..').getByRole('combobox');
  await secondFactor.selectOption('none');
  await expect(page.getByText('OTP Settings', { exact: true })).toHaveCount(0);

  await page.getByText('passkey', { exact: true }).first().click();
  await expect(page.getByText('OTP Settings', { exact: true })).toHaveCount(0);

  const passkeyFactor = page.getByText('Passkey next factor', { exact: true }).locator('..');
  await passkeyFactor.getByRole('combobox').selectOption('otp');
  await expect(page.getByText('OTP Settings', { exact: true })).toBeVisible();
});

test('configures a registration flow and sends it when creating a client', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaClient] },
      roles: {
        'tenant-alpha': [
          { id: 'user', description: { en: 'User' }, permissions: [], active: true },
          { id: 'alpha-admin', description: { en: 'Alpha admin' }, permissions: ['alpha.read'], active: true },
        ],
      },
    },
  });

  await startCreate(page);
  await fillBasics(page, 'registering-client', 'Registering Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://registering.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await continueToThirdStep(page);

  // Creation asks only whether users may sign themselves up; the credential they verify follows
  // from the first screen, and the steps and roles are refined on the edit page.
  await expect(checkRow(page, 'Let users sign themselves up')).toHaveAttribute('aria-pressed', 'false');
  await checkRow(page, 'Let users sign themselves up').click();
  await expect(checkRow(page, 'Let users sign themselves up'))
    .toContainText('The phone is the credential whose ownership is verified');

  await continueToReview(page);
  await expect(page.locator('versola-client-form .review-row').filter({ hasText: 'Self-service sign-up' }))
    .toContainText('on');
  await submitCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    registrationFlow: {
      credential: 'phone',
      steps: [{ type: 'otp' }],
      roleIds: ['user'],
    },
  });

  await openEditSection(page, 'Registration and consent');
  await page.getByLabel('Challenge', { exact: true }).selectOption('setPassword');
  await page.getByRole('group', { name: 'Assigned roles' }).getByRole('checkbox', { name: 'alpha-admin', exact: true }).check();
  await saveEdit(page);

  expect((await findClientWrite(api.requests, 'PUT')).body).toMatchObject({
    registrationFlow: {
      steps: [{ type: 'otp' }, { type: 'setPassword' }],
      roleIds: ['user', 'alpha-admin'],
    },
  });
});

test('configures a consent flow and sends it when creating a client', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'consenting-client', 'Consenting Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://consenting.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await continueToThirdStep(page);

  // Off by default: a client without a consent flow never prompts, so nothing the screen would
  // render is asked for either.
  await expect(page.getByLabel('Reuse the grant for')).toHaveCount(0);
  await expect(page.getByLabel('Logo URI')).toHaveCount(0);

  await page.getByRole('button', { name: 'Ask for consent', exact: true }).click();
  await checkRow(page, 'Let the user grant part of it').click();
  await page.getByLabel('Reuse the grant for').selectOption('30');

  await page.getByLabel('Logo URI').fill('https://consenting.example/logo.png');
  await page.getByLabel('Privacy policy URI').fill('https://consenting.example/privacy');
  await page.getByLabel('Terms of service URI').fill('https://consenting.example/terms');

  await continueToReview(page);
  await submitCreate(page);

  const body = (await findClientWrite(api.requests, 'POST')).body;
  expect(body).toMatchObject({
    logoUri: 'https://consenting.example/logo.png',
    policyUri: 'https://consenting.example/privacy',
    tosUri: 'https://consenting.example/terms',
  });
  expect(body.consentFlow).toEqual({ allowPartial: true, rememberDuration: 30 * 86400 });
});

test('reads and updates a finite consent duration as seconds', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: {
        'tenant-alpha': [{
          ...alphaClient,
          consentFlow: { allowPartial: true, rememberDuration: 14 * 86400 },
        }],
      },
    },
  });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'Registration and consent');
  await expect(page.getByLabel('Remember', { exact: true })).toHaveValue('days');
  await expect(page.getByLabel('Remember duration in days')).toHaveValue('14');

  await page.getByLabel('Remember duration in days').fill('30');
  await saveEdit(page);

  expect((await findClientWrite(api.requests, 'PUT')).body.consentFlow)
    .toEqual({ allowPartial: true, rememberDuration: 30 * 86400 });
});

test('explains consent settings with info buttons', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'Registration and consent');

  await page.getByRole('button', { name: 'Consent settings info' }).click();
  await expect(page.getByText('Shows the user which scopes the client is requesting before an authorization code is issued.', { exact: true })).toBeVisible();

  const consentRow = page.getByText('Consent', { exact: true }).locator('..');
  await consentRow.locator('label.toggle').click();

  const explanations = [
    ['Partial grants consent info', 'Allows the user to deselect optional scopes and approve only a subset of the requested access.'],
    ['Remember consent info', 'Controls how long a previously approved grant can be reused without showing the consent screen again. A grant is always re-requested if the requested scopes grow.'],
    ['Consent logo URI info', 'An image URL displayed on the consent screen next to the client name.'],
    ['Privacy policy consent info', 'A link to the privacy policy that the user can open from the consent screen.'],
    ['Terms of service consent info', 'A link to the terms of service that the user can open from the consent screen.'],
  ] as const;

  for (const [buttonName, explanation] of explanations) {
    await page.getByRole('button', { name: buttonName }).click();
    await expect(page.getByText(explanation, { exact: true })).toBeVisible();
  }
});

test('explains which client fields are shown on the consent screen', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  // The client name is identity, shown above the collapsed groups; scopes are inside one.
  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'General');
  await page.getByRole('button', { name: 'Consent display info', exact: true }).click();
  await expect(page.getByText('Shown to the user on the consent screen. Each locale can have its own name.', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await openEditSection(page, 'Scopes and permissions');
  await page.getByRole('button', { name: 'OAuth scopes consent info', exact: true }).click();
  await expect(page.getByText('Scope descriptions and their claim descriptions are shown to the user before the authorization code is issued.', { exact: true })).toBeVisible();
});

test('uses sentence case for consent property labels', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'Registration and consent');
  const consentRow = page.getByText('Consent', { exact: true }).locator('..');
  await consentRow.locator('label.toggle').click();

  for (const id of ['consent-remember', 'consent-logo-uri', 'consent-policy-uri', 'consent-tos-uri']) {
    const label = page.locator(`label[for="${id}"]`);
    await expect(label).toHaveCSS('text-transform', 'none');
    await expect(label).toHaveCSS('letter-spacing', 'normal');
  }
});

test('hides consent settings when the auth flow is disabled', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { tenants: standardTenants, clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'consenting-web', 'Consenting Web');
  await continueToThirdStep(page);
  await expect(page.getByText('Consent', { exact: true })).toBeVisible();

  // Consent is an act by a user, so the kind with none never offers it.
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: /Service app/ }).click();
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
  await continueToThirdStep(page);

  await expect(page.getByText('Consent', { exact: true })).toHaveCount(0);
});

test('hides registration settings when inline password is enabled', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  // Inline password is a sign-in refinement; turning it on leaves sign-up nothing to verify.
  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'Registration and consent');

  const registrationRow = page.getByText('Registration', { exact: true }).locator('..');
  await registrationRow.locator('label.toggle').click();
  await expect(page.getByText('Assigned roles *', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();

  await openEditSection(page, 'How users sign in');
  await page.getByRole('checkbox', { name: 'inline password', exact: true }).check();
  await saveEdit(page);

  await openEditSection(page, 'Registration and consent');
  await expect(page.getByText('Registration', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();

  // Patch semantics: alpha-web had no registration flow before either, so nothing changed and
  // the key is omitted rather than sent as an explicit null.
  const putBody = (await findClientWrite(api.requests, 'PUT')).body;
  expect(putBody).toMatchObject({
    authFlow: {
      primary: {
        inlinePassword: true,
      },
    },
  });
  expect(putBody.registrationFlow).toBeUndefined();
});

test('hides registration settings for a login+password flow', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'login-password-client', 'Login Password Client');
  await addRedirectUri(page, 'https://login-password.example/callback');
  await continueToThirdStep(page);

  await checkRow(page, 'Let users sign themselves up').click();

  // A login is not a delivery address, so there is nothing for sign-up to prove ownership of.
  await page.getByRole('button', { name: 'Login + password', exact: true }).click();
  await expect(page.getByText('Users cannot sign themselves up', { exact: true })).toBeVisible();
  await expect(checkRow(page, 'Let users sign themselves up')).toHaveCount(0);

  await continueToReview(page);
  await submitCreate(page);

  expect((await findClientWrite(api.requests, 'POST')).body).toMatchObject({
    registrationFlow: null,
  });
});

test('leaves out everything a sign-in flow owns when the client has none', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      tenants: standardTenants,
      clients: { 'tenant-alpha': [alphaClient] },
      scopes: { 'tenant-alpha': [{ scope: 'openid', description: { en: 'OpenID scope' }, claims: [] }] },
    },
  });

  // A service app is the kind with no user, so nothing about signing one in is asked for.
  // (A standard tenant, where its secret default needs no key set to continue.)
  await startCreate(page, 'Service app');
  await fillBasics(page, 'no-auth-flow-client', 'No Auth Flow Client');
  await expect(page.getByText('Redirect URIs', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Logout Settings', { exact: true })).toHaveCount(0);

  await continueToThirdStep(page);
  await expect(page.getByText('First screen', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'openid', exact: true })).toHaveCount(0);
  await expect(page.getByText('No OIDC scopes', { exact: true })).toBeVisible();

  await continueToReview(page);
  await submitCreate(page);

  // The service endpoint has no member for any of it, so none is sent.
  const sent = (await findClientWrite(api.requests, 'POST')).body as Record<string, unknown>;
  for (const owned of ['authFlow', 'registrationFlow', 'theme', 'redirectUris', 'otpTemplateId', 'frontChannelLogoutUri',
    'frontChannelLogoutSessionRequired', 'backChannelLogoutUri', 'logoUri', 'policyUri', 'tosUri', 'consentFlow']) {
    expect(sent).not.toHaveProperty(owned);
  }
});

test('shows client form validation before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  const clientIdField = page.getByLabel('Client ID');
  await clientIdField.fill('Bad-client');
  await page.getByLabel('Client Name').fill('Broken Client');
  await expect(clientIdField).toHaveClass(/input-error/);
  await expect(clientIdField).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await expect(page.getByRole('button', { name: 'Continue to sign-in', exact: true })).toBeDisabled();

  expect(clientWrites(api.requests, 'POST').length > 0).toBeFalsy();

  await page.getByLabel('Client ID').fill('good-client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://broken.example/callback#fragment');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await expect(page.getByText('URI must not contain fragment (#)', { exact: true })).toBeVisible();
});

test('rejects logout notification URIs with a non-http(s) scheme', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'good-client', 'Good Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://good.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');

  const continueButton = page.getByRole('button', { name: 'Continue to sign-in', exact: true });

  await openLogout(page);
  await page.getByRole('button', { name: 'Front-channel', exact: true }).click();
  await page.getByPlaceholder('https://app.example.com/logout/frontchannel').fill('javascript:alert(1)');
  await expect(page.getByText('Logout URI must use https://', { exact: true })).toBeVisible();
  await expect(continueButton).toBeDisabled();

  await page.getByRole('button', { name: 'Back-channel', exact: true }).click();
  await page.getByPlaceholder('https://app.example.com/logout/backchannel').fill('com.example.app://logout');
  await expect(page.getByText('Logout URI must use https://', { exact: true })).toBeVisible();
  await expect(continueButton).toBeDisabled();

  expect(clientWrites(api.requests, 'POST').length > 0).toBeFalsy();
});

test('preserves the front-channel URI when switching logout modes', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await openLogout(page);
  await page.getByRole('button', { name: 'Front-channel', exact: true }).click();
  const frontUri = page.getByPlaceholder('https://app.example.com/logout/frontchannel');
  await frontUri.fill('https://good.example/logout/frontchannel');

  await page.getByRole('button', { name: 'Back-channel', exact: true }).click();
  await page.getByRole('button', { name: 'Front-channel', exact: true }).click();
  await expect(frontUri).toHaveValue('https://good.example/logout/frontchannel');
});

test('shows redirect URI validation with a red input border', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  const redirectUriField = page.getByPlaceholder('https://app.example.com/callback');
  await redirectUriField.fill('not-a-uri');

  await expect(redirectUriField).toHaveClass(/input-error/);
  await expect(redirectUriField).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
});

const patchState = {
  clients: { 'tenant-alpha': [alphaClient, serviceClient] },
  scopes: { 'tenant-alpha': [{ scope: 'openid', description: { en: 'OpenID scope' }, claims: [] }, { scope: 'email', description: { en: 'Email scope' }, claims: [] }] },
  permissions: {
    'tenant-alpha': [
      { permission: 'alpha.read', description: { en: 'Read alpha resources' }, endpointIds: [101] },
      { permission: 'alpha.write', description: { en: 'Write alpha resources' }, endpointIds: [101] },
    ],
  },
};

// Each group of settings is saved on its own, and sends only what that group changed.

test('sends the name and redirect URIs as a patch when General is saved', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: patchState });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'General');
  await page.getByLabel('Client Name').fill('Alpha Console');
  await page.getByRole('button', { name: 'Remove redirect URI https://alpha.example/callback', exact: true }).click();
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://alpha.example/admin/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await saveEdit(page);

  const body = (await findClientWrite(api.requests, 'PUT')).body;
  // The client is a web client, so its update goes to the web endpoint; its kind never changes.
  expect((await findClientWrite(api.requests, 'PUT')).pathname).toBe('/configuration/clients/web');
  expect(body).toMatchObject({
    clientId: 'alpha-web',
    clientName: { en: 'Alpha Console' },
    redirectUris: { add: ['https://alpha.example/admin/callback'], remove: ['https://alpha.example/callback'] },
  });
  expect(body).not.toHaveProperty('accessTokenTtl');
  expect(body.scope ?? { add: [], remove: [] }).toEqual({ add: [], remove: [] });
  await expect(page.locator('versola-clients-list .view-title')).toHaveText('Alpha Console');
});

test('sends scopes and permissions as a patch when they are saved', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: patchState });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'Scopes and permissions');
  await page.locator('.checkbox-item', { hasText: 'openid' }).getByRole('checkbox').uncheck();
  await page.locator('.checkbox-item', { hasText: 'email' }).getByRole('checkbox').check();
  await page.locator('.checkbox-item', { hasText: 'alpha.read' }).getByRole('checkbox').uncheck();
  await page.locator('.checkbox-item', { hasText: 'alpha.write' }).getByRole('checkbox').check();
  await saveEdit(page);

  expect((await findClientWrite(api.requests, 'PUT')).body).toMatchObject({
    clientId: 'alpha-web',
    scope: { add: ['email'], remove: ['openid'] },
    permissions: { add: ['alpha.write'], remove: ['alpha.read'] },
  });
});

test('sends the access token lifetime when Tokens is saved, and nothing else about the client', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: patchState });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'Token lifetimes');
  await page.getByRole('spinbutton', { name: 'Access Token TTL *' }).fill('2');
  await saveEdit(page);

  const body = (await findClientWrite(api.requests, 'PUT')).body;
  expect(body).toMatchObject({ clientId: 'alpha-web', accessTokenTtl: 7200 });
  expect(body).not.toHaveProperty('clientName');
  expect(body.redirectUris ?? { add: [], remove: [] }).toEqual({ add: [], remove: [] });
  expect(body.scope ?? { add: [], remove: [] }).toEqual({ add: [], remove: [] });
});

test('goes back to the client page without saving when a section is cancelled', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: patchState });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'Token lifetimes');
  await page.getByRole('spinbutton', { name: 'Access Token TTL *' }).fill('2');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();

  await expect(page.locator('versola-clients-list .view-title')).toBeVisible();
  expect(api.requests.filter(request => request.method === 'PUT')).toHaveLength(0);
});

test('does not patch a localized client name after reverting an edit', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'General');
  const clientName = page.getByLabel('Client Name');
  await clientName.fill('Temporary name');
  await clientName.fill('Alpha Web');
  await saveEdit(page);

  expect((await findClientWrite(api.requests, 'PUT')).body).not.toHaveProperty('clientName');
});

test('does not let an existing client switch its sign-in flow, which would change what kind of client it is', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaClient] },
      scopes: { 'tenant-alpha': [{ scope: 'openid', description: { en: 'OpenID scope' }, claims: [] }] },
    },
  });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'How users sign in');

  await expect(page.getByText('Authorization Flow', { exact: true })).toHaveCount(0);
});

test('does not carry a stale logout error into a kind that has no logout block', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { tenants: standardTenants } });

  await startCreate(page);
  await fillBasics(page, 'stale-logout-client', 'Stale Logout Client');
  await addRedirectUri(page, 'https://app.example.com/callback');
  await openLogout(page);
  await page.getByRole('button', { name: 'Front-channel', exact: true }).click();
  await page.getByLabel('Front-channel logout URI').fill('not-a-url');

  // Sanity check: as a web client, the invalid URI does gate this step.
  await expect(page.getByRole('button', { name: 'Continue to sign-in' })).toBeDisabled();

  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: /Service app/ }).click();
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();

  // A service client has no sign-in flow, so its logout block never renders - the stale error
  // from the earlier kind must not keep gating a step with no field left to fix it on.
  await expect(page.getByRole('button', { name: 'Continue to permissions' })).toBeEnabled();
});

test('shows a public native client as having no credential, with nothing to register', async ({ page }) => {
  const nativeClient = { ...alphaClient, id: 'alpha-native', clientName: { en: 'Alpha Native' }, clientType: 'native' as const, authMethod: 'none' as const };
  await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [nativeClient] },
      scopes: { 'tenant-alpha': [{ scope: 'openid', description: { en: 'OpenID scope' }, claims: [] }] },
    },
  });

  await openClientPage(page, 'alpha-native');

  // authMethodFor pins a native client to 'none', so a credential an update would drop is never
  // offered: the page states what the client has and offers no way to pick another.
  await expect(page.getByRole('region', { name: 'Type' })).toContainText('None (public client)');
  await expect(page.getByRole('button', { name: 'mTLS certificate', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'private_key_jwt', exact: true })).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Keys' })).toHaveCount(0);
});

test('gates the sign-in step on the consent URIs it edits', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'consent-uri-client', 'Consent URI Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://consenting.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await continueToThirdStep(page);

  await page.getByRole('button', { name: 'Ask for consent', exact: true }).click();
  await page.getByLabel('Logo URI').fill('http://logo.example/logo.png');

  // The field lives on this step, so this step is where it has to be answered: submitting
  // from the review step returns early on it and renders the error under no visible field.
  await expect(page.getByRole('button', { name: 'Continue to review' })).toBeDisabled();

  await page.getByLabel('Logo URI').fill('https://logo.example/logo.png');
  await expect(page.getByRole('button', { name: 'Continue to review' })).toBeEnabled();
});

test('rotates a client secret and deletes the previous secret', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath });

  // Click Edit on the client
  await openClientPage(page, 'alpha-web');

  // Click Rotate secret button
  await page.getByRole('button', { name: 'Rotate secret', exact: true }).click();

  // The old secret stays valid until it is deleted, so Rotate gives way to Delete old secret.
  await expect(page.getByRole('button', { name: 'Rotate secret', exact: true })).not.toBeVisible();

  // Verify the rotate secret API was called correctly
  expect((await findRequest(api.requests, 'POST', '/configuration/clients/rotate-secret')).searchParams).toEqual({
    clientId: 'alpha-web',
  });

  // The client card should show "Secret Rotation" badge
  await expect(page.locator('versola-clients-list .view-meta').getByText('Secret rotation')).toBeVisible();

  // Delete the old secret from the same page
  await page.getByRole('button', { name: 'Delete old secret', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  // Wait for the API call to complete
  await page.waitForTimeout(500);

  // Verify the delete previous secret API was called correctly
  expect((await findRequest(api.requests, 'DELETE', '/configuration/clients/previous-secret')).searchParams).toEqual({
    clientId: 'alpha-web',
  });

  // After deleting the old secret, the "Secret Rotation" indicator should be gone
  await expect(page.locator('versola-clients-list .view-meta').getByText('Secret rotation')).toHaveCount(0);
});

test('deletes a client through the confirm dialog and reaches the empty state', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await page.getByRole('button', { name: 'Actions for alpha-web' }).click();
  await page.getByRole('menuitem', { name: 'Delete client alpha-web' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect((await findRequest(api.requests, 'DELETE', '/configuration/clients')).searchParams).toEqual({
    clientId: 'alpha-web',
  });
  await expect(page.locator('versola-clients-list tbody tr')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No OAuth clients yet', exact: true })).toBeVisible();
});

test('shows error alert when creating a client with duplicate ID', async ({ page }) => {
  let dialogShown = false;

  page.on('dialog', async dialog => {
    expect(dialog.type()).toBe('alert');
    expect(dialog.message()).toBe('Client ID "alpha-web" already exists. Please choose a different ID.');
    dialogShown = true;
    await dialog.accept();
  });

  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'alpha-web', 'Duplicate Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://duplicate.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await finishCreate(page);

  // The dialog is raised once the registration is answered, which a slow runner takes longer over
  // than any fixed pause.
  await expect.poll(() => dialogShown).toBe(true);

  expect((await findClientWrite(api.requests, 'POST')).body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'alpha-web',
    clientName: { en: 'Duplicate Client' },
    redirectUris: ['https://duplicate.example/callback'],
    allowedScopes: ['openid'],
    permissions: [],
    accessTokenTtl: 3600,
    authFlow: {
      primary: {
        credentials: ['phone'],
        inlinePassword: false,
        factors: [{ type: 'otp', required: true }],
      },
      passkey: null,
      equivalents: {},
      otpType: 'sms',
    },
    registrationFlow: null,
    otpTemplateId: 'default',
    theme: 'default',
    frontChannelLogoutUri: null,
    frontChannelLogoutSessionRequired: false,
    backChannelLogoutUri: null,
    logoUri: null,
    policyUri: null,
    tosUri: null,
    consentFlow: null,
    authMethod: 'tls_client_auth',
    dpopBoundAccessTokens: false,
    dpopSigningAlgs: [],
    dpopMinRsaKeySize: null,
    mtlsAuth: null,
    certificateBoundAccessTokens: true,
    jwks: null,
    requireSignedRequestObject: false,
    requirePushedAuthorizationRequests: true,
    issueEdgeClientCertificate: true,
    enrollEdgeClientCertificate: false,
  });

  // The client should NOT be added to the list
  await expect(page.locator('versola-clients-list tbody tr').filter({ hasText: 'Duplicate Client' })).toHaveCount(0);
});

test('says what kind a client is, from the template it was registered from', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [edgeNativeClient] } },
  });

  await openClientPage(page, 'edge-mobile');

  await expect(page.locator('versola-clients-list .view-meta').getByText('Mobile or desktop app', { exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Type' })).toContainText('Fixed');
  await expect(page.getByText(/differ from the template/)).toHaveCount(0);
});

test('flags settings stored below what the client type requires, and moves them only upwards', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [{ ...edgeNativeClient, requirePushedAuthorizationRequests: false }] } },
  });

  await openClientPage(page, 'edge-mobile');
  await expect(page.getByText('Some of these differ from what this type requires.')).toBeVisible();
  await page.getByRole('button', { name: 'Review', exact: true }).click();

  const par = page.locator('versola-client-form .locked-setting', { hasText: 'Pushed authorization requests' });
  await expect(par.getByText('Stored as off, but this client must have it on.')).toBeVisible();
  await par.getByRole('button', { name: 'Set to on' }).click();
  await expect(par.locator('.locked-problem')).toHaveCount(0);

  // The update goes to the device endpoint, which has no member for PAR: central writes what the
  // kind fixes onto every update of it, so saving is what moves the stored value up.
  await saveEdit(page);
  const update = await findClientWrite(api.requests, 'PUT');
  expect(update.pathname).toBe('/configuration/clients/native');
  expect(update.body).toMatchObject({ clientId: 'edge-mobile' });
  expect(update.body).not.toHaveProperty('requirePushedAuthorizationRequests');
});

test('shows a web client on a secret as holding its own credential, not as one fronted by edge', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { tenants: standardTenants, clients: { 'tenant-alpha': [secretBackedHighClient] } },
  });

  await openClientPage(page, 'web-downgraded');

  await expect(page.getByRole('region', { name: 'Type' })).toContainText('Client secret');
  await expect(page.getByText('Some of these differ from what this type requires.')).toHaveCount(0);
});

test('says nothing about a template for a client registered without one', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await openClientPage(page, 'alpha-web');

  await expect(page.getByText(/template/i)).toHaveCount(0);
});

// A client's kind is fixed at creation, and what the kind implies is shown rather than offered.

test('says what an existing client is and that it meets FAPI 2.0, at the top of its page', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'edge-mobile');

  const meta = page.locator('versola-clients-list .view-meta');
  await expect(meta.getByText('Mobile or desktop app', { exact: true })).toBeVisible();
  await expect(meta.getByText('FAPI 2.0', { exact: true })).toBeVisible();
  await expect(page.getByText('Meets FAPI 2.0', { exact: false })).toBeVisible();
});

test('shows a mobile app\'s DPoP and PAR as fixed by its type, with nothing to switch', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'edge-mobile');

  const binding = page.getByRole('region', { name: 'Token binding and requests' });
  await expect(binding).toContainText('Bound to a DPoP key');
  await expect(binding).toContainText('Pushed through edge (PAR)');
  await expect(binding.getByText('By type')).toHaveCount(3);
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Edit token binding and requests', exact: true })).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Type' })).toContainText('Fixed');
});

test('tells a FAPI 2.0 client from a standard one on a standard tenant, in the list and on its page', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { tenants: standardTenants, clients: { 'tenant-alpha': [edgeNativeClient, secretBackedHighClient] } },
  });

  await expect(clientCard(page, 'Edge Mobile')).toContainText('FAPI 2.0');
  await expect(clientCard(page, 'Web Downgraded')).toContainText('Standard');

  await openClientPage(page, 'web-downgraded');
  await expect(page.getByText('Standard profile client, not FAPI 2.0')).toBeVisible();
});

// Refresh tokens are issued to a client with the offline_access scope and to no other.

test('says a client without offline_access is issued no refresh token, and offers no lifetime for one', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [{ ...alphaClient, scope: ['openid'] }] } },
  });

  await openClientPage(page, 'alpha-web');
  await expect(page.getByRole('region', { name: 'Tokens' })).toContainText('Not issued');

  await openEditSection(page, 'Token lifetimes');
  await expect(page.getByLabel('Refresh Token TTL (days) *')).toHaveCount(0);
  await expect(page.locator('versola-client-form .locked-setting', { hasText: 'Refresh token' })).toContainText('offline_access');
});

test('lets the refresh token lifetime be changed once offline_access is in scope', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [offlineClient] } } });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'Token lifetimes');
  await expect(page.getByLabel('Refresh Token TTL (days) *')).toBeEnabled();
  await expect(page.locator('versola-client-form .locked-setting', { hasText: 'Refresh token' })).toHaveCount(0);
});

test('offers PAR and JAR to a web client that authenticates for itself, since its type leaves them open', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { tenants: standardTenants, clients: { 'tenant-alpha': [secretBackedHighClient] } },
  });

  await openClientPage(page, 'web-downgraded');
  await expect(page.getByRole('region', { name: 'Token binding and requests' })).not.toContainText('By type');
  await page.getByRole('button', { name: 'Edit token binding and requests', exact: true }).click();
  await page.getByRole('checkbox', { name: /Pushed Authorization Requests/ }).uncheck();
  await saveEdit(page);

  expect((await findClientWrite(api.requests, 'PUT')).body).toMatchObject({
    clientId: 'web-downgraded',
    requirePushedAuthorizationRequests: false,
  });
});

test('keeps the info button above a field clear of the field', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [alphaClient] } } });

  await openClientPage(page, 'alpha-web');
  await openEditSection(page, 'Token lifetimes');

  const info = await page.getByRole('button', { name: 'Access token TTL info' }).boundingBox();
  const field = await page.locator('versola-client-form #ttl').boundingBox();
  expect(info!.height).toBeGreaterThanOrEqual(24);
  expect(field!.y).toBeGreaterThanOrEqual(info!.y + info!.height);
});

for (const width of [375, 320]) {
  test(`keeps every step of creating a client inside the page and its targets usable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [] } } });

    const measure = () => page.evaluate(() => {
      const small: string[] = [];
      const visit = (root: ParentNode) => {
        for (const element of Array.from(root.querySelectorAll('*'))) {
          if (element.shadowRoot) visit(element.shadowRoot);
          const interactive = element.matches('button, a[href], input:not([type="hidden"]), select, textarea, [role="button"]');
          if (!interactive) continue;
          const box = element.getBoundingClientRect();
          if (box.width === 0 || box.height === 0) continue;
          // WCAG 2.5.8: a target of at least 24x24 CSS px, unless it is an inline link.
          if (box.width < 24 || box.height < 24) small.push(`${element.tagName.toLowerCase()} ${Math.round(box.width)}x${Math.round(box.height)} ${(element.textContent || '').trim().slice(0, 24)}`);
        }
      };
      visit(document);
      return { overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth, small };
    });

    await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
    await page.getByRole('button', { name: /Web app/ }).click();
    expect((await measure()).overflow).toBeLessThanOrEqual(0);
    await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
    await fillBasics(page, 'narrow-client', 'Narrow Client');
    await addRedirectUri(page, 'https://narrow.example/callback');
    const basics = await measure();
    expect(basics.overflow).toBeLessThanOrEqual(0);
    expect(basics.small).toEqual([]);
    await continueToThirdStep(page);
    const signIn = await measure();
    expect(signIn.overflow).toBeLessThanOrEqual(0);
    expect(signIn.small).toEqual([]);
    await continueToReview(page);
    const review = await measure();
    expect(review.overflow).toBeLessThanOrEqual(0);
    expect(review.small).toEqual([]);
  });
}
