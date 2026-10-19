import { expect, test } from '@playwright/test';
import { INVALID_FIELD_BORDER, findRequest, loadAdminApp, sidePanel, sidePanelDialog, expectSheetWidth } from './fixtures';

/**
 * OAuth scopes on the table + side panel pattern: the list, opening a scope by click and by
 * keyboard, creating, editing a group, deleting through the confirm dialog, the read-only
 * console and the full-screen sheet below 720px.
 */
const scopesPath = '/?view=scopes&tenant=tenant-alpha';
const locales = [
  { code: 'en', name: 'English', isDefault: true, active: true },
  { code: 'ru', name: 'Русский', isDefault: false, active: true },
];
const openidScope = {
  scope: 'openid',
  description: { en: 'OpenID scope', ru: 'OpenID' },
  claims: [{ claim: 'sub', description: { en: 'Subject', ru: 'Субъект' } }, { claim: 'email', description: { en: 'Email address', ru: 'Почта' } }],
};
const profileScope = { scope: 'profile', description: { en: 'Profile data' }, claims: [{ claim: 'name', description: { en: 'Display name' } }] };
const offlineScope = { scope: 'offline_access', description: { en: 'Offline access', ru: 'Офлайн' }, claims: [] };
const readOnly = { resources: { central: { permissions: ['oauth:read'] } }, isProd: false };

const panel = sidePanel;

test('lists scopes with claims and languages, filters by chip and searches by ID or description', async ({ page }) => {
  await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope, profileScope, offlineScope] }, locales } });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Scope', 'Claims', 'Languages', 'Actions']);
  const openid = table.getByRole('row').filter({ hasText: 'OpenID scope' });
  await expect(openid.getByRole('cell').nth(1)).toHaveText('2');
  await expect(openid.getByRole('cell').nth(2)).toHaveText('2 of 2');
  await expect(table.getByRole('row').filter({ hasText: 'Profile data' })).toContainText('Missing: ru');

  await page.getByRole('button', { name: /^No claims\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open scope/ })).toHaveText([/offline_access/]);
  await page.getByRole('button', { name: /^Missing translations\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open scope/ })).toHaveText([/profile/]);
  await page.getByRole('button', { name: /^All\s?3$/ }).click();

  await page.getByLabel('Search scopes').fill('Profile');
  await expect(table.getByRole('button', { name: /^Open scope/ })).toHaveCount(1);
  await page.getByLabel('Search scopes').fill('nothing');
  await expect(page.getByRole('heading', { name: 'No scopes match' })).toBeVisible();
});

test('opens the panel from the row, shows the groups, and the URL follows', async ({ page }) => {
  await loadAdminApp(page, {
    path: scopesPath,
    state: {
      scopes: { 'tenant-alpha': [openidScope] },
      clients: { 'tenant-alpha': [{ id: 'alpha-web', clientName: { en: 'Alpha Web' }, redirectUris: [], scope: ['openid'], permissions: [], secretRotation: false }] },
      locales,
    },
  });

  await page.locator('versola-data-table').getByRole('row').filter({ hasText: 'OpenID scope' }).getByRole('cell').nth(1).click();
  const dialog = panel(page, 'OpenID scope');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expect(dialog.getByRole('dialog')).toHaveAttribute('aria-modal', 'false');
  await expect(page).toHaveURL(/id=openid/);
  await expect(dialog.getByRole('region', { name: 'General' })).toContainText('OpenID scope');
  await expect(dialog.getByRole('region', { name: 'Claims' })).toContainText('sub');
  await expect(dialog.getByRole('region', { name: 'Claims' })).toContainText('Email address');
  await expect(dialog.getByRole('region', { name: 'Requested by' })).toContainText('alpha-web');
  await expect(dialog.getByRole('region', { name: 'Fixed' })).toContainText('Set at creation; clients request it by this value');
  await expect(dialog.getByRole('region', { name: 'Delete scope' })).toBeVisible();
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'OpenID scope' })).toHaveAttribute('aria-current', 'true');

  await dialog.getByRole('button', { name: 'Close panel' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page).not.toHaveURL(/id=/);
});

test('opens with Enter on the row and Escape closes it and returns focus to the row', async ({ page }) => {
  await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope] } } });

  const row = page.getByRole('button', { name: /^Open scope (.* )?openid$/ });
  await row.focus();
  await page.keyboard.press('Enter');
  await expect(sidePanelDialog(page, 'OpenID scope')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'OpenID scope', level: 2 })).toBeFocused();

  await page.keyboard.press('Escape');
  await expect(panel(page, 'OpenID scope')).toHaveCount(0);
  await expect(row).toBeFocused();
});

test('restores the panel and the group editor from the URL', async ({ page }) => {
  await loadAdminApp(page, { path: `${scopesPath}&id=openid&edit=claims`, state: { scopes: { 'tenant-alpha': [openidScope] } } });

  const dialog = panel(page, 'Edit claims');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expect(dialog.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
  await expect(dialog.getByLabel('Claim ID')).toHaveCount(2);
});

test('creates a scope with localized descriptions and claims in the panel and opens it', async ({ page }) => {
  const api = await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [] }, locales } });

  await expect(page.getByRole('heading', { name: 'No OAuth scopes yet', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();
  const dialog = panel(page, 'Create scope');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expect(page).toHaveURL(/new=1/);

  await dialog.getByLabel('Scope ID *').fill('profile');
  await dialog.locator('#scope-description').fill('Profile data');
  await dialog.getByRole('tab', { name: 'ru', exact: true }).first().click();
  await dialog.locator('#scope-description-ru').fill('Данные профиля');
  await dialog.getByRole('button', { name: '+ Add claim' }).click();
  await dialog.getByLabel('Claim ID').fill('name');
  await dialog.locator('versola-localized-text-editor').nth(1).locator('input').fill('Display name');
  await dialog.getByRole('button', { name: 'Create scope', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/scopes')).body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'profile',
    description: { en: 'Profile data', ru: 'Данные профиля' },
    claims: [{ id: 'name', description: { en: 'Display name' } }],
  });
  await expect(sidePanelDialog(page, 'Profile data')).toBeVisible();
  await expect(page).toHaveURL(/id=profile/);
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'profile' })).toHaveAttribute('aria-current', 'true');
});

test('refuses an invalid scope ID and a claim without an English description before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope] } } });

  await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();
  const dialog = panel(page, 'Create scope');
  const id = dialog.getByLabel('Scope ID *');
  await id.fill('BadScope');
  await expect(id).toHaveAttribute('aria-invalid', 'true');
  await expect(id).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await expect(dialog.getByText('Lowercase letters, digits and underscores, starting with a letter.')).toBeVisible();
  await id.fill('openid');
  await expect(dialog.getByText('A scope with this ID already exists.')).toBeVisible();

  await id.fill('orders');
  await dialog.locator('#scope-description').fill('Orders');
  await dialog.getByRole('button', { name: '+ Add claim' }).click();
  await dialog.getByLabel('Claim ID').fill('order_count');
  await dialog.getByRole('button', { name: 'Create scope', exact: true }).click();
  await expect(dialog.getByText('A description in English is required.')).toBeVisible();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/scopes')).toBeFalsy();
});

test('edits the general group and sends only the description patch', async ({ page }) => {
  const api = await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope] }, locales } });

  await page.getByRole('button', { name: /^Open scope (.* )?openid$/ }).click();
  await panel(page, 'OpenID scope').getByRole('button', { name: 'Edit general' }).click();
  const editor = panel(page, 'Edit general');
  await expect(editor.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
  await expect(page).toHaveURL(/edit=general/);
  await editor.locator('#scope-description').fill('OpenID claims');
  await editor.getByRole('button', { name: 'Save general' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/scopes')).body).toMatchObject({
    tenantId: 'tenant-alpha',
    id: 'openid',
    patch: { description: { add: { en: 'OpenID claims' }, delete: [] } },
  });
  await expect(panel(page, 'OpenID claims').getByRole('region', { name: 'General' })).toContainText('OpenID claims');
  await expect(page.locator('versola-data-table')).toContainText('OpenID claims');
});

test('edits the claims group and sends patch-style claim changes', async ({ page }) => {
  const api = await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope] }, locales } });

  await page.getByRole('button', { name: /^Open scope (.* )?openid$/ }).click();
  await panel(page, 'OpenID scope').getByRole('button', { name: 'Edit claims' }).click();
  const editor = panel(page, 'Edit claims');
  await expect(editor.getByLabel('Claim ID').first()).toHaveAttribute('readonly', '');
  await editor.getByRole('button', { name: 'Remove claim email' }).click();
  await editor.getByRole('tab', { name: 'ru', exact: true }).first().click();
  await editor.locator('#claim-description-1-ru').fill('Идентификатор субъекта');
  await editor.getByRole('button', { name: '+ Add claim' }).click();
  await editor.getByLabel('Claim ID').last().fill('name');
  await editor.locator('versola-localized-text-editor').last().locator('input').fill('Display name');
  await editor.getByRole('button', { name: 'Save claims' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/scopes')).body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'openid',
    patch: {
      add: [{ id: 'name', description: { en: 'Display name' } }],
      update: [{ id: 'sub', description: { add: { ru: 'Идентификатор субъекта' }, delete: [] } }],
      delete: ['email'],
      description: { add: {}, delete: [] },
    },
  });
  const claims = panel(page, 'OpenID scope').getByRole('region', { name: 'Claims' });
  await expect(claims).toContainText('name');
  await expect(claims).not.toContainText('Email address');
});

test('deletes a scope from the panel through the confirm dialog and reaches the empty state', async ({ page }) => {
  const api = await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope] } } });

  await page.getByRole('button', { name: /^Open scope (.* )?openid$/ }).click();
  await panel(page, 'OpenID scope').getByRole('button', { name: 'Delete scope openid' }).click();
  await page.getByRole('dialog', { name: 'Delete scope' }).getByRole('button', { name: 'Delete', exact: true }).click();

  expect((await findRequest(api.requests, 'DELETE', '/configuration/scopes')).searchParams).toEqual({ tenantId: 'tenant-alpha', scopeId: 'openid' });
  await expect(panel(page, 'OpenID scope')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No OAuth scopes yet', exact: true })).toBeVisible();
});

test('deletes a scope from the row menu', async ({ page }) => {
  const api = await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope, profileScope] } } });

  await page.getByRole('button', { name: 'Actions for profile' }).click();
  await page.getByRole('menuitem', { name: 'Delete scope profile' }).click();
  await page.getByRole('dialog', { name: 'Delete scope' }).getByRole('button', { name: 'Delete', exact: true }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/scopes')).searchParams.scopeId).toBe('profile');
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open scope/ })).toHaveCount(1);
});

test('read-only console: no create, no Edit, no danger card, row menu without Delete', async ({ page }) => {
  await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope] }, myPermissions: readOnly } });

  await expect(page.getByRole('button', { name: '+ Create Scope' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Actions for openid' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy scope ID']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const dialog = panel(page, 'OpenID scope');
  await expect(dialog.getByRole('region', { name: 'General' })).toBeVisible();
  await expect(dialog.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(dialog.getByRole('region', { name: 'Delete scope' })).toHaveCount(0);
});

test('opens the scope as a page from the panel', async ({ page }) => {
  await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope] } } });

  await page.getByRole('button', { name: /^Open scope (.* )?openid$/ }).click();
  await panel(page, 'OpenID scope').getByRole('button', { name: 'Open page' }).click();
  await expect(page).toHaveURL(/layout=page/);
  await expect(page.getByRole('heading', { name: 'OpenID scope', level: 1 })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Claims' })).toBeVisible();
  await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Scopes' }).click();
  await expect(page.getByRole('heading', { name: 'OAuth Scopes', exact: true })).toBeVisible();
});

test('below 720px the panel is a full-screen sheet and nothing scrolls sideways', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${scopesPath}&id=openid`, state: { scopes: { 'tenant-alpha': [openidScope] } } });

  const dialog = panel(page, 'OpenID scope');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  const box = await dialog.getByRole('dialog').boundingBox();
  await expectSheetWidth(page, box?.width);
  expect(box?.x).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);

  await dialog.getByRole('button', { name: 'Edit claims' }).click();
  await expect(panel(page, 'Edit claims').getByRole('button', { name: 'Save claims' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test('asks before Escape drops an edited draft, and closes straight away when nothing changed', async ({ page }) => {
  await loadAdminApp(page, { path: scopesPath, state: { scopes: { 'tenant-alpha': [openidScope] }, locales } });

  await page.getByRole('button', { name: /^Open scope (.* )?openid$/ }).click();
  await panel(page, 'OpenID scope').getByRole('button', { name: 'Edit general' }).click();
  await panel(page, 'Edit general').getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page).not.toHaveURL(/edit=general/);

  await panel(page, 'OpenID scope').getByRole('button', { name: 'Edit general' }).click();
  await panel(page, 'Edit general').locator('#scope-description').fill('Changed');
  await page.keyboard.press('Escape');
  const confirm = page.getByRole('dialog', { name: 'Discard changes?' });
  await confirm.getByRole('button', { name: 'Keep editing' }).click();
  await expect(page).toHaveURL(/edit=general/);
  await expect(panel(page, 'Edit general').locator('#scope-description')).toHaveValue('Changed');

  await page.keyboard.press('Escape');
  await confirm.getByRole('button', { name: 'Discard' }).click();
  await expect(page).not.toHaveURL(/edit=general/);
});
