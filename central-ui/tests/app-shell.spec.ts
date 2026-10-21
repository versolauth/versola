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
    { navText: 'Security', heading: 'Sign-in and security', url: /view=security/ },
    { navText: 'OTP templates', heading: 'OTP templates', url: /view=otp-templates/ },
    { navText: 'Authorization details', heading: 'Authorization details', url: /view=authorization-details/ },
    { navText: 'Keys & metadata', heading: 'Keys & metadata', url: /view=keys/ },
    { navText: 'System settings', heading: 'System settings', url: /view=system-settings/ },
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
  await page.emulateMedia({ colorScheme: 'light' });

  await loadAdminApp(page);
  const navigation = page.locator('versola-navigation');
  const favicon = page.locator('link[rel="icon"]');

  await expect(favicon).toHaveAttribute('href', '/favicon-light.svg');

  await navigation.getByRole('button', { name: 'Colour scheme: Auto' }).click();
  await navigation.getByRole('option', { name: 'Dark' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(favicon).toHaveAttribute('href', 'favicon-dark.svg');

  await navigation.getByRole('button', { name: 'Colour scheme: Dark' }).click();
  await navigation.getByRole('option', { name: 'Light' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(favicon).toHaveAttribute('href', 'favicon-light.svg');
});

test('follows the operating system when nothing is persisted', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await loadAdminApp(page);

  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(page.locator('html')).toHaveAttribute('data-theme-pref', 'auto');
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href', '/favicon-light.svg');
});

test('uses the dark favicon for a persisted dark theme on first load', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await page.addInitScript(() => localStorage.setItem('versola-theme', 'dark'));
  await loadAdminApp(page);

  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href', 'favicon-dark.svg');
});

test('the navigation lists the tenant items, then the global ones, in the agreed order', async ({ page }) => {
  await loadAdminApp(page);

  const links = page.locator('versola-navigation').getByRole('navigation', { name: 'Sections' }).getByRole('button');
  const labels = (await links.allInnerTexts()).map(text => text.trim()).filter(Boolean);
  expect(labels).toEqual([
    'Security', 'Clients', 'Resources', 'Scopes', 'Permissions', 'Roles', 'OTP templates', 'Authorization details',
    'Tenants', 'Edges', 'Users', 'Keys & metadata', 'System settings', 'Forms', 'Themes',
  ]);
});

const renamed = [
  { old: 'challenges', heading: 'Sign-in and security', notice: 'Challenges & Security is now Security.', url: /view=security/ },
  { old: 'well-known', heading: 'Keys & metadata', notice: 'Well Known is now Keys & metadata.', url: /view=keys/ },
  { old: 'locales', heading: 'System settings', notice: 'Locales live under System settings.', url: /view=system-settings/ },
];

for (const view of renamed) {
  test(`an old bookmark to ${view.old} lands on its successor with a dismissible note`, async ({ page }) => {
    await loadAdminApp(page, { path: `/?view=${view.old}&tenant=tenant-alpha` });

    await expect(page).toHaveURL(view.url);
    await expect(page.getByRole('heading', { name: view.heading, level: 1 })).toBeVisible();
    const note = page.getByRole('status').filter({ hasText: view.notice });
    await expect(note).toBeVisible();
    await note.getByRole('button', { name: 'Dismiss' }).click();
    await expect(note).toHaveCount(0);
  });
}

test('the rename note is shown once: picking another view clears it', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=challenges&tenant=tenant-alpha' });
  await expect(page.getByRole('status').filter({ hasText: 'Challenges & Security is now Security.' })).toBeVisible();
  await page.locator('versola-navigation').getByText('Scopes', { exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'is now' })).toHaveCount(0);
});

test.describe('brand tokens', () => {
  for (const [theme, btn, onBtn, accent] of [
    ['light', 'rgb(28, 27, 25)', 'rgb(255, 255, 255)', '#155e75'],
    ['dark', 'rgb(243, 242, 240)', 'rgb(28, 27, 25)', '#8dacb8'],
  ] as const) {
    test(`${theme}: ink primary button, brand accent, system font, brand radii`, async ({ page }) => {
      await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
      await loadAdminApp(page);
      const create = page.getByRole('button', { name: /Create Client/ });
      await expect(create).toHaveCSS('background-color', btn);
      await expect(create).toHaveCSS('color', onBtn);
      await expect(create).toHaveCSS('border-top-left-radius', '12px');
      await expect(create).toHaveCSS('font-weight', '700');
      const h1 = page.getByRole('heading', { level: 1 }).first();
      await expect(h1).toHaveCSS('font-weight', '800');
      await expect(h1).toHaveCSS('font-size', '32px');
      expect(await h1.evaluate(el => getComputedStyle(el).fontFamily)).toMatch(/^system-ui/);
      expect(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim())).toBe(accent);
      await expect(page.getByRole('img', { name: 'Versola' })).toBeVisible();
    });
  }
});
