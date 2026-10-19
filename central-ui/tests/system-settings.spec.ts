import { expect, test } from '@playwright/test';
import { INVALID_FIELD_BORDER, findRequest, loadAdminApp } from './fixtures';

/**
 * System settings: the password policy record and the Locales card, whose row actions save
 * immediately. The password editor is a page state; the add-locale form is small and inline.
 */
const path = '/?view=system-settings';
const locales = [
  { code: 'en', name: 'English', isDefault: true, active: true },
  { code: 'ru', name: 'Russian', isDefault: false, active: true },
  { code: 'kk', name: 'Kazakh', isDefault: false, active: false },
];
const systemSettings = { passwordRegex: '.{8,}', passwordHistorySize: 5, passwordNumDifferent: 3 };
const readOnly = { resources: { central: { permissions: ['security:read', 'locales:read'] } }, isProd: false };

test('shows the password policy and the locales as cards with their states', async ({ page }) => {
  await loadAdminApp(page, { path, state: { systemSettings, locales } });

  await expect(page.getByRole('region', { name: 'Locales' })).toContainText('Changes to locales apply immediately.');
  const policy = page.getByRole('region', { name: 'Password policy' });
  await expect(policy).toContainText('.{8,}');
  await expect(policy).toContainText('5 previous passwords');
  await expect(page.getByRole('region', { name: 'Locales' }).getByRole('row')).toHaveCount(4);
  const kk = page.getByRole('row').filter({ hasText: 'Kazakh' });
  await expect(kk).toContainText('Inactive');
  await expect(page.getByRole('row').filter({ hasText: 'English' })).toContainText('Default');
  await expect(page.getByRole('button', { name: '+ Create' })).toHaveCount(0);
});

test('updates the password policy from its editor and returns to the record', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { systemSettings, locales } });

  await page.getByRole('button', { name: 'Edit password policy' }).click();
  await expect(page).toHaveURL(/edit=password/);
  await page.getByLabel('Password regex *').fill('.{12,}');
  await page.getByRole('button', { name: 'Save password policy' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/system-settings')).body).toEqual({
    passwordRegex: '.{12,}',
    passwordHistorySize: 5,
    passwordNumDifferent: 3,
  });
  await expect(page).not.toHaveURL(/edit=password/);
  await expect(page.getByRole('region', { name: 'Password policy' })).toContainText('.{12,}');
});

test('refuses an empty or malformed regex and a negative count before saving', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=password`, state: { systemSettings } });

  const regex = page.getByLabel('Password regex *');
  await regex.fill('');
  await page.getByRole('button', { name: 'Save password policy' }).click();
  await expect(page.getByText('The password regex is required.')).toBeVisible();
  await expect(regex).toHaveAttribute('aria-invalid', 'true');
  await expect(regex).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await regex.fill('(');
  await page.getByLabel('Password history size').fill('-1');
  await page.getByRole('button', { name: 'Save password policy' }).click();
  await expect(page.getByText('Not a valid regular expression.')).toBeVisible();
  await expect(page.getByText('A whole number, 0 or more.')).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);
});

test('asks before Cancel drops an edited draft', async ({ page }) => {
  await loadAdminApp(page, { path: `${path}&edit=password`, state: { systemSettings } });

  await page.getByLabel('Password regex *').fill('.{20,}');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  const confirm = page.getByRole('dialog', { name: 'Discard changes?' });
  await confirm.getByRole('button', { name: 'Keep editing' }).click();
  await expect(page).toHaveURL(/edit=password/);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await confirm.getByRole('button', { name: 'Discard' }).click();
  await expect(page).not.toHaveURL(/edit=password/);
});

test('sets another default locale and saves at once', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { systemSettings, locales } });

  await page.getByRole('button', { name: 'Actions for ru' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Set as default', 'Deactivate', 'Remove locale']);
  await page.getByRole('menuitem', { name: 'Set as default' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/locales/default')).body).toEqual({ code: 'ru' });
  await expect(page.getByRole('row').filter({ hasText: 'Russian' })).toContainText('Default');
});

test('the default locale can be neither deactivated nor removed, and says why', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { systemSettings, locales } });

  await page.getByRole('button', { name: 'Actions for en' }).click();
  await expect(page.getByRole('menuitem', { name: 'Set as default' })).toHaveCount(0);
  const deactivate = page.getByRole('menuitem', { name: 'Deactivate en' });
  await expect(deactivate).toHaveAttribute('aria-disabled', 'true');
  await expect(deactivate).toHaveAttribute('title', 'Pick another default first');
  await expect(deactivate).toContainText('Pick another default first');
  await deactivate.click({ force: true });
  await expect(page.getByRole('menuitem', { name: 'Remove locale en' })).toHaveAttribute('aria-disabled', 'true');
  expect(api.requests.filter(request => request.pathname === '/configuration/locales' && request.method === 'PUT')).toHaveLength(0);
});

test('activates and deactivates a locale', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { systemSettings, locales } });

  await page.getByRole('button', { name: 'Actions for kk' }).click();
  await page.getByRole('menuitem', { name: 'Activate kk' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/locales')).body).toMatchObject({ add: [{ code: 'kk', active: true }], delete: [] });
  await expect(page.getByRole('row').filter({ hasText: 'Kazakh' })).toContainText('Active');

  await page.getByRole('button', { name: 'Actions for kk' }).click();
  await page.getByRole('menuitem', { name: 'Deactivate kk' }).click();
  const confirm = page.getByRole('dialog', { name: 'Deactivate locale' });
  await expect(confirm).toContainText('Login pages stop offering Kazakh');
  await confirm.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.getByRole('row').filter({ hasText: 'Kazakh' })).toContainText('Active');
  await page.getByRole('button', { name: 'Actions for kk' }).click();
  await page.getByRole('menuitem', { name: 'Deactivate kk' }).click();
  await page.getByRole('dialog', { name: 'Deactivate locale' }).getByRole('button', { name: 'Deactivate', exact: true }).click();
  await expect(page.getByRole('row').filter({ hasText: 'Kazakh' })).toContainText('Inactive');
});

test('lists the missing keys when a locale cannot be activated', async ({ page }) => {
  await loadAdminApp(page, {
    path,
    state: { systemSettings, locales, localeActivationMissing: { kk: ['forms.sign-in.title', 'scopes.email.description'] } },
  });

  await page.getByRole('button', { name: 'Actions for kk' }).click();
  await page.getByRole('menuitem', { name: 'Activate kk' }).click();
  const alert = page.getByRole('alert').filter({ hasText: 'Cannot activate kk' });
  await expect(alert).toContainText('content is missing for 2 keys');
  await expect(alert).toContainText('forms.sign-in.title');
  await expect(alert).toContainText('scopes.email.description');
  await expect(page.getByRole('row').filter({ hasText: 'Kazakh' })).toContainText('Inactive');
});

test('removes a locale after confirmation', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { systemSettings, locales } });

  await page.getByRole('button', { name: 'Actions for kk' }).click();
  await page.getByRole('menuitem', { name: 'Remove locale kk' }).click();
  await page.getByRole('dialog', { name: 'Remove locale' }).getByRole('button', { name: 'Remove', exact: true }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/locales')).body).toMatchObject({ add: [], delete: ['kk'] });
  await expect(page.getByRole('row').filter({ hasText: 'Kazakh' })).toHaveCount(0);
});

test('adds a locale from the small form, inactive unless asked', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { systemSettings, locales } });

  await page.getByRole('button', { name: '+ Add locale' }).click();
  const form = page.getByRole('form', { name: 'Add locale' });
  await form.getByRole('button', { name: 'Add locale', exact: true }).click();
  await expect(form.getByText('Pick a language.')).toBeVisible();
  await form.getByLabel('Language *').selectOption('de');
  await expect(form.getByText('Code de · Deutsch')).toBeVisible();
  await expect(form.getByRole('option', { name: /\(en\)/ })).toHaveCount(0);
  await form.getByRole('button', { name: 'Add locale', exact: true }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/locales')).body).toMatchObject({ add: [{ code: 'de', name: 'Deutsch', active: false }], delete: [] });
  await expect(page.getByRole('row').filter({ hasText: 'Deutsch' })).toContainText('Inactive');
  await expect(form).toHaveCount(0);
});

test('read-only console: no Edit, no Add locale, no row menus', async ({ page }) => {
  await loadAdminApp(page, { path, state: { systemSettings, locales, myPermissions: readOnly } });

  await expect(page.getByRole('region', { name: 'Password policy' })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: 'Kazakh' })).toBeVisible();
  await expect(page.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '+ Add locale' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Actions for/ })).toHaveCount(0);
});

test('without locales:read the Locales card is absent', async ({ page }) => {
  await loadAdminApp(page, { path, state: { systemSettings, locales, myPermissions: { resources: { central: { permissions: ['security:read', 'security:manage'] } }, isProd: false } } });

  await expect(page.getByRole('region', { name: 'Password policy' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Locales' })).toHaveCount(0);
});

test('no longer carries the logo: it is a tenant setting', async ({ page }) => {
  await loadAdminApp(page, { path });

  await expect(page.getByText('Identity provider logo')).toHaveCount(0);
});
