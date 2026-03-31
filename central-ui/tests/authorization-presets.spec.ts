import { expect, test } from '@playwright/test';
import { loadAdminApp } from './fixtures';

const clientsPath = '/?view=clients&tenant=tenant-alpha';

const alphaWebClient = {
  id: 'alpha-web',
  clientName: 'Alpha Web App',
  redirectUris: ['https://alpha.example.com/callback', 'https://alpha.example.com/silent'],
  scope: ['openid', 'profile', 'email'],
  permissions: [],
  secretRotation: false,
};

const alphaPresets = [
  {
    id: 'web-login',
    clientId: 'alpha-web',
    description: 'Web Login',
    redirectUri: 'https://alpha.example.com/callback',
    scope: ['openid', 'profile'],
    responseType: 'code',
    uiLocales: ['en', 'fr'],
  },
  {
    id: 'mobile-login',
    clientId: 'alpha-web',
    description: 'Mobile Login',
    redirectUri: 'https://alpha.example.com/silent',
    scope: ['openid', 'email'],
    responseType: 'code id_token',
  },
];

function clientCard(page: any, clientId: string) {
  return page.locator('.client-card').filter({ hasText: clientId });
}

test('loads presets when client is expanded', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaWebClient] } },
  });

  const card = clientCard(page, 'alpha-web');

  // Initially no preset request
  expect(api.requests.some(req =>
    req.pathname === '/v1/configuration/auth-request-presets'
  )).toBeFalsy();

  // Click to expand
  await card.locator('.client-header').click();

  // Wait for presets to load
  await page.waitForResponse(resp =>
    resp.url().includes('/v1/configuration/auth-request-presets') && resp.status() === 200
  );

  // Verify GET request was made with correct params
  const presetsRequest = api.requests.find(req =>
    req.pathname === '/v1/configuration/auth-request-presets' &&
    req.method === 'GET'
  );
  expect(presetsRequest).toBeTruthy();
  expect(presetsRequest?.searchParams).toMatchObject({
    tenantId: 'tenant-alpha',
    clientId: 'alpha-web',
  });
});

test('caches presets and does not reload on subsequent expands', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaWebClient] } },
  });

  const card = clientCard(page, 'alpha-web');

  // Expand first time
  await card.locator('.client-header').click();
  await page.waitForResponse(resp =>
    resp.url().includes('/v1/configuration/auth-request-presets')
  );

  const firstLoadCount = api.requests.filter(req =>
    req.pathname === '/v1/configuration/auth-request-presets' &&
    req.method === 'GET'
  ).length;

  // Collapse
  await card.locator('.client-header').click();

  // Expand second time
  await card.locator('.client-header').click();

  // Wait a bit to ensure no new request
  await page.waitForTimeout(200);

  const secondLoadCount = api.requests.filter(req =>
    req.pathname === '/v1/configuration/auth-request-presets' &&
    req.method === 'GET'
  ).length;

  // Should be the same - no second load
  expect(secondLoadCount).toBe(firstLoadCount);
});

test('displays loaded presets in the expanded client card', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaWebClient] },
      authorizationPresets: { 'alpha-web': alphaPresets },
    },
  });

  const card = clientCard(page, 'alpha-web');
  await card.locator('.client-header').click();

  // Wait for presets to load
  await page.waitForResponse(resp =>
    resp.url().includes('/v1/configuration/auth-request-presets') && resp.status() === 200
  );

  // Expand presets section
  await card.getByText('Authorization Presets').click();

  // Check preset details are visible
  await expect(card.getByText('Web Login')).toBeVisible();
  await expect(card.getByText('Mobile Login')).toBeVisible();
});

test('shows preset details in card', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaWebClient] },
      authorizationPresets: { 'alpha-web': alphaPresets },
    },
  });

  const card = clientCard(page, 'alpha-web');
  await card.locator('.client-header').click();

  // Click on "Authorization Presets" to expand the section
  await card.getByText('Authorization Presets').click();

  // Presets show all details immediately in the card
  const webLoginPreset = card.locator('.preset-card').filter({ hasText: 'Web Login' });

  // Check all details are visible
  await expect(webLoginPreset).toContainText('https://alpha.example.com/callback');
  await expect(webLoginPreset).toContainText('openid, profile');
  await expect(webLoginPreset).toContainText('code');
  await expect(webLoginPreset).toContainText('en, fr');
});

test('adds a new preset with auto-generated UUID', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaWebClient] },
      authorizationPresets: { 'alpha-web': [] },
    },
  });

  const card = clientCard(page, 'alpha-web');
  await card.locator('.client-header').click();
  await expect(card.getByText('Authorization Presets')).toBeVisible();

  // Click add preset button - this opens form in full page view
  await card.getByRole('button', { name: 'Add Preset' }).click();

  // Form is now at page level, not in card
  await page.getByLabel('Description').fill('Admin Login');
  await page.getByLabel('Redirect URI').selectOption('https://alpha.example.com/callback');

  // Select scopes using checkboxes
  await page.locator('#scope-openid').check();
  await page.locator('#scope-email').check();

  await page.getByLabel('Response Type').selectOption('code');

  // Save the form
  await page.getByRole('button', { name: 'Create Preset' }).click();

  // Wait for POST request
  await page.waitForResponse(resp =>
    resp.url().includes('/v1/configuration/auth-request-presets') &&
    resp.request().method() === 'POST' &&
    resp.status() === 204
  );

  // Verify request body
  const saveRequest = api.requests.find(req =>
    req.pathname === '/v1/configuration/auth-request-presets' &&
    req.method === 'POST'
  );

  expect(saveRequest?.body).toMatchObject({
    tenantId: 'tenant-alpha',
    clientId: 'alpha-web',
    presets: expect.arrayContaining([
      expect.objectContaining({
        description: 'Admin Login',
        redirectUri: 'https://alpha.example.com/callback',
        scope: ['openid', 'email'],
        responseType: 'code',
      }),
    ]),
  });

  // ID should be a valid UUID
  const preset = (saveRequest?.body as any)?.presets?.[0];
  expect(preset?.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
});

test('edits an existing preset', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaWebClient] },
      authorizationPresets: { 'alpha-web': alphaPresets },
    },
  });

  const card = clientCard(page, 'alpha-web');
  await card.locator('.client-header').click();

  // Click on "Authorization Presets" to expand the section
  await card.getByText('Authorization Presets').click();

  // Click edit on Web Login preset using aria-label
  await card.getByRole('button', { name: 'Edit preset Web Login' }).click();

  // Form opens in full page view
  await page.getByLabel('Description').fill('Updated Web Login');

  // Save
  await page.getByRole('button', { name: 'Update Preset' }).click();

  await page.waitForResponse(resp =>
    resp.url().includes('/v1/configuration/auth-request-presets') &&
    resp.request().method() === 'POST'
  );

  // Verify updated data
  const saveRequest = api.requests.find(req =>
    req.pathname === '/v1/configuration/auth-request-presets' &&
    req.method === 'POST'
  );

  expect(saveRequest?.body).toMatchObject({
    presets: expect.arrayContaining([
      expect.objectContaining({
        id: 'web-login',
        description: 'Updated Web Login',
      }),
    ]),
  });
});

test('deletes a preset', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaWebClient] },
      authorizationPresets: { 'alpha-web': alphaPresets },
    },
  });

  const card = clientCard(page, 'alpha-web');
  await card.locator('.client-header').click();

  // Click on "Authorization Presets" to expand the section
  await card.getByText('Authorization Presets').click();

  // Initially should show both presets
  await expect(card.getByText('Web Login')).toBeVisible();
  await expect(card.getByText('Mobile Login')).toBeVisible();

  // Click delete and handle the confirm dialog
  await Promise.all([
    page.waitForEvent('dialog').then(dialog => dialog.accept()),
    page.waitForResponse(resp =>
      resp.url().includes('/v1/configuration/auth-request-presets') &&
      resp.request().method() === 'DELETE'
    ),
    card.getByRole('button', { name: 'Delete preset Web Login' }).click(),
  ]);

  // Verify the DELETE request
  const deleteRequest = api.requests.find(req =>
    req.pathname === '/v1/configuration/auth-request-presets' &&
    req.method === 'DELETE'
  );

  // Should have deleted the web-login preset
  expect(deleteRequest?.searchParams).toMatchObject({
    clientId: 'alpha-web',
    id: 'web-login',
  });

  // Web Login should be removed
  await expect(card.getByText('Web Login')).not.toBeVisible();
  await expect(card.getByText('Mobile Login')).toBeVisible();
});

test('validates preset form - redirect URI must be in client allowed URIs', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaWebClient] },
      authorizationPresets: { 'alpha-web': [] },
    },
  });

  const card = clientCard(page, 'alpha-web');
  await card.locator('.client-header').click();

  // Add preset - opens full page form
  await card.getByRole('button', { name: 'Add Preset' }).click();

  // The redirect URI dropdown should only show client's allowed URIs
  const redirectUriSelect = page.getByLabel('Redirect URI');

  // Count total options (empty option + 2 allowed URIs)
  const optionCount = await redirectUriSelect.locator('option').count();
  expect(optionCount).toBe(3); // Empty option + 2 allowed URIs

  // Verify the correct URIs are in the dropdown
  const firstUri = await redirectUriSelect.locator('option[value="https://alpha.example.com/callback"]').count();
  const secondUri = await redirectUriSelect.locator('option[value="https://alpha.example.com/silent"]').count();
  expect(firstUri).toBe(1);
  expect(secondUri).toBe(1);
});

test('handles backend validation errors gracefully', async ({ page }) => {
  // Set up dialog handler to capture alert message
  let alertMessage = '';
  page.on('dialog', async dialog => {
    alertMessage = dialog.message();
    await dialog.accept();
  });

  await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [alphaWebClient] },
      authorizationPresets: { 'alpha-web': [] },
    },
  });

  // Intercept and return error
  await page.route('**/v1/configuration/auth-request-presets', async route => {
    if (route.request().method() === 'POST') {
      await route.fulfill({ status: 400, body: 'Invalid redirect URI' });
    } else {
      await route.continue();
    }
  });

  const card = clientCard(page, 'alpha-web');
  await card.locator('.client-header').click();

  await card.getByRole('button', { name: 'Add Preset' }).click();

  // Form is at page level
  await page.getByLabel('Description').fill('Test');
  await page.getByLabel('Redirect URI').selectOption('https://alpha.example.com/callback');
  await page.getByRole('button', { name: 'Create Preset' }).click();

  // Wait a bit for the alert to trigger
  await page.waitForTimeout(500);

  // Should have shown error via alert
  expect(alertMessage).toContain('Failed to save presets');
});
