import { expect, test } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const wellKnownPath = '/?view=well-known&tenant=tenant-alpha';

test('marks which stored keys can sign and which only verify', async ({ page }) => {
  await loadAdminApp(page, { path: wellKnownPath });

  await expect(page.locator('.key-id').filter({ hasText: 'ps-kid' })).toBeVisible();
  await expect(page.locator('.key-meta').filter({ hasText: 'RSA · PS256' })).toBeVisible();

  // A key with no private half in central can never be a tenant's signing key. The list
  // says so, rather than leaving the picker to silently omit it.
  const badges = page.locator('.key-badge');
  await expect(badges.nth(0)).toHaveText(/Can sign/);
  await expect(badges.nth(1)).toHaveText(/Verify only/);
});

test('generates a key for the chosen algorithm and shows it in the list', async ({ page }) => {
  const api = await loadAdminApp(page, { path: wellKnownPath });

  await page.getByLabel('Algorithm to generate').selectOption('ES256');
  await page.getByRole('button', { name: 'Generate Key', exact: true }).click();

  const request = findRequest(api.requests, 'POST', '/configuration/jwks/generate');
  expect(request.searchParams['alg']).toBe('ES256');

  await expect(page.locator('.key-id').filter({ hasText: 'generated-es256' })).toBeVisible();
  // Published for verification immediately, and signing nothing until a tenant selects it:
  // the first of the two steps a rotation needs.
  await expect(
    page.locator('.key-card').filter({ hasText: 'generated-es256' }).locator('.key-badge'),
  ).toHaveText(/Can sign/);
});

test('offers no key management without the manage permission', async ({ page }) => {
  await loadAdminApp(page, {
    path: wellKnownPath,
    state: {
      myPermissions: { resources: { central: { permissions: ['jwks:read'] } }, isProd: false },
    },
  });

  await expect(page.locator('.key-id').filter({ hasText: 'ps-kid' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Generate Key', exact: true })).toHaveCount(0);
  await expect(page.getByLabel('Algorithm to generate')).toHaveCount(0);
});
