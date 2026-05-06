import { expect, test } from '@playwright/test';
import { loadAdminApp, tenantSelectorButton } from './fixtures';

test('renders the app shell with the default clients view', async ({ page }) => {
  const api = await loadAdminApp(page);

  await expect(page.getByRole('heading', { name: 'OAuth Clients', exact: true })).toBeVisible();
  await expect(page.locator('versola-navigation')).toBeVisible();
  await expect(page.locator('tenant-selector').getByRole('button', { name: 'Manage' })).toBeVisible();
  await expect(tenantSelectorButton(page)).toContainText('tenant-alpha');
  expect(api.requests.some(request => request.method === 'GET' && request.pathname === '/v1/configuration/tenants')).toBeTruthy();
});

test('switches navigation views and keeps the url in sync', async ({ page }) => {
  await loadAdminApp(page);

  const navigation = page.locator('versola-navigation');
  const views = [
    { navText: 'Scopes', heading: 'OAuth Scopes', url: /view=scopes/ },
    { navText: 'Permissions', heading: 'Permissions', url: /view=permissions/ },
    { navText: 'Resources', heading: 'Resources', url: /view=resources/ },
    { navText: 'Roles', heading: 'Roles', url: /view=roles/ },
  ];

  for (const view of views) {
    await navigation.getByText(view.navText, { exact: true }).click();
    await expect(page).toHaveURL(view.url);
    await expect(page.getByRole('heading', { name: view.heading, exact: true })).toBeVisible();
  }

  await page.locator('tenant-selector').getByRole('button', { name: 'Manage' }).click();
  await expect(page).toHaveURL(/view=tenants/);
  await expect(page.getByRole('heading', { name: 'Tenants', exact: true })).toBeVisible();
});

test('restores the tenant and current view from the url on first load', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=permissions&tenant=tenant-bravo' });

  await expect(page).toHaveURL(/view=permissions/);
  await expect(page.getByRole('heading', { name: 'Permissions', exact: true })).toBeVisible();
  await expect(tenantSelectorButton(page)).toContainText('tenant-bravo');
});