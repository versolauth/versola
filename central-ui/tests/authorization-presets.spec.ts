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
    id: 'a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d',
    clientId: 'alpha-web',
    description: 'Web Login',
    redirectUri: 'https://alpha.example.com/callback',
    scope: ['openid', 'profile'],
    responseType: 'code',
    uiLocales: ['en', 'fr'],
  },
  {
    id: 'b2c3d4e5-f6a7-4b5c-9d0e-1f2a3b4c5d6e',
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

  // Preset cards show description and ID by default (collapsed)
  const webLoginPreset = card.locator('.preset-card').filter({ hasText: 'Web Login' });
  await expect(webLoginPreset).toContainText('Web Login');
  await expect(webLoginPreset).toContainText('a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d');

  // Click on the preset card to expand and see all details
  await webLoginPreset.locator('.preset-card-header').click();

  // Now all details should be visible
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

  // Click on "Authorization Presets" to expand the section
  await card.getByText('Authorization Presets').click();

  // Click the edit icon to open the preset editing view
  await card.getByRole('button', { name: 'Edit presets for alpha-web' }).click();

  // Click "+ Add Preset" to open the form
  await page.getByRole('button', { name: '+ Add Preset' }).click();

  // The preset form should now be visible - fill it in
  await page.getByLabel('Description').fill('Admin Login');
  await page.getByLabel('Redirect URI').selectOption('https://alpha.example.com/callback');

  // Select scopes - click the entire card (not just the checkbox)
  await page.locator('.checkbox-item').filter({ hasText: 'openid' }).click();

  await page.getByLabel('Response Type').selectOption('code');

  // Submit the form
  await page.getByRole('button', { name: 'Create Preset' }).click();

  // Wait for the form to close and the edit view to show
  await expect(page.getByRole('button', { name: 'Save Presets' })).toBeVisible();

  // Now save all presets
  await page.getByRole('button', { name: 'Save Presets' }).click();

  // Wait for the save to complete
  await page.waitForTimeout(300);

  // Verify the API request
  const saveRequest = api.requests.find(req =>
    req.pathname === '/v1/configuration/auth-request-presets' &&
    req.method === 'POST'
  );

  expect(saveRequest).toBeTruthy();
  expect(saveRequest?.body).toMatchObject({
    clientId: 'alpha-web',
    presets: expect.arrayContaining([
      expect.objectContaining({
        description: 'Admin Login',
        redirectUri: 'https://alpha.example.com/callback',
        scope: ['openid'],
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

  // Click the edit icon to open the preset editing view
  await card.getByRole('button', { name: 'Edit presets for alpha-web' }).click();

  // Find the Web Login preset and click its edit icon (✎)
  const webLoginCard = page.locator('.preset-card').filter({ hasText: 'Web Login' });
  await webLoginCard.getByRole('button', { name: 'Edit preset' }).click();

  // The preset form should now be visible - edit the description
  await page.getByLabel('Description').fill('Updated Web Login');

  // Submit the form
  await page.getByRole('button', { name: 'Update Preset' }).click();

  // Wait for the form to close and the edit view to show
  await expect(page.getByRole('button', { name: 'Save Presets' })).toBeVisible();

  // Save all presets
  await page.getByRole('button', { name: 'Save Presets' }).click();

  // Wait for the save to complete
  await page.waitForTimeout(300);

  // Verify the API request
  const saveRequest = api.requests.find(req =>
    req.pathname === '/v1/configuration/auth-request-presets' &&
    req.method === 'POST'
  );

  expect(saveRequest).toBeTruthy();
  expect(saveRequest?.body).toMatchObject({
    presets: expect.arrayContaining([
      expect.objectContaining({
        id: 'a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d',
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

  // Click the edit icon to open the preset editing view
  await card.getByRole('button', { name: 'Edit presets for alpha-web' }).click();

  // Find the Web Login preset and click its delete icon (✕)
  const webLoginCard = page.locator('.preset-card').filter({ hasText: 'Web Login' });
  await webLoginCard.getByRole('button', { name: 'Delete preset' }).click();

  // Save the changes
  await page.getByRole('button', { name: 'Save Presets' }).click();

  // Wait for the save to complete
  await page.waitForTimeout(300);

  // Verify the API request
  const saveRequest = api.requests.find(req =>
    req.pathname === '/v1/configuration/auth-request-presets' &&
    req.method === 'POST'
  );

  // The request should only include the Mobile Login preset (Web Login was deleted)
  const presets = (saveRequest?.body as any)?.presets || [];
  expect(presets).toHaveLength(1);
  expect(presets[0].id).toBe('b2c3d4e5-f6a7-4b5c-9d0e-1f2a3b4c5d6e');
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

  // Click on "Authorization Presets" to expand the section
  await card.getByText('Authorization Presets').click();

  // Click the edit icon to open the preset edit form
  await card.getByRole('button', { name: 'Edit presets for alpha-web' }).click();

  // Add preset - opens form
  await page.getByRole('button', { name: '+ Add Preset' }).click();

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

  // Click on "Authorization Presets" to expand the section
  await card.getByText('Authorization Presets').click();

  // Click the edit icon to open the preset editing view
  await card.getByRole('button', { name: 'Edit presets for alpha-web' }).click();

  // Add preset
  await page.getByRole('button', { name: '+ Add Preset' }).click();

  // Fill in the form
  await page.getByLabel('Description').fill('Test');
  await page.getByLabel('Redirect URI').selectOption('https://alpha.example.com/callback');

  // Submit the form
  await page.getByRole('button', { name: 'Create Preset' }).click();

  // Wait for the form to close and the edit view to show
  await expect(page.getByRole('button', { name: 'Save Presets' })).toBeVisible();

  // Save presets (this will trigger the error)
  await page.getByRole('button', { name: 'Save Presets' }).click();

  // Wait a bit for the alert to trigger
  await page.waitForTimeout(500);

  // Should have shown error via alert
  expect(alertMessage).toContain('Failed to save presets');
});
