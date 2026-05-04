import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const clientsPath = '/?view=clients&tenant=tenant-alpha';

const alphaClient = {
  id: 'alpha-web',
  clientName: 'Alpha Web',
  redirectUris: ['https://alpha.example/callback'],
  scope: ['openid'],
  permissions: ['alpha.read'],
  secretRotation: false,
};

const serviceClient = {
  id: 'service-client',
  clientName: 'Service Client',
  redirectUris: ['https://service.example/callback'],
  scope: ['email'],
  permissions: ['alpha.write'],
  secretRotation: false,
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

  const search = page.getByLabel('Search clients');
  await search.fill('service-client');
  await expect(clientCard(page, 'Service Client')).toBeVisible();
  await expect(page.locator('.client-card').filter({ hasText: 'alpha-web' })).toHaveCount(0);

  await search.fill('missing-client');
  await expect(page.getByRole('heading', { name: 'No clients match your search', exact: true })).toBeVisible();
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
  await page.getByLabel('External Audience').fill('alp');
  await expect(page.getByRole('button', { name: 'Select audience alpha-web', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Select audience service-client', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Select audience alpha-web', exact: true }).click();
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
  await expect(created).toContainText('alpha-web');
  await expect(created).toContainText('30m');

  expect(findRequest(api.requests, 'POST', '/v1/configuration/clients').body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'dashboard-client',
    clientName: 'Dashboard Client',
    redirectUris: ['https://dashboard.example/callback'],
    allowedScopes: ['openid'],
    audience: ['alpha-web'],
    permissions: ['alpha.read'],
    accessTokenTtl: 1800,
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

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/clients')).toBeFalsy();

  await page.getByLabel('Client ID').fill('good-client');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://broken.example/callback#fragment');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await expect(page.getByText('URI must not contain fragment (#)', { exact: true })).toBeVisible();
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

test('requires external audience to reference an existing client', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('service-client');
  await page.getByLabel('Client Name').fill('Service Client');
  await page.getByLabel('External Audience').fill('Alpha-Web');
  await page.getByLabel('External Audience').press('Enter');

  await expect(page.getByText('Lowercase letters, numbers, hyphen, start with letter', { exact: true })).toBeVisible();
  await expect(page.locator('.tag-list .tag').filter({ hasText: 'Alpha-Web' })).toHaveCount(0);

  await page.getByLabel('External Audience').fill('missing-client');
  await page.getByLabel('External Audience').press('Enter');

  await expect(page.getByText('Audience must reference an existing client in this tenant', { exact: true })).toBeVisible();
  await expect(page.getByText('missing-client', { exact: true })).toHaveCount(0);
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/clients')).toBeFalsy();

  await page.getByLabel('External Audience').fill('alpha-web');
  await page.getByLabel('External Audience').press('Enter');
  await expect(page.getByText('alpha-web', { exact: true })).toBeVisible();

  await page.getByPlaceholder('https://app.example.com/callback').fill('https://service.example/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.getByRole('button', { name: 'Create Client', exact: true }).click();

  expect(findRequest(api.requests, 'POST', '/v1/configuration/clients').body.audience).toEqual(['alpha-web']);
});

test('rejects duplicate external audiences', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('service-client');
  await page.getByLabel('Client Name').fill('Service Client');
  await page.getByLabel('External Audience').fill('alpha-web');
  await page.getByLabel('External Audience').press('Enter');
  await expect(page.locator('.tag-list .tag').filter({ hasText: 'alpha-web' })).toHaveCount(1);

  await page.getByLabel('External Audience').fill('alpha-web');
  await page.getByLabel('External Audience').press('Enter');

  await expect(page.getByText('Audience has already been added', { exact: true })).toBeVisible();
  await expect(page.locator('.tag-list .tag').filter({ hasText: 'alpha-web' })).toHaveCount(1);
});

test('rejects self as external audience', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('alpha-web');
  await page.getByLabel('Client Name').fill('Alpha Clone');
  await page.getByLabel('External Audience').fill('alpha-web');
  await page.getByLabel('External Audience').press('Enter');

  await expect(page.getByText('Audience cannot reference the client itself', { exact: true })).toBeVisible();
  await expect(page.locator('.tag-list .tag').filter({ hasText: 'alpha-web' })).toHaveCount(0);
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/clients')).toBeFalsy();
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
  await page.getByLabel('External Audience').fill('serv');
  await expect(page.getByRole('button', { name: 'Select audience service-client', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Select audience alpha-web', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Select audience service-client', exact: true }).click();
  await page.getByRole('checkbox', { name: 'openid', exact: true }).uncheck();
  await page.getByRole('checkbox', { name: 'email', exact: true }).check();
  await page.getByRole('checkbox', { name: 'alpha.read', exact: true }).uncheck();
  await page.getByRole('checkbox', { name: 'alpha.write', exact: true }).check();
  await page.getByLabel('Access Token TTL').fill('2');
  await page.getByRole('button', { name: 'Update Client', exact: true }).click();

  const updated = clientCard(page, 'Alpha Console');
  await expect(updated).toContainText('alpha-web');
  await updated.locator('.client-header').click();
  await expect(updated).toContainText('service-client');
  expect(findRequest(api.requests, 'PUT', '/v1/configuration/clients').body).toEqual({
    tenantId: 'tenant-alpha',
    clientId: 'alpha-web',
    clientName: 'Alpha Console',
    redirectUris: { add: ['https://alpha.example/admin/callback'], remove: ['https://alpha.example/callback'] },
    scope: { add: ['email'], remove: ['openid'] },
    permissions: { add: ['alpha.write'], remove: ['alpha.read'] },
    accessTokenTtl: 7200,
  });
});

test('rotates a client secret and deletes the previous secret', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath });

  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Edit client alpha-web' }).click();
  await page.getByRole('button', { name: 'Rotate Secret', exact: true }).click();

  await expect(page.getByRole('heading', { name: 'Secret rotated: Alpha Web', exact: true })).toBeVisible();
  await expect(page.getByText('rotated-alpha-web', { exact: true })).toBeVisible();
  expect(findRequest(api.requests, 'POST', '/v1/configuration/clients/rotate-secret').searchParams).toEqual({
    tenantId: 'tenant-alpha',
    clientId: 'alpha-web',
  });
  await expect(clientCard(page, 'Alpha Web')).toContainText('Secret Rotation');

  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Edit client alpha-web' }).click();
  await page.getByRole('button', { name: 'Delete old secret', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect(findRequest(api.requests, 'DELETE', '/v1/configuration/clients/previous-secret').searchParams).toEqual({
    tenantId: 'tenant-alpha',
    clientId: 'alpha-web',
  });
  await expect(clientCard(page, 'Alpha Web')).not.toContainText('Secret Rotation');
});

test('deletes a client through the confirm dialog and reaches the empty state', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [alphaClient] } },
  });

  await clientCard(page, 'Alpha Web').getByRole('button', { name: 'Delete client alpha-web' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect(findRequest(api.requests, 'DELETE', '/v1/configuration/clients').searchParams).toEqual({
    tenantId: 'tenant-alpha',
    clientId: 'alpha-web',
  });
  await expect(page.locator('.client-card')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No OAuth clients yet', exact: true })).toBeVisible();
});