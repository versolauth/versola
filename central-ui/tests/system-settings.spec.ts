import { expect, test } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const systemSettingsPath = '/?view=system-settings';

test('updates the password policy', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: systemSettingsPath,
    state: { systemSettings: { passwordRegex: '.{8,}', passwordHistorySize: 5, passwordNumDifferent: 3 } },
  });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.locator('input[type="text"]').fill('.{12,}');
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/system-settings')).body).toEqual({
    passwordRegex: '.{12,}',
    passwordHistorySize: 5,
    passwordNumDifferent: 3,
  });
});

test('no longer carries the logo: it is a tenant setting', async ({ page }) => {
  await loadAdminApp(page, { path: systemSettingsPath });

  await expect(page.getByText('Identity provider logo')).toHaveCount(0);
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.locator('input[type="url"]')).toHaveCount(0);
});
