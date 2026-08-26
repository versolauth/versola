import { expect, test } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const systemSettingsPath = '/?view=system-settings';

test('shows and updates identity provider logo settings', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: systemSettingsPath,
    state: {
      systemSettings: {
        passwordRegex: '.{8,}',
        passwordHistorySize: 5,
        passwordNumDifferent: 3,
        identityProviderLogo: 'https://assets.example/current.svg',
      },
    },
  });

  await expect(page.getByText('https://assets.example/current.svg')).toBeVisible();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.locator('input[type="url"]').fill('https://assets.example/new.svg');
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  expect(findRequest(api.requests, 'PUT', '/configuration/system-settings').body).toMatchObject({
    identityProviderLogo: 'https://assets.example/new.svg',
  });
  await expect(page.getByText('https://assets.example/new.svg')).toBeVisible();
});

test('rejects malformed logo URLs before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: systemSettingsPath });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.locator('input[type="url"]').fill('https://');
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  await expect(page.getByText('Logo must be an absolute http(s) URL.')).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === '/configuration/system-settings')).toBeFalsy();
});

test('sends null when the configured logo is cleared', async ({ page }) => {
  const api = await loadAdminApp(page, { path: systemSettingsPath });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.locator('input[type="url"]').fill('');
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  expect(findRequest(api.requests, 'PUT', '/configuration/system-settings').body).toMatchObject({
    identityProviderLogo: null,
  });
});