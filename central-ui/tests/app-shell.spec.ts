import { expect, test } from '@playwright/test';
import { loadAdminApp, tenantSelectorButton } from './fixtures';

test('renders the app shell with the default clients view', async ({ page }) => {
  const api = await loadAdminApp(page);

  await expect(page.getByRole('heading', { name: 'OAuth Clients', exact: true })).toBeVisible();
  const navigation = page.locator('versola-navigation');
  await expect(navigation).toBeVisible();
  await expect(navigation.getByText('Tenants', { exact: true })).toBeVisible();
  await expect(tenantSelectorButton(page)).toContainText('tenant-alpha');
  expect(api.requests.some(request => request.method === 'GET' && request.pathname === '/configuration/tenants')).toBeTruthy();
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

  await navigation.getByText('Tenants', { exact: true }).click();
  await expect(page).toHaveURL(/view=tenants/);
  await expect(page.getByRole('heading', { name: 'Tenants', exact: true })).toBeVisible();
});

test('restores the tenant and current view from the url on first load', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=permissions&tenant=tenant-bravo' });

  await expect(page).toHaveURL(/view=permissions/);
  await expect(page.getByRole('heading', { name: 'Permissions', exact: true })).toBeVisible();
  await expect(tenantSelectorButton(page)).toContainText('tenant-bravo');
});

test('switches themes for the session when localStorage is unavailable', async ({ page }) => {
  await page.addInitScript(() => {
    Storage.prototype.getItem = () => { throw new Error('localStorage unavailable'); };
    Storage.prototype.setItem = () => { throw new Error('localStorage unavailable'); };
  });

  await loadAdminApp(page);
  const navigation = page.locator('versola-navigation');
  const favicon = page.locator('link[rel="icon"]');

  await expect(favicon).toHaveAttribute('href', '/logo-shield.svg');

  await navigation.getByRole('radio', { name: 'Light theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(favicon).toHaveAttribute('href', 'logo-shield-light.svg');

  await navigation.getByRole('radio', { name: 'Dark theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(favicon).toHaveAttribute('href', 'logo-shield.svg');
});

test('uses the light favicon for a persisted light theme on first load', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('versola-theme', 'light'));
  await loadAdminApp(page);

  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href', 'logo-shield-light.svg');
});