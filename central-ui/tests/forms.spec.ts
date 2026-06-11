import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';
import type { MockConfigState } from './mocks';

const formsPath = '/?view=forms&tenant=tenant-alpha';

const credentialV1 = {
  id: 'credential',
  version: 1,
  active: true,
  style: '.form { color: red; }',
  jsSource: 'export const Form = () => null;',
  jsCompiled: 'document.getElementById("versola-form-root").textContent = "preview";',
  localizations: { en: { title: 'Sign in' }, ru: { title: 'Вход' } },
  properties: [
    { type: 'BooleanProperty' as const, name: 'showRemember' },
    { type: 'StringArrayProperty' as const, name: 'mode', allowedValues: ['email', 'phone'] },
  ],
};
const credentialV2 = { ...credentialV1, version: 2, active: false };

const locales = [
  { code: 'en', name: 'English' },
  { code: 'ru', name: 'Russian' },
];

const themes = [
  { id: 'default', css: 'body { background: #fff; }', tenantId: null },
  { id: 'dark', css: 'body { background: #000; }', tenantId: null },
];

const fullState: Partial<MockConfigState> = {
  forms: [credentialV1, credentialV2],
  formLocales: locales,
  themes,
};

function formHeader(page: Page, text: string) {
  return page.locator('.form-header').filter({ hasText: text });
}

async function expandCredential(page: Page) {
  await formHeader(page, 'credential').click();
}

async function expandThemes(page: Page) {
  await formHeader(page, 'Themes').click();
}

test('renders locales, forms and themes sections', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: fullState });

  await expect(page.locator('.form-id').filter({ hasText: 'Locales' })).toBeVisible();
  await expect(page.locator('.form-id').filter({ hasText: 'credential' })).toBeVisible();
  await expect(page.locator('.form-id').filter({ hasText: 'Themes' })).toBeVisible();
});

test('expands a form and shows versions with active badge', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandCredential(page);

  await expect(page.locator('.version-badge', { hasText: 'v1' })).toBeVisible();
  await expect(page.locator('.version-badge', { hasText: 'v2' })).toBeVisible();
  await expect(page.locator('.active-bubble')).toHaveCount(1);
});

test('sets a non-active version as active', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandCredential(page);
  await page.getByRole('button', { name: 'Set Active', exact: true }).click();
  await page.waitForTimeout(300);

  expect(findRequest(api.requests, 'PUT', '/configuration/forms/active').body).toEqual({
    id: 'credential',
    version: 2,
  });
});

test('creates a new form', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: { forms: [], formLocales: locales, themes } });

  await page.getByRole('button', { name: 'New Form', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'New Form', exact: true })).toBeVisible();
  await page.getByPlaceholder('e.g. credential').fill('signup');
  await page.getByRole('button', { name: 'Save as new version', exact: true }).click();
  await page.waitForTimeout(300);

  expect(findRequest(api.requests, 'PUT', '/configuration/forms').body).toMatchObject({ id: 'signup' });
});

test('creates a new version of an existing form', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandCredential(page);
  await page.getByRole('button', { name: 'New Version', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'New Version', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Save as new version', exact: true }).click();
  await page.waitForTimeout(300);

  expect(findRequest(api.requests, 'PUT', '/configuration/forms').body).toMatchObject({ id: 'credential' });
});

test('edits locales: adds a new locale', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: fullState });

  await page.getByRole('button', { name: 'Edit locales', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Edit Locales', exact: true })).toBeVisible();
  await page.getByPlaceholder('en', { exact: true }).fill('de');
  await page.getByPlaceholder('English', { exact: true }).fill('German');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await page.getByRole('button', { name: 'Save Locales', exact: true }).click();
  await page.waitForTimeout(300);

  const body = findRequest(api.requests, 'PUT', '/configuration/forms/locales').body as {
    add: Array<{ code: string; name: string }>;
    delete: string[];
  };
  expect(body.add).toEqual([{ code: 'de', name: 'German' }]);
  expect(body.delete).toEqual([]);
});

test('edits locales: removes an existing locale', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: fullState });

  await page.getByRole('button', { name: 'Edit locales', exact: true }).click();
  await page.getByRole('button', { name: 'Remove locale ru', exact: true }).click();
  await page.getByRole('button', { name: 'Save Locales', exact: true }).click();
  await page.waitForTimeout(300);

  const body = findRequest(api.requests, 'PUT', '/configuration/forms/locales').body as { delete: string[] };
  expect(body.delete).toEqual(['ru']);
});

test('creates a global theme', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandThemes(page);
  await page.getByRole('button', { name: 'New Theme', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'New Theme', exact: true })).toBeVisible();
  await page.getByPlaceholder('e.g. default').fill('brand');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page.waitForTimeout(300);

  expect(findRequest(api.requests, 'POST', '/configuration/themes').body).toMatchObject({
    id: 'brand',
    tenantId: null,
  });
});

test('edits a theme', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandThemes(page);
  await page.getByRole('button', { name: 'Edit theme dark', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Edit Theme', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  expect(findRequest(api.requests, 'PUT', '/configuration/themes').body).toMatchObject({ id: 'dark' });
});

test('deletes a non-default theme', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandThemes(page);
  await page.getByRole('button', { name: 'Edit theme dark', exact: true }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await page.waitForTimeout(300);

  findRequest(api.requests, 'DELETE', '/configuration/themes/dark');
});

test('renders a live preview iframe', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandCredential(page);
  await page.locator('.version-card').filter({ hasText: 'v1' }).click();
  await page.getByRole('button', { name: 'Preview', exact: true }).click();

  await expect(page.locator('iframe.preview-iframe')).toHaveCount(1);
});
