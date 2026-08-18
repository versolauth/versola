import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const clientsPath = '/?view=clients&tenant=tenant-alpha';

const alphaClient = {
  id: 'alpha-web',
  clientName: { en: 'Alpha Web' },
  redirectUris: ['https://alpha.example/callback'],
  scope: ['openid'],
  permissions: ['alpha.read'],
  secretRotation: false,
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

function clientCard(page: Page, text: string) {
  return page.locator('.client-card').filter({ hasText: text }).first();
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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await expect(page.getByLabel('Refresh Token TTL (days) *')).toHaveCount(0);
  await page.getByLabel('Client ID').fill('offline-client');
  await page.getByLabel('Client Name').fill('Offline Client');
  await page.getByRole('checkbox', { name: 'offline_access', exact: true }).check();
  await expect(page.getByLabel('Refresh Token TTL (days) *')).toHaveValue('90');
  await page.getByLabel('Refresh Token TTL (days) *').fill('45');
  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();

  await page.getByLabel('Client ID').fill('dashboard-client');
  await page.getByLabel('Client Name').fill('Dashboard Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://dashboard.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.getByLabel('Access Token TTL').fill('30');
  await page.locator('versola-client-form select.ttl-unit-select').selectOption('minutes');
  await page.getByRole('checkbox', { name: 'openid', exact: true }).check();
  await page.getByRole('checkbox', { name: 'alpha.read', exact: true }).check();
  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('localized-client');
  const nameEditor = page.locator('versola-client-form versola-localized-text-editor');
  await page.getByLabel('Client Name').fill('Localized Client');
  await page.getByRole('tab', { name: 'ru', exact: true }).click();
  await nameEditor.locator('input').fill('Локализованный клиент');
  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('registering-client');
  await page.getByLabel('Client Name').fill('Registering Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://registering.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');

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

  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('consenting-client');
  await page.getByLabel('Client Name').fill('Consenting Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://consenting.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');

  // Off by default: a client without a consent flow never prompts.
  await expect(page.getByLabel('Remember', { exact: true })).toHaveCount(0);

  const consentRow = page.getByText('Consent', { exact: true }).locator('..');
  await consentRow.locator('label.toggle').click();

  await page.getByRole('checkbox', { name: 'Let the user deselect optional scopes' }).check();

  const remember = page.getByLabel('Remember', { exact: true });
  await expect(remember).toHaveValue('forever');
  await remember.selectOption('days');
  await page.getByLabel('Remember duration in days').fill('14');

  await page.getByRole('textbox', { name: 'Logo URI', exact: true }).fill('https://consenting.example/logo.png');
  await page.getByRole('textbox', { name: 'Privacy policy', exact: true }).fill('https://consenting.example/privacy');
  await page.getByRole('textbox', { name: 'Terms of service', exact: true }).fill('https://consenting.example/terms');

  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

  const body = findRequest(api.requests, 'POST', '/configuration/clients').body;
  expect(body).toMatchObject({
    logoUri: 'https://consenting.example/logo.png',
    policyUri: 'https://consenting.example/privacy',
    tosUri: 'https://consenting.example/terms',
  });
  expect(body.consentFlow).toEqual({ allowPartial: true, rememberDuration: 14 * 86400 });
});

test('explains consent settings with info buttons', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();

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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: 'Consent display info', exact: true }).click();
  await expect(page.getByText('Shown to the user on the consent screen. Each locale can have its own name.', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'OAuth scopes consent info', exact: true }).click();
  await expect(page.getByText('Scope descriptions and their claim descriptions are shown to the user before the authorization code is issued.', { exact: true })).toBeVisible();
});

test('uses sentence case for consent property labels', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await expect(page.getByText('Consent', { exact: true })).toBeVisible();

  const authFlowRow = page.getByText('Authorization Flow', { exact: true }).locator('..');
  await authFlowRow.locator('label.toggle').click();

  await expect(page.getByText('Consent', { exact: true })).toHaveCount(0);
});

test('hides registration settings when inline password is enabled', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('inline-password-client');
  await page.getByLabel('Client Name').fill('Inline Password Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://inline-password.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');

  const registrationRow = page.getByText('Registration', { exact: true }).locator('..');
  await registrationRow.locator('label.toggle').click();
  await expect(page.getByText('Assigned roles *', { exact: true })).toBeVisible();

  await page.getByRole('checkbox', { name: 'inline password', exact: true }).check();
  await expect(page.getByText('Registration', { exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('login-password-client');
  await page.getByLabel('Client Name').fill('Login Password Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://login-password.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');

  const registrationRow = page.getByText('Registration', { exact: true }).locator('..');
  await registrationRow.locator('label.toggle').click();
  await expect(page.getByText('Assigned roles *', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'login + password', exact: true }).click();
  await expect(page.getByText('Registration', { exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

  expect(findRequest(api.requests, 'POST', '/configuration/clients').body).toMatchObject({
    registrationFlow: null,
  });
});

test('hides logout settings and clears logout values when auth flow is disabled', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaClient] },
      scopes: { 'tenant-alpha': [{ scope: 'openid', description: { en: 'OpenID scope' }, claims: [] }] },
    },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('no-auth-flow-client');
  await page.getByLabel('Client Name').fill('No Auth Flow Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://no-auth-flow.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');

  const authFlowRow = page.getByText('Authorization Flow', { exact: true }).locator('..');
  await authFlowRow.locator('label.toggle').click();
  await expect(page.getByText('OTP Settings', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: 'openid', exact: true })).toBeDisabled();
  await expect(page.getByRole('checkbox', { name: 'openid', exact: true })).not.toBeChecked();
  await expect(page.getByLabel('Forms Theme', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Redirect URIs', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Logout Settings', { exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  const clientIdField = page.getByLabel('Client ID');
  await clientIdField.fill('Bad-client');
  await page.getByLabel('Client Name').fill('Broken Client');
  await expect(clientIdField).toHaveClass(/input-error/);
  await expect(clientIdField).toHaveCSS('border-top-color', 'rgb(248, 81, 73)');
  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('good-client');
  await page.getByLabel('Client Name').fill('Good Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://good.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');

  await page.getByRole('button', { name: 'front-channel', exact: true }).click();
  await page.getByPlaceholder('https://app.example.com/logout/frontchannel').fill('javascript:alert(1)');
  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

  await expect(page.getByText('Logout URI must use https://', { exact: true })).toBeVisible();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/clients')).toBeFalsy();

  await page.getByRole('button', { name: 'back-channel', exact: true }).click();
  await page.getByPlaceholder('https://app.example.com/logout/backchannel').fill('com.example.app://logout');
  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

  await expect(page.getByText('Logout URI must use https://', { exact: true })).toBeVisible();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/clients')).toBeFalsy();
});

test('preserves the front-channel URI when switching logout modes', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  const redirectUriField = page.getByPlaceholder('https://app.example.com/callback');
  await redirectUriField.fill('not-a-uri');

  await expect(redirectUriField).toHaveClass(/input-error/);
  await expect(redirectUriField).toHaveCSS('border-top-color', 'rgb(248, 81, 73)');
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
  await page.getByLabel('Access Token TTL').fill('2');

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

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('alpha-web');
  await page.getByLabel('Client Name').fill('Duplicate Client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://duplicate.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

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
  });

  // The client should NOT be added to the list
  await expect(page.locator('.client-card').filter({ hasText: 'Duplicate Client' })).toHaveCount(0);
});