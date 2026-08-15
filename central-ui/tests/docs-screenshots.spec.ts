/**
 * Generates the screenshots embedded in the public documentation
 * (versola-website: docs/entities/*). Not an assertion suite — every test here
 * exists to produce a PNG under `docs-screenshots/`, which is then copied into
 * versola-website/public/img/docs/entities/. The expects only pin the state the
 * screenshot is supposed to capture.
 *
 * Run with: npx playwright test docs-screenshots
 */
import { expect, test, type Page } from '@playwright/test';
import { loadAdminApp } from './fixtures';

const outputDir = 'docs-screenshots';
const tenant = 'tenant-alpha';

const scopes = [
  { scope: 'openid', description: { en: 'OpenID scope' }, claims: [{ claim: 'sub', description: { en: 'Subject' } }] },
  { scope: 'profile', description: { en: 'Profile data' }, claims: [{ claim: 'name', description: { en: 'Full name' } }] },
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

const storefrontClient = {
  id: 'storefront-web',
  clientName: 'Storefront Web',
  redirectUris: ['https://storefront.example.com/callback'],
  scope: ['openid', 'profile'],
  permissions: ['orders.read', 'orders.write'],
  secretRotation: false,
  authFlow: {
    primary: { credentials: ['phone'], inlinePassword: false, factors: [{ type: 'otp', required: true }] },
    passkey: null,
    otpType: 'sms',
  },
};

const alphaAdmin = { id: 'alpha_admin', description: { en: 'Alpha admin' }, permissions: ['orders.read', 'orders.write'], active: true };
const userRole = { id: 'user', description: { en: 'Self-registered user' }, permissions: [], active: true };

const dana = {
  id: '00000000-0000-0000-0000-000000000042',
  email: 'dana@example.com',
  phone: '+14155559876',
  login: 'dana',
  claims: { name: 'Dana Reyes' },
  rolesByTenant: { [tenant]: ['alpha_admin'] },
};

const state = {
  scopes: { [tenant]: scopes },
  permissions: { [tenant]: permissions },
  resources: { [tenant]: [ordersApi] },
  clients: { [tenant]: [storefrontClient] },
  roles: { [tenant]: [alphaAdmin, userRole] },
  users: [dana],
};

/**
 * Forms are taller than the viewport. `versola-navigation`'s sidebar is
 * `position: fixed; height: 100vh`, so Playwright's `fullPage` capture (which
 * stitches scrolled sections) paints it only within the original viewport
 * height, leaving the rest of a tall page without a sidebar. Growing the
 * viewport to the full document height before shooting avoids any scrolling,
 * so the sidebar's 100vh naturally covers the whole capture.
 */
async function shot(page: Page, name: string, fullPage = false) {
  if (fullPage) {
    const docHeight = await page.evaluate(() => document.documentElement.scrollHeight);
    const viewport = page.viewportSize();
    if (viewport && docHeight > viewport.height) {
      await page.setViewportSize({ width: viewport.width, height: docHeight });
    }
  }
  await page.screenshot({ path: `${outputDir}/${name}.png`, fullPage });
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
});

test('clients list', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=clients&tenant=${tenant}`, state });

  await expect(page.getByRole('heading', { name: 'OAuth Clients', exact: true })).toBeVisible();
  await expect(page.locator('.client-card')).toHaveCount(1);
  await shot(page, 'clients-list');

  await page.locator('.client-card').filter({ hasText: 'Storefront Web' }).first().locator('.client-header').click();
  await expect(page.getByText('https://storefront.example.com/callback').first()).toBeVisible();
  await shot(page, 'clients-list-expanded', true);
});

test('client create form', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=clients&tenant=${tenant}`, state });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create New Client', exact: true })).toBeVisible();
  await shot(page, 'client-form-empty', true);

  await page.getByLabel('Client ID').fill('checkout-web');
  await page.getByLabel('Client Name').fill('Checkout Web');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://checkout.example.com/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');
  await page.getByRole('checkbox', { name: 'openid', exact: true }).check();
  await page.getByRole('checkbox', { name: 'profile', exact: true }).check();
  await page.getByRole('checkbox', { name: 'orders.read', exact: true }).check();
  await expect(page.getByText('https://checkout.example.com/callback')).toBeVisible();
  await shot(page, 'client-form-filled', true);

  await page.getByRole('button', { name: 'Create Client', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Client created: Checkout Web', exact: true })).toBeVisible();
  await expect(page.locator('.secret-banner .secret-value')).toBeVisible();
  await page.locator('.secret-banner').scrollIntoViewIfNeeded();
  await shot(page, 'client-created-secret');
});

test('client registration flow form', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=clients&tenant=${tenant}`, state });

  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByLabel('Client ID').fill('checkout-web');
  await page.getByLabel('Client Name').fill('Checkout Web');
  await page.getByPlaceholder('https://app.example.com/callback').fill('https://checkout.example.com/callback');
  await page.getByPlaceholder('https://app.example.com/callback').press('Enter');

  const registrationRow = page.getByText('Registration', { exact: true }).locator('..');
  await registrationRow.locator('label.toggle').click();
  await expect(page.locator('[aria-label="Registration credential (locked)"]')).toContainText('phone');

  await page.getByLabel('Challenge', { exact: true }).selectOption('setPassword');
  await page.getByRole('group', { name: 'Assigned roles' }).getByRole('checkbox', { name: 'alpha_admin', exact: true }).check();
  await shot(page, 'client-form-registration', true);
});

test('resources list', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=resources&tenant=${tenant}`, state });

  await expect(page.getByRole('heading', { name: 'Resources', exact: true })).toBeVisible();
  await expect(page.locator('.resource-shell')).toHaveCount(1);
  await shot(page, 'resources-list');

  await page.locator('.resource-shell').filter({ hasText: 'orders-api.orders.svc.cluster.local' }).first().locator('.resource-header').click();
  await expect(page.locator('.audience-view-item')).toHaveText(['storefront-web']);
  await expect(page.locator('.endpoint-card')).toHaveCount(2);
  await shot(page, 'resources-list-expanded');
});

test('resource create form', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=resources&tenant=${tenant}`, state });

  await page.getByRole('button', { name: '+ Create Resource', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create Resource', exact: true })).toBeVisible();
  await shot(page, 'resource-form-empty');

  await page.getByRole('textbox', { name: 'Resource ID' }).fill('invoices-api');
  await page.getByLabel('Absolute resource URI').fill('http://invoices-api.billing.svc.cluster.local:8080');
  await page.getByRole('button', { name: 'internal', exact: true }).click();
  await page.getByRole('textbox', { name: 'Audience client' }).fill('storefront-web');
  await page.getByRole('option', { name: 'storefront-web', exact: true }).click();
  await page.getByRole('button', { name: 'Add audience', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Remove audience storefront-web', exact: true })).toBeVisible();
  await shot(page, 'resource-form-identity');

  await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  const endpoint = page.locator('.endpoint-editor').last();
  await endpoint.locator('select').first().selectOption('GET');
  await endpoint.getByPlaceholder('/users').fill('/invoices');
  await endpoint.getByRole('textbox', { name: 'Allow expression' }).click();
  await page.keyboard.type("'orders.read' in token.scope");
  await shot(page, 'resource-form-endpoint', true);

  await page.getByRole('button', { name: 'Create Resource', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Resource created: invoices-api', exact: true })).toBeVisible();
  await expect(page.locator('.secret-banner .secret-value')).toBeVisible();
  await page.locator('.secret-banner').scrollIntoViewIfNeeded();
  await shot(page, 'resource-created-secret');
});

test('users list and search', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=users&tenant=${tenant}`, state });

  await expect(page.getByRole('heading', { name: 'Search for a user', exact: true })).toBeVisible();
  await shot(page, 'users-search-empty');

  await page.getByPlaceholder('Search users…').fill('dana');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const card = page.locator('.user-card').filter({ hasText: 'Dana Reyes' }).first();
  await expect(card).toBeVisible();
  await shot(page, 'users-list');

  await card.getByRole('button', { name: 'Get Roles', exact: true }).click();
  await expect(card.locator('.role-tag', { hasText: 'alpha_admin' })).toBeVisible();
  await shot(page, 'users-list-roles-expanded', true);
});

test('user create form', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=users&tenant=${tenant}`, state });

  await page.getByRole('button', { name: '+ Create User', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create New User', exact: true })).toBeVisible();
  await shot(page, 'user-form-empty');

  await page.getByLabel('Email').fill('newowner@example.com');
  await page.getByLabel('Phone').fill('+14155550100');
  await page.getByLabel('Login').fill('newowner');
  await shot(page, 'user-form-filled');
});

test('user edit claims form', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=users&tenant=${tenant}`, state });

  await page.getByPlaceholder('Search users…').fill('dana');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const card = page.locator('.user-card').filter({ hasText: 'Dana Reyes' }).first();
  await card.getByRole('button', { name: 'Get Claims', exact: true }).click();
  await card.locator('.icon-action[title="Edit Claims"]').click();

  await expect(page.getByRole('heading', { name: 'Edit Claims', exact: true })).toBeVisible();
  await shot(page, 'user-claims-edit', true);
});

test('tenants list', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=tenants&tenant=${tenant}`, state });

  await expect(page.getByRole('heading', { name: 'Tenants', exact: true })).toBeVisible();
  await shot(page, 'tenants-list');
});

test('tenant create form', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=tenants&tenant=${tenant}`, state });

  await page.getByRole('button', { name: '+ Create Tenant', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create Tenant', exact: true })).toBeVisible();
  await shot(page, 'tenant-form-empty');

  await page.getByLabel('Tenant ID *').fill('acme-prod');
  await page.getByLabel('Description').fill('Acme production');
  await shot(page, 'tenant-form-filled');
});

test('scopes list', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=scopes&tenant=${tenant}`, state });

  await expect(page.getByRole('heading', { name: 'OAuth Scopes', exact: true })).toBeVisible();
  await expect(page.locator('.scope-card')).toHaveCount(scopes.length);
  await shot(page, 'scopes-list');

  await page.locator('.scope-card').filter({ hasText: 'OpenID scope' }).first().locator('.scope-header').click();
  await expect(page.getByText('Subject')).toBeVisible();
  await shot(page, 'scopes-list-expanded');
});

test('scope create form', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=scopes&tenant=${tenant}`, state });

  await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create New Scope', exact: true })).toBeVisible();
  await shot(page, 'scope-form-empty');

  await page.getByLabel('Scope ID *').fill('orders_read');
  await page.getByLabel('Description *').fill('Read order history');
  await page.getByLabel('Claim ID').fill('order_count');
  await page.getByLabel('Claim Description').fill('Number of orders placed');
  await page.getByRole('button', { name: 'Add Claim', exact: true }).click();
  await expect(page.getByText('order_count')).toBeVisible();
  await shot(page, 'scope-form-filled');
});

test('permissions list', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=permissions&tenant=${tenant}`, state });

  await expect(page.getByRole('heading', { name: 'Permissions', exact: true })).toBeVisible();
  await shot(page, 'permissions-list');

  await page.locator('.permission-card').filter({ hasText: 'orders.read' }).first().click();
  await expect(page.locator('.endpoint-row').filter({ hasText: '/orders' }).first()).toBeVisible();
  await shot(page, 'permissions-list-expanded');
});

test('permission create form', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=permissions&tenant=${tenant}`, state });

  await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create Permission', exact: true })).toBeVisible();
  await shot(page, 'permission-form-empty');

  await page.getByLabel('Permission ID *').fill('orders.cancel');
  await page.getByLabel('English description').fill('Cancel orders');
  await page.getByLabel('Add resource').selectOption({ label: 'orders-api.orders.svc.cluster.local:8080' });
  await page.getByRole('button', { name: 'Add resource', exact: true }).click();
  await page.getByLabel(/Add endpoint for/).selectOption({ label: 'POST /orders' });
  await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  await expect(page.locator('.endpoint-row').filter({ hasText: '/orders' })).toBeVisible();
  await shot(page, 'permission-form-filled', true);
});

test('roles list', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=roles&tenant=${tenant}`, state });

  await expect(page.getByRole('heading', { name: 'Roles', exact: true })).toBeVisible();
  await shot(page, 'roles-list');

  await page.locator('.role-card').filter({ hasText: 'Alpha admin' }).first().locator('.role-header').click();
  await expect(page.locator('.permission-item').filter({ hasText: 'orders.read' })).toBeVisible();
  await shot(page, 'roles-list-expanded');
});

test('role create form', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=roles&tenant=${tenant}`, state });

  await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create New Role', exact: true })).toBeVisible();
  await shot(page, 'role-form-empty', true);

  await page.getByLabel('Role ID *').fill('support');
  await page.getByLabel('Description *').fill('Customer support agent');
  await page.getByRole('checkbox', { name: /orders\.read/ }).check();
  await shot(page, 'role-form-filled', true);
});
