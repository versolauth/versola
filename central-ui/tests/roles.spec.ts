import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const rolesPath = '/?view=roles&tenant=tenant-alpha';
const alphaRead = { permission: 'alpha.read', description: { en: 'Read alpha resources' }, endpointIds: [101] };
const reportsRead = { permission: 'reports.read', description: { en: 'Read reports' }, endpointIds: [201] };
const alphaAdmin = { id: 'alpha_admin', description: { en: 'Alpha admin' }, permissions: ['alpha.read'], active: true };
const supportTeam = { id: 'support_team', description: { en: 'Support team' }, permissions: ['reports.read'], active: false };

function roleCard(page: Page, text: string) {
  return page.locator('.role-card').filter({ hasText: text }).first();
}

test('renders roles, expands permissions, and filters by id', async ({ page }) => {
  await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin, supportTeam] } } });

  const alpha = roleCard(page, 'Alpha admin');
  await alpha.locator('.role-header').click();
  await expect(alpha).toContainText('alpha_admin');
  await expect(alpha).toContainText('alpha.read');
  await expect(roleCard(page, 'Support team')).toContainText('Inactive');

  const search = page.getByLabel('Search roles');
  await search.fill('support_team');
  await expect(roleCard(page, 'Support team')).toBeVisible();
  await expect(page.locator('.role-card').filter({ hasText: 'alpha_admin' })).toHaveCount(0);

  await search.fill('missing-role');
  await expect(page.getByRole('heading', { name: 'No roles match your search', exact: true })).toBeVisible();
});

test('creates a role with selected permissions', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [] }, permissions: { 'tenant-alpha': [alphaRead, reportsRead] } } });

  await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create New Role', exact: true })).toBeVisible();
  await page.getByLabel('Role ID *').fill('platform_admin');
  await page.getByLabel('Description *').fill('Platform admin');
  await page.getByRole('checkbox', { name: 'alpha.read', exact: true }).check();
  await page.getByRole('checkbox', { name: 'reports.read', exact: true }).check();
  await page.getByRole('button', { name: 'Create Role', exact: true }).click();

  const created = roleCard(page, 'Platform admin');
  await expect(created).toContainText('platform_admin');
  expect(findRequest(api.requests, 'POST', '/v1/configuration/roles').body).toEqual({ tenantId: 'tenant-alpha', id: 'platform_admin', description: { en: 'Platform admin' }, permissions: ['alpha.read', 'reports.read'] });
});

test('shows role validation with a red input border before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [] } } });

  await page.getByRole('button', { name: '+ Create Role', exact: true }).click();
  const roleIdField = page.getByLabel('Role ID *');
  await roleIdField.fill('Bad_role');
  await page.getByLabel('Description *').fill('Broken role');

  await expect(roleIdField).toHaveClass(/input-error/);
  await expect(roleIdField).toHaveCSS('border-top-color', 'rgb(248, 81, 73)');
  await page.getByRole('button', { name: 'Create Role', exact: true }).click();

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/roles')).toBeFalsy();
});

test('edits a role and sends patch-style permission changes', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin] }, permissions: { 'tenant-alpha': [alphaRead, reportsRead] } } });

  await roleCard(page, 'Alpha admin').getByRole('button', { name: 'Edit role alpha_admin' }).click();
  await page.getByLabel('Description *').fill('Platform admins');
  await page.getByRole('checkbox', { name: 'alpha.read', exact: true }).uncheck();
  await page.getByRole('checkbox', { name: 'reports.read', exact: true }).check();
  await page.getByRole('button', { name: 'Update Role', exact: true }).click();

  const updated = roleCard(page, 'Platform admins');
  await expect(updated).toContainText('alpha_admin');
  await updated.locator('.role-header').click();
  await expect(updated).toContainText('reports.read');
  expect(findRequest(api.requests, 'PUT', '/v1/configuration/roles').body).toEqual({ tenantId: 'tenant-alpha', id: 'alpha_admin', description: { add: { en: 'Platform admins' }, delete: [] }, permissions: { add: ['reports.read'], remove: ['alpha.read'] } });
});

test('deletes a role through the shared confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, { path: rolesPath, state: { roles: { 'tenant-alpha': [alphaAdmin] } } });

  await roleCard(page, 'Alpha admin').getByRole('button', { name: 'Delete role alpha_admin' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect(findRequest(api.requests, 'DELETE', '/v1/configuration/roles').searchParams).toEqual({ tenantId: 'tenant-alpha', roleId: 'alpha_admin' });
  await expect(page.locator('.role-card')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No roles yet', exact: true })).toBeVisible();
});
