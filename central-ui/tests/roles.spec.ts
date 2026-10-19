import { expect, test } from '@playwright/test';
import { INVALID_FIELD_BORDER, findRequest, loadAdminApp, sidePanel, sidePanelDialog, expectSheetWidth } from './fixtures';
import { defaultChallengeSettings } from './mocks';

/**
 * Roles on the table + side panel pattern: the list with its status chips, the panel with the
 * permissions grouped by category, create with the grouped picker, the group editors, delete
 * (refused for a default role), the read-only console and the sheet below 720px.
 */
const rolesPath = '/?view=roles&tenant=tenant-alpha';
const ordersRead = { permission: 'orders:read', description: { en: 'Read orders' }, endpointIds: [101] };
const ordersWrite = { permission: 'orders:write', description: { en: 'Create orders' }, endpointIds: [] };
const reportsRead = { permission: 'reports:read', description: { en: 'Read reports' }, endpointIds: [201] };
const alphaAdmin = { id: 'alpha-admin', description: { en: 'Alpha admin' }, permissions: ['orders:read', 'reports:read'], active: true };
const supportTeam = { id: 'support-team', description: { en: 'Support team' }, permissions: ['reports:read'], active: false };
const userRole = { id: 'user', description: { en: 'Self-registered user' }, permissions: [], active: true };
const auditRead = { permission: 'audit:read', description: { en: 'Read the audit log' }, endpointIds: [] };
const permissions = { 'tenant-alpha': [ordersRead, ordersWrite, reportsRead, auditRead] };
const defaultRoleSettings = { 'tenant-alpha': { ...defaultChallengeSettings('tenant-alpha'), defaultRoles: ['user'] } };
const readOnly = { resources: { central: { permissions: ['access:read'] } }, isProd: false };

const panel = sidePanel;

test('lists roles with their status, filters by chip and searches by ID or description', async ({ page }) => {
  await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin, supportTeam, userRole] }, challengeSettings: defaultRoleSettings } });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Role', 'Permissions', 'Status', 'Actions']);
  await expect(table.getByRole('row').filter({ hasText: 'Alpha admin' })).toContainText('Active');
  await expect(table.getByRole('row').filter({ hasText: 'Support team' })).toContainText('Inactive');
  await expect(table.getByRole('row').filter({ hasText: 'Self-registered user' })).toContainText('Default for sign-up');

  await page.getByRole('button', { name: /^Inactive\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open role/ })).toHaveText([/support-team/]);
  await page.getByRole('button', { name: /^Default for sign-up\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open role/ })).toHaveText([/user/]);
  await page.getByRole('button', { name: /^All\s?3$/ }).click();

  await page.getByLabel('Search roles').fill('Support');
  await expect(table.getByRole('button', { name: /^Open role/ })).toHaveCount(1);
  await page.getByLabel('Search roles').fill('missing');
  await expect(page.getByRole('heading', { name: 'No roles match' })).toBeVisible();
});

test('opens a role and shows its permissions grouped by category, Fixed and the danger card', async ({ page }) => {
  await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin] }, permissions } });

  await page.getByRole('button', { name: /^Open role (.* )?alpha-admin$/ }).click();
  const dialog = panel(page, 'Alpha admin');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expect(page).toHaveURL(/id=alpha-admin/);
  const perms = dialog.getByRole('region', { name: 'Permissions (2)' });
  await expect(perms).toContainText('orders');
  await expect(perms).toContainText('reports');
  await expect(perms.getByRole('button', { name: 'Show endpoints for orders:read' })).toBeVisible();
  const fixed = dialog.getByRole('region', { name: 'Fixed' });
  await expect(fixed).toContainText('Set at creation');
  await expect(fixed).not.toContainText('Active');
  await expect(dialog.getByRole('region', { name: 'General' })).toContainText('Active');
  await expect(dialog.getByRole('button', { name: 'Delete role alpha-admin' })).toBeEnabled();

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Open role (.* )?alpha-admin$/ })).toBeFocused();
});

test('creates a role with permissions picked by group and with the search', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [] }, permissions } });

  await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
  const dialog = panel(page, 'Create role');
  await dialog.getByLabel('Role ID *').fill('platform-admin');
  await dialog.locator('#role-description').fill('Platform admin');
  const picker = dialog.getByRole('group', { name: 'Permissions' });
  await picker.getByRole('button', { name: 'Select all' }).nth(1).click();
  await expect(dialog.getByRole('heading', { name: 'Permissions (2 selected)' })).toBeVisible();
  await picker.getByLabel('Filter permissions').fill('reports');
  await expect(picker.getByRole('checkbox')).toHaveCount(1);
  await picker.getByRole('checkbox', { name: 'reports:read' }).check();
  await picker.getByLabel('Filter permissions').fill('');
  await picker.getByRole('checkbox', { name: 'orders:write' }).uncheck();
  await dialog.getByRole('button', { name: 'Create role', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/roles')).body).toEqual({
    tenantId: 'tenant-alpha', id: 'platform-admin', description: { en: 'Platform admin' }, permissions: ['orders:read', 'reports:read'], active: true,
  });
  await expect(panel(page, 'Platform admin').getByRole('region', { name: 'Permissions (2)' })).toContainText('reports:read');
});

test('refuses an invalid role ID with a red border before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin] } } });

  await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
  const id = panel(page, 'Create role').getByLabel('Role ID *');
  await id.fill('Bad_role');
  await expect(id).toHaveAttribute('aria-invalid', 'true');
  await expect(id).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await panel(page, 'Create role').getByRole('button', { name: 'Create role', exact: true }).click();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/roles')).toBeFalsy();
});

test('edits the permissions group and sends patch-style changes', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin] }, permissions } });

  await page.getByRole('button', { name: /^Open role (.* )?alpha-admin$/ }).click();
  await panel(page, 'Alpha admin').getByRole('button', { name: 'Edit permissions (2)' }).click();
  const editor = panel(page, 'Edit permissions');
  await expect(page).toHaveURL(/edit=permissions/);
  await editor.getByRole('checkbox', { name: 'orders:read' }).uncheck();
  await editor.getByRole('checkbox', { name: 'orders:write' }).check();
  await editor.getByRole('button', { name: 'Save permissions' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/roles')).body).toEqual({
    tenantId: 'tenant-alpha', id: 'alpha-admin', description: { add: {}, delete: [] }, permissions: { add: ['orders:write'], remove: ['orders:read'] },
  });
  await expect(panel(page, 'Alpha admin').getByRole('region', { name: 'Permissions (2)' })).toContainText('orders:write');
});

test('edits the general group', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin] } } });

  await page.getByRole('button', { name: /^Open role (.* )?alpha-admin$/ }).click();
  await panel(page, 'Alpha admin').getByRole('button', { name: 'Edit general' }).click();
  await panel(page, 'Edit general').locator('#role-description').fill('Platform admins');
  await panel(page, 'Edit general').getByRole('button', { name: 'Save general' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/roles')).body).toEqual({
    tenantId: 'tenant-alpha', id: 'alpha-admin', description: { add: { en: 'Platform admins' }, delete: [] }, permissions: { add: [], remove: [] },
  });
  await expect(page.locator('versola-data-table')).toContainText('Platform admins');
});

test('deletes a role from the panel through the confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin] } } });

  await page.getByRole('button', { name: /^Open role (.* )?alpha-admin$/ }).click();
  await panel(page, 'Alpha admin').getByRole('button', { name: 'Delete role alpha-admin' }).click();
  await page.getByRole('dialog', { name: 'Delete role' }).getByRole('button', { name: 'Delete', exact: true }).click();

  expect((await findRequest(api.requests, 'DELETE', '/configuration/roles')).searchParams).toEqual({ tenantId: 'tenant-alpha', roleId: 'alpha-admin' });
  await expect(panel(page, 'Alpha admin')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No roles yet', exact: true })).toBeVisible();
});

test('does not let a default role for new registrations be deleted and says why', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin, userRole] }, challengeSettings: defaultRoleSettings } });

  await page.getByRole('button', { name: 'Actions for user' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy role ID']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const dialog = panel(page, 'Self-registered user');
  await expect(dialog.getByText('Granted to every self-registered account')).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Delete role user' })).toBeDisabled();
  await expect(dialog.getByRole('region', { name: 'Delete role' })).toContainText('change it in the sign-in settings first');

  await dialog.getByRole('button', { name: 'Open sign-in settings' }).click();
  await expect(page).toHaveURL(/view=challenges/);
  expect(api.requests.some(request => request.method === 'DELETE')).toBe(false);
});

test('read-only console: no create, no Edit, no danger card', async ({ page }) => {
  await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin] }, myPermissions: readOnly } });

  await expect(page.getByRole('button', { name: '+ Create Role' })).toHaveCount(0);
  await page.getByRole('button', { name: /^Open role (.* )?alpha-admin$/ }).click();
  const dialog = panel(page, 'Alpha admin');
  await expect(dialog.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(dialog.getByRole('region', { name: 'Delete role' })).toHaveCount(0);
});

test('below 720px the wide create panel is a full-screen sheet', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${rolesPath}&new=1`, state: { roles: { 'tenant-alpha': [alphaAdmin] }, permissions } });

  const dialog = panel(page, 'Create role');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expectSheetWidth(page, (await dialog.getByRole('dialog').boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test('creates an inactive role, then switches it on from the General group (patch carries only active)', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [] }, permissions } });

  await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
  const dialog = panel(page, 'Create role');
  await dialog.getByLabel('Role ID *').fill('night-shift');
  await dialog.locator('#role-description').fill('Night shift');
  await dialog.getByLabel('Active', { exact: true }).uncheck();
  await dialog.getByRole('button', { name: 'Create role', exact: true }).click();
  expect((await findRequest(api.requests, 'POST', '/configuration/roles')).body).toMatchObject({ id: 'night-shift', active: false });
  await expect(panel(page, 'Night shift').getByRole('region', { name: 'General' })).toContainText('No');

  await panel(page, 'Night shift').getByRole('button', { name: 'Edit general', exact: true }).click();
  await panel(page, 'Edit general').getByLabel('Active', { exact: true }).check();
  await panel(page, 'Edit general').getByRole('button', { name: 'Save general', exact: true }).click();
  const put = await findRequest(api.requests, 'PUT', '/configuration/roles');
  expect(put.body).toMatchObject({ tenantId: 'tenant-alpha', id: 'night-shift', active: true });
  await expect(panel(page, 'Night shift').getByRole('region', { name: 'General' })).toContainText('Yes');
});
