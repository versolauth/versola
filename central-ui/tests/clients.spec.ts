import { expect, test, type Page } from '@playwright/test';
import { INVALID_FIELD_BORDER, findRequest, loadAdminApp } from './fixtures';

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
  return page.locator('.client-card').filter({ hasText: text }).first();
}

/**
 * Creation opens on step 1, which decides the client's credential and request-integrity
 * settings. Web x Compatibility is the combination these tests configure by hand afterwards:
 * a secret, no PAR, no signed request objects.
 */
async function startCreate(page: Page, kind = 'Web app', tier = 'Compatibility') {
  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: new RegExp(kind) }).click();
  await page.getByRole('button', { name: tier, exact: true }).click();
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
}

/** Step 2 gates on these two, so every creation has to fill them before it can go on. */
async function fillBasics(page: Page, id: string, name: string) {
  await page.getByLabel('Client ID').fill(id);
  await page.getByLabel('Client Name').fill(name);
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

  const alpha = clientCard(page, 'Alpha Web');
  await alpha.locator('.client-header').click();
  await expect(alpha).toContainText('https://alpha.example/callback');
  await expect(alpha).toContainText('openid');
  await expect(alpha).toContainText('alpha.read');
  await expect(alpha).toContainText('1h');
  await expect(alpha.locator('.flow-section')).toBeVisible();
  await expect(alpha.locator('.auth-flow-settings')).toContainText('Forms Theme');
  await expect(alpha.locator('.auth-flow-settings')).toContainText('Redirect URIs');
  await expect(alpha.locator('.auth-flow-settings')).toContainText('OTP Settings');
  await expect(alpha.locator('.auth-flow-settings')).toContainText('Logout Settings');

  const search = page.getByLabel('Search clients');
  await search.fill('service-client');
  await expect(clientCard(page, 'Service Client')).toBeVisible();
  await expect(page.locator('.client-card').filter({ hasText: 'alpha-web' })).toHaveCount(0);

  await search.fill('missing-client');
  await expect(page.getByRole('heading', { name: 'No clients match your search', exact: true })).toBeVisible();
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

  const client = clientCard(page, 'Alpha Web');
  await client.locator('.client-header').click();
  await expect(client).toContainText('Refresh Token TTL');
  await expect(client).toContainText('180d');

  await client.getByRole('button', { name: 'Edit client alpha-web' }).click();
  await expect(page.getByLabel('Refresh Token TTL (days) *')).toHaveValue('180');
  await page.getByLabel('Refresh Token TTL (days) *').fill('120');
  await page.getByRole('button', { name: 'Update Client', exact: true }).click();

  expect(findRequest(api.requests, 'PUT', '/configuration/clients').body).toMatchObject({
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
  await expect(page.getByLabel('Refresh Token TTL (days) *')).toHaveCount(0);
  await page.getByRole('checkbox', { name: 'offline_access', exact: true }).check();
  await expect(page.getByLabel('Refresh Token TTL (days) *')).toHaveValue('90');
  await page.getByLabel('Refresh Token TTL (days) *').fill('45');
  await continueToReview(page);
  await submitCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    refreshTokenTtl: 45 * 24 * 60 * 60,
  });
  await expect(clientCard(page, 'Offline Client')).toContainText('45d');
});

test('creates a client and shows the generated secret banner', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient, serviceClient] } },
  });

  await startCreate(page);

  await fillBasics(page, 'dashboard-client', 'Dashboard Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://dashboard.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.getByRole('spinbutton', { name: 'Access Token TTL *' }).fill('30');
  await page.locator('versola-client-form select.ttl-unit-select').selectOption('minutes');
  await continueToThirdStep(page);
  await page.getByRole('checkbox', { name: 'openid', exact: true }).check();
  await page.getByRole('checkbox', { name: 'alpha.read', exact: true }).check();
  await continueToReview(page);
  await submitCreate(page);

  const created = clientCard(page, 'Dashboard Client');
  const secretBanner = page.locator('.secret-banner').first();
  const secretValue = secretBanner.locator('.secret-value');
  await expect(page.getByRole('heading', { name: 'Client created: Dashboard Client', exact: true })).toBeVisible();
  await expect(secretBanner.getByRole('button', { name: 'Copy secret', exact: true })).toBeVisible();
  await expect(secretValue).toBeVisible();
  expect((await secretValue.textContent())?.trim().length ?? 0).toBeGreaterThan(0);
  await expect(created).toContainText('dashboard-client');
  await expect(created).toContainText('https://dashboard.example/callback');
  await expect(created).toContainText('30m');

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'dashboard-client',
    clientName: { en: 'Dashboard Client' },
    redirectUris: ['https://dashboard.example/callback'],
    allowedScopes: ['openid'],
    permissions: ['alpha.read'],
    accessTokenTtl: 1800,
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
    clientType: 'web',
    dpopBoundAccessTokens: false,
    dpopSigningAlgs: [],
    dpopMinRsaKeySize: null,
    mtlsAuth: null,
    certificateBoundAccessTokens: false,
    jwks: null,
    requireSignedRequestObject: false,
    requirePushedAuthorizationRequests: false,
  });
});

test('creates a native client without a secret and without rotation controls', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page, 'Mobile or desktop app');
  await fillBasics(page, 'mobile-app', 'Mobile App');
  await page.getByPlaceholder('https://app.example.com/callback').fill('com.example.app://callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await finishCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    id: 'mobile-app',
    clientType: 'native',
  });

  // There is no secret to copy, so the banner says so instead of rendering an empty value.
  await expect(page.getByRole('heading', { name: 'Client created: Mobile App', exact: true })).toBeVisible();
  await expect(page.locator('.secret-banner .secret-value')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Copy secret', exact: true })).toHaveCount(0);
  await expect(page.locator('.secret-banner')).toContainText('no secret was issued');

  // The list itself shows the client type, so it's discoverable without opening the edit form.
  await expect(clientCard(page, 'Mobile App').locator('.badge-native')).toHaveText('Native');

  await clientCard(page, 'Mobile App').getByRole('button', { name: 'Edit client mobile-app' }).click();
  await expect(page.getByRole('button', { name: 'Rotate Secret', exact: true })).toHaveCount(0);
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
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(clientCard(page, 'Alpha Web').locator('.badge-web')).toHaveText('Web');
  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Edit client alpha-web' }).click();
  await expect(page.getByText('Client type', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'web', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'native', exact: true })).toBeDisabled();
});

test('registers a client that authenticates with an mTLS certificate', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [] },
      challengeSettings: { 'tenant-alpha': mtlsTerminatingSettings },
    },
  });

  await startCreate(page);
  await fillBasics(page, 'mtls-client', 'mTLS Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://mtls.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.getByRole('button', { name: 'mTLS certificate', exact: true }).click();

  // The tenant terminates TLS, so no warning - and token binding is implied by the credential.
  await expect(page.getByText('no mTLS certificate header configured')).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: /Bind access tokens/ })).toBeDisabled();

  await page.getByLabel('Subject type').selectOption('san_dns');
  await page.getByLabel('Subject value').fill('client.example.com');
  await finishCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    id: 'mtls-client',
    mtlsAuth: { type: 'tls_client_auth', subjectType: 'san_dns', subjectValue: 'client.example.com' },
    certificateBoundAccessTokens: false,
    jwks: null,
  });
});

test('blocks an mTLS client with no subject value, and one whose tenant terminates no TLS', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page);
  await fillBasics(page, 'mtls-client', 'mTLS Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://mtls.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.getByRole('button', { name: 'mTLS certificate', exact: true }).click();

  await expect(page.getByText('no mTLS certificate header configured')).toBeVisible();

  // The basics step will not hand a broken credential on to the steps after it.
  const continueButton = page.getByRole('button', { name: 'Continue to sign-in', exact: true });
  await expect(page.getByText('Subject value is required')).toBeVisible();
  await expect(continueButton).toBeDisabled();

  // Central refuses the registration outright without a header to read the certificate from,
  // so a complete form is no more submittable than an incomplete one.
  await page.getByLabel('Subject value').fill('client.example.com');
  await expect(page.getByText('Subject value is required')).toHaveCount(0);
  await expect(continueButton).toBeDisabled();
  expect(api.requests.filter(request => request.method === 'POST' && request.pathname === '/configuration/clients')).toHaveLength(0);
});

test('registers a self-signed mTLS client and keeps JAR off keys that cannot verify one', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [] },
      challengeSettings: { 'tenant-alpha': mtlsTerminatingSettings },
    },
  });

  await startCreate(page);
  await fillBasics(page, 'self-signed-client', 'Self Signed Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://self-signed.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.getByRole('button', { name: 'mTLS self-signed', exact: true }).click();

  // A certificate is matched over public key material, so a P-384 key registers fine - but
  // nothing here can verify a request object with it.
  await page.getByLabel('JWK Set').fill(JSON.stringify({ keys: [{ kty: 'EC', crv: 'P-384', x: 'x', y: 'y' }] }));
  await expect(page.getByRole('checkbox', { name: /Require signed request objects/ })).toBeDisabled();

  const keySet = { keys: [{ kty: 'EC', crv: 'P-256', x: 'x', y: 'y' }] };
  await page.getByLabel('JWK Set').fill(JSON.stringify(keySet));
  await page.getByRole('checkbox', { name: /Require signed request objects/ }).check();
  await finishCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    id: 'self-signed-client',
    mtlsAuth: { type: 'self_signed_tls_client_auth' },
    jwks: keySet,
    requireSignedRequestObject: true,
  });

  // The backend still generates a secret for a web client, but token authentication refuses
  // it once an mTLS credential is registered, so the banner must not offer it to copy.
  await expect(page.getByRole('heading', { name: 'Client created: Self Signed Client', exact: true })).toBeVisible();
  await expect(page.getByText('authenticates with its certificate')).toBeVisible();
  await expect(page.locator('.secret-banner .secret-value')).toHaveCount(0);
});

test('applies the credential and request integrity a high-assurance web client implies', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page, 'Web app', 'High assurance');

  await expect(page.getByRole('button', { name: 'private_key_jwt', exact: true })).toHaveClass(/selected/);
  await expect(page.getByRole('checkbox', { name: /Require Pushed Authorization Requests/ })).toBeChecked();

  // The preset asks for signed request objects, but they are verified against the client's
  // own keys - the requirement only takes effect once a key set that can verify one is in.
  await expect(page.getByRole('checkbox', { name: /Require signed request objects/ })).toBeDisabled();

  await fillBasics(page, 'high-web', 'High Web');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://high.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.getByLabel('JWK Set').fill(JSON.stringify({ keys: [{ kty: 'EC', crv: 'P-256', x: 'x', y: 'y' }] }));
  await expect(page.getByRole('checkbox', { name: /Require signed request objects/ })).toBeChecked();
  await finishCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    id: 'high-web',
    clientType: 'web',
    requirePushedAuthorizationRequests: true,
    requireSignedRequestObject: true,
    dpopBoundAccessTokens: false,
  });
});

test('binds a high-assurance mobile client to a device key and leaves it public', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page, 'Mobile or desktop app', 'High assurance');

  await expect(page.getByRole('checkbox', { name: /Require DPoP-bound access tokens/ })).toBeChecked();

  await fillBasics(page, 'mobile-client', 'Mobile Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('com.example.app://callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await finishCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    id: 'mobile-client',
    clientType: 'native',
    dpopBoundAccessTokens: true,
    requirePushedAuthorizationRequests: true,
    requireSignedRequestObject: false,
  });
});

test('leaves a service client with no sign-in flow to configure', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page, 'Service app', 'High assurance');

  await expect(page.getByPlaceholder('https://app.example.com/callback')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'private_key_jwt', exact: true })).toHaveClass(/selected/);

  await fillBasics(page, 'batch-service', 'Batch Service');
  await page.getByLabel('JWK Set').fill(JSON.stringify({ keys: [{ kty: 'EC', crv: 'P-256', x: 'x', y: 'y' }] }));

  // A service app has no user, so its third step asks what it may call instead of how anyone
  // signs in - and there is no sign-in flow to turn off.
  await continueToThirdStep(page);
  await expect(page.getByText('What it is allowed to call', { exact: true })).toBeVisible();
  await expect(page.getByText('Authorization Flow', { exact: true })).toHaveCount(0);
  await continueToReview(page);
  await submitCreate(page);

  const body = findRequest(api.requests, 'POST', '/configuration/clients').body as Record<string, unknown>;
  expect(body).toMatchObject({ id: 'batch-service', clientType: 'web', authFlow: null });
  expect(body.redirectUris).toEqual([]);
});

test('re-applies the preset when the tier changes before continuing', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [] } } });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: /Web app/ }).click();
  await page.getByRole('button', { name: 'Compatibility', exact: true }).click();
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();

  await expect(page.getByRole('button', { name: 'secret', exact: true })).toHaveClass(/selected/);
  await expect(page.getByRole('checkbox', { name: /Require Pushed Authorization Requests/ })).not.toBeChecked();
});

test('registers a private_key_jwt client with signed request objects and PAR', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [] } },
  });

  await startCreate(page);
  await fillBasics(page, 'assertion-client', 'Assertion Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://assertion.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');

  // JAR has nothing to verify a request object against until a JWK Set is registered.
  await expect(page.getByRole('checkbox', { name: /Require signed request objects/ })).toBeDisabled();

  await page.getByRole('button', { name: 'private_key_jwt', exact: true }).click();
  await page.getByLabel('JWK Set').fill('not json');
  await expect(page.getByText('Must be valid JSON')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue to sign-in', exact: true })).toBeDisabled();
  expect(api.requests.filter(request => request.method === 'POST' && request.pathname === '/configuration/clients')).toHaveLength(0);

  const keySet = { keys: [{ kty: 'EC', crv: 'P-256', x: 'x-coordinate', y: 'y-coordinate' }] };
  await page.getByLabel('JWK Set').fill(JSON.stringify(keySet));
  await page.getByRole('checkbox', { name: /Require signed request objects/ }).check();
  await page.getByRole('checkbox', { name: /Require Pushed Authorization Requests/ }).check();
  await finishCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    id: 'assertion-client',
    mtlsAuth: null,
    jwks: keySet,
    requireSignedRequestObject: true,
    requirePushedAuthorizationRequests: true,
  });
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

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
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

  await startCreate(page);
  await fillBasics(page, 'otp-client', 'OTP Client');
  await continueToThirdStep(page);
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

  const registrationRow = page.getByText('Registration', { exact: true }).locator('..');
  await registrationRow.locator('label.toggle').click();

  await expect(page.locator('[aria-label="Registration credential (locked)"]')).toContainText('phone');
  await expect(page.getByText('New users prove ownership of their phone with an OTP.', { exact: true })).toBeVisible();
  await expect(page.getByText('Granted once, when the account is created.', { exact: true })).toBeVisible();

  const challenge = page.getByLabel('Challenge', { exact: true });
  await expect(challenge).toHaveValue('none');
  await challenge.selectOption('setPassword');

  const roles = page.getByRole('group', { name: 'Assigned roles' });
  await expect(roles.getByRole('checkbox', { name: 'user', exact: true })).toBeChecked();
  await roles.getByRole('checkbox', { name: 'alpha-admin', exact: true }).check();

  await continueToReview(page);
  await submitCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    registrationFlow: {
      credential: 'phone',
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

  // Off by default: a client without a consent flow never prompts.
  await expect(page.getByLabel('Remember', { exact: true })).toHaveCount(0);

  const consentRow = page.getByText('Consent', { exact: true }).locator('..');
  await consentRow.locator('label.toggle').click();

  await page.getByRole('checkbox', { name: 'Let the user deselect optional scopes' }).check();

  const remember = page.getByLabel('Remember', { exact: true });
  await expect(remember).toHaveValue('days');
  await expect(page.getByLabel('Remember duration in days')).toHaveValue('180');
  await page.getByLabel('Remember duration in days').fill('14');

  await page.getByRole('textbox', { name: 'Logo URI', exact: true }).fill('https://consenting.example/logo.png');
  await page.getByRole('textbox', { name: 'Privacy policy', exact: true }).fill('https://consenting.example/privacy');
  await page.getByRole('textbox', { name: 'Terms of service', exact: true }).fill('https://consenting.example/terms');

  await continueToReview(page);
  await submitCreate(page);

  const body = findRequest(api.requests, 'POST', '/configuration/clients').body;
  expect(body).toMatchObject({
    logoUri: 'https://consenting.example/logo.png',
    policyUri: 'https://consenting.example/privacy',
    tosUri: 'https://consenting.example/terms',
  });
  expect(body.consentFlow).toEqual({ allowPartial: true, rememberDuration: 14 * 86400 });
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

  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Edit client alpha-web' }).click();
  await expect(page.getByLabel('Remember', { exact: true })).toHaveValue('days');
  await expect(page.getByLabel('Remember duration in days')).toHaveValue('14');

  await page.getByLabel('Remember duration in days').fill('30');
  await page.getByRole('button', { name: 'Update Client', exact: true }).click();

  expect(findRequest(api.requests, 'PUT', '/configuration/clients').body.consentFlow)
    .toEqual({ allowPartial: true, rememberDuration: 30 * 86400 });
});

test('explains consent settings with info buttons', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'consent-info-client', 'Consent Info Client');
  await continueToThirdStep(page);

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

  await startCreate(page);
  await page.getByRole('button', { name: 'Consent display info', exact: true }).click();
  await expect(page.getByText('Shown to the user on the consent screen. Each locale can have its own name.', { exact: true })).toBeVisible();

  await fillBasics(page, 'consent-fields-client', 'Consent Fields Client');
  await continueToThirdStep(page);
  await page.getByRole('button', { name: 'OAuth scopes consent info', exact: true }).click();
  await expect(page.getByText('Scope descriptions and their claim descriptions are shown to the user before the authorization code is issued.', { exact: true })).toBeVisible();
});

test('uses sentence case for consent property labels', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'consent-case-client', 'Consent Case Client');
  await continueToThirdStep(page);
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
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'consenting-web', 'Consenting Web');
  await continueToThirdStep(page);
  await expect(page.getByText('Consent', { exact: true })).toBeVisible();

  // Consent is an act by a user, so the kind with none never offers it.
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await startCreate(page, 'Service app');
  await fillBasics(page, 'consentless-service', 'Consentless Service');
  await continueToThirdStep(page);

  await expect(page.getByText('Consent', { exact: true })).toHaveCount(0);
});

test('hides registration settings when inline password is enabled', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'inline-password-client', 'Inline Password Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://inline-password.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await continueToThirdStep(page);

  const registrationRow = page.getByText('Registration', { exact: true }).locator('..');
  await registrationRow.locator('label.toggle').click();
  await expect(page.getByText('Assigned roles *', { exact: true })).toBeVisible();

  await page.getByRole('checkbox', { name: 'inline password', exact: true }).check();
  await expect(page.getByText('Registration', { exact: true })).toHaveCount(0);

  await continueToReview(page);
  await submitCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    authFlow: {
      primary: {
        inlinePassword: true,
      },
    },
    registrationFlow: null,
  });
});

test('hides registration settings for a login+password flow', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await fillBasics(page, 'login-password-client', 'Login Password Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://login-password.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await continueToThirdStep(page);

  const registrationRow = page.getByText('Registration', { exact: true }).locator('..');
  await registrationRow.locator('label.toggle').click();
  await expect(page.getByText('Assigned roles *', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'login + password', exact: true }).click();
  await expect(page.getByText('Registration', { exact: true })).toHaveCount(0);

  await continueToReview(page);
  await submitCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    registrationFlow: null,
  });
});

test('leaves out everything a sign-in flow owns when the client has none', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaClient] },
      scopes: { 'tenant-alpha': [{ scope: 'openid', description: { en: 'OpenID scope' }, claims: [] }] },
    },
  });

  // A service app is the kind with no user, so nothing about signing one in is asked for.
  await startCreate(page, 'Service app');
  await fillBasics(page, 'no-auth-flow-client', 'No Auth Flow Client');
  await expect(page.getByText('Redirect URIs', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Logout Settings', { exact: true })).toHaveCount(0);

  await continueToThirdStep(page);
  await expect(page.getByText('OTP Settings', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: 'openid', exact: true })).toHaveCount(0);
  await expect(page.getByLabel('Forms Theme', { exact: true })).toHaveCount(0);

  await continueToReview(page);
  await submitCreate(page);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    authFlow: null,
    theme: 'default',
    redirectUris: [],
    otpTemplateId: 'default',
    frontChannelLogoutUri: null,
    frontChannelLogoutSessionRequired: false,
    backChannelLogoutUri: null,
    logoUri: null,
    policyUri: null,
    tosUri: null,
    consentFlow: null,
  });
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

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/clients')).toBeFalsy();

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

  await page.getByRole('button', { name: 'front-channel', exact: true }).click();
  await page.getByPlaceholder('https://app.example.com/logout/frontchannel').fill('javascript:alert(1)');
  await expect(page.getByText('Logout URI must use https://', { exact: true })).toBeVisible();
  await expect(continueButton).toBeDisabled();

  await page.getByRole('button', { name: 'back-channel', exact: true }).click();
  await page.getByPlaceholder('https://app.example.com/logout/backchannel').fill('com.example.app://logout');
  await expect(page.getByText('Logout URI must use https://', { exact: true })).toBeVisible();
  await expect(continueButton).toBeDisabled();

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/clients')).toBeFalsy();
});

test('preserves the front-channel URI when switching logout modes', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await startCreate(page);
  await page.getByRole('button', { name: 'front-channel', exact: true }).click();
  const frontUri = page.getByPlaceholder('https://app.example.com/logout/frontchannel');
  await frontUri.fill('https://good.example/logout/frontchannel');

  await page.getByRole('button', { name: 'back-channel', exact: true }).click();
  await page.getByRole('button', { name: 'front-channel', exact: true }).click();
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

test('updates a client and sends patch-style changes', async ({ page }) => {
  const api = await loadAdminApp(page, {
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

  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Edit client alpha-web' }).click();
  await page.getByLabel('Client Name').fill('Alpha Console');
  await page.getByRole('button', { name: 'Remove redirect URI https://alpha.example/callback', exact: true }).click();
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://alpha.example/admin/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.locator('.checkbox-item', { hasText: 'openid' }).getByRole('checkbox').uncheck();
  await page.locator('.checkbox-item', { hasText: 'email' }).getByRole('checkbox').check();
  await page.locator('.checkbox-item', { hasText: 'alpha.read' }).getByRole('checkbox').uncheck();
  await page.locator('.checkbox-item', { hasText: 'alpha.write' }).getByRole('checkbox').check();
  await page.getByRole('spinbutton', { name: 'Access Token TTL *' }).fill('2');

  await page.getByRole('button', { name: 'Update Client', exact: true }).click();

  // Verify the API request was made correctly
  expect(findRequest(api.requests, 'PUT', '/configuration/clients').body).toEqual({
    clientId: 'alpha-web',
    clientName: { en: 'Alpha Console' },
    otpTemplateId: 'default',
    redirectUris: { add: ['https://alpha.example/admin/callback'], remove: ['https://alpha.example/callback'] },
    scope: { add: ['email'], remove: ['openid'] },
    permissions: { add: ['alpha.write'], remove: ['alpha.read'] },
    accessTokenTtl: 7200,
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
    frontChannelLogoutSessionRequired: false,
  });
});

test('does not patch a localized client name after reverting an edit', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Edit client alpha-web' }).click();
  const clientName = page.getByLabel('Client Name');
  await clientName.fill('Temporary name');
  await clientName.fill('Alpha Web');
  await page.getByRole('button', { name: 'Update Client', exact: true }).click();

  expect(findRequest(api.requests, 'PUT', '/configuration/clients').body).not.toHaveProperty('clientName');
});

test('clears the auth flow on an existing client by sending an explicit null', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaClient] },
      scopes: { 'tenant-alpha': [{ scope: 'openid', description: { en: 'OpenID scope' }, claims: [] }] },
    },
  });

  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Edit client alpha-web' }).click();

  const authFlowRow = page.getByText('Authorization Flow', { exact: true }).locator('..');
  await authFlowRow.locator('label.toggle').click();

  await page.getByRole('button', { name: 'Update Client', exact: true }).click();

  // An explicit null is required: an omitted key would leave the stored flow in place.
  expect(findRequest(api.requests, 'PUT', '/configuration/clients').body).toMatchObject({
    clientId: 'alpha-web',
    authFlow: null,
  });
});

test('rotates a client secret and deletes the previous secret', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath });

  // Click Edit on the client
  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Edit client alpha-web' }).click();

  // Click Rotate Secret button
  await page.getByRole('button', { name: 'Rotate Secret', exact: true }).click();

  // Wait for the form to close (it should close after rotation)
  await expect(page.getByRole('button', { name: 'Rotate Secret', exact: true })).not.toBeVisible();

  // Verify the rotate secret API was called correctly
  expect(findRequest(api.requests, 'POST', '/configuration/clients/rotate-secret').searchParams).toEqual({
    clientId: 'alpha-web',
  });

  // The client card should show "Secret Rotation" badge
  await expect(page.getByText('Secret Rotation')).toBeVisible();

  // Edit the client again to delete the old secret
  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Edit client alpha-web' }).click();
  await page.getByRole('button', { name: 'Delete old secret', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  // Wait for the API call to complete
  await page.waitForTimeout(500);

  // Verify the delete previous secret API was called correctly
  expect(findRequest(api.requests, 'DELETE', '/configuration/clients/previous-secret').searchParams).toEqual({
    clientId: 'alpha-web',
  });

  // After deleting the old secret, the "Secret Rotation" indicator should be gone
  await expect(clientCard(page, 'Alpha Web')).not.toContainText('Secret Rotation');
});

test('deletes a client through the confirm dialog and reaches the empty state', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Delete client alpha-web' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect(findRequest(api.requests, 'DELETE', '/configuration/clients').searchParams).toEqual({
    clientId: 'alpha-web',
  });
  await expect(page.locator('.client-card')).toHaveCount(0);
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

  // Wait for the dialog to appear
  await page.waitForTimeout(200);

  expect(dialogShown).toBe(true);

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'alpha-web',
    clientName: { en: 'Duplicate Client' },
    redirectUris: ['https://duplicate.example/callback'],
    allowedScopes: [],
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
    clientType: 'web',
    dpopBoundAccessTokens: false,
    dpopSigningAlgs: [],
    dpopMinRsaKeySize: null,
    mtlsAuth: null,
    certificateBoundAccessTokens: false,
    jwks: null,
    requireSignedRequestObject: false,
    requirePushedAuthorizationRequests: false,
  });

  // The client should NOT be added to the list
  await expect(page.locator('.client-card').filter({ hasText: 'Duplicate Client' })).toHaveCount(0);
});