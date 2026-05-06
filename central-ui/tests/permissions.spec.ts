import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const permissionsPath = '/?view=permissions&tenant=tenant-alpha';
const emptyAcl = { kind: 'any', children: [] } as const;
const alphaResource = { id: 1, resource: 'https://alpha.example', endpoints: [{ id: 101, method: 'GET', path: '/alpha/items', fetchUserInfo: false, allowRules: emptyAcl, denyRules: emptyAcl, injectHeaders: {} }, { id: 102, method: 'POST', path: '/alpha/items', fetchUserInfo: false, allowRules: emptyAcl, denyRules: emptyAcl, injectHeaders: {} }] };
const reportsResource = { id: 2, resource: 'https://reports.example', endpoints: [{ id: 201, method: 'GET', path: '/reports', fetchUserInfo: false, allowRules: emptyAcl, denyRules: emptyAcl, injectHeaders: {} }] };
const alphaRead = { permission: 'alpha.read', description: { en: 'Read alpha resources' }, endpointIds: [101] };
const reportsRead = { permission: 'reports.read', description: { en: 'Read reports' }, endpointIds: [201] };

function permissionCard(page: Page, text: string) {
  return page.locator('.permission-card').filter({ hasText: text }).first();
}

test('renders permissions with endpoint bindings and filters by id', async ({ page }) => {
  await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead, reportsRead] }, resources: { 'tenant-alpha': [alphaResource, reportsResource] } } });

  const alpha = permissionCard(page, 'Read alpha resources');
  await alpha.locator('.permission-header').click();
  await expect(alpha).toContainText('alpha.read');
  await expect(alpha).toContainText('alpha.example');
  await expect(alpha).toContainText('/alpha/items');

  const search = page.getByLabel('Search permissions');
  await search.fill('reports.read');
  await expect(permissionCard(page, 'Read reports')).toBeVisible();
  await expect(page.locator('.permission-card').filter({ hasText: 'alpha.read' })).toHaveCount(0);

  await search.fill('missing-permission');
  await expect(page.getByRole('heading', { name: 'No permissions match your search', exact: true })).toBeVisible();
});

test('creates a permission bound to a selected resource endpoint', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [] }, resources: { 'tenant-alpha': [alphaResource] } } });

  await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
  await page.getByLabel('Permission ID').fill('alpha.write');
  await page.getByLabel('English description').fill('Write alpha resources');
  await page.getByLabel('Add resource').selectOption('1');
  await page.getByRole('button', { name: 'Add resource', exact: true }).click();
  const resourceCard = page.locator('.resource-card').filter({ hasText: 'alpha.example' }).first();
  await expect(resourceCard).toContainText('No endpoints added yet.');
  await resourceCard.getByLabel('Add endpoint for alpha.example').selectOption('102');
  await resourceCard.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  await page.getByRole('button', { name: 'Create Permission', exact: true }).click();

  const created = permissionCard(page, 'Write alpha resources');
  await expect(created).toContainText('alpha.write');
  await expect(created).toContainText('POST');
  expect(findRequest(api.requests, 'POST', '/v1/configuration/permissions').body).toEqual({ tenantId: 'tenant-alpha', permission: 'alpha.write', description: { en: 'Write alpha resources' }, endpointIds: [102] });
});

test('shows permission id validation with a red input border before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [] }, resources: { 'tenant-alpha': [alphaResource] } } });

  await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
  const permissionIdField = page.getByLabel('Permission ID');
  await permissionIdField.fill('Bad.Permission');
  await page.getByLabel('English description').fill('Broken permission');

  await expect(permissionIdField).toHaveClass(/input-error/);
  await expect(permissionIdField).toHaveCSS('border-top-color', 'rgb(248, 81, 73)');
  await page.getByRole('button', { name: 'Create Permission', exact: true }).click();

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/permissions')).toBeFalsy();
});

test('edits a permission and changes its endpoint binding', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead] }, resources: { 'tenant-alpha': [alphaResource] } } });

  await permissionCard(page, 'Read alpha resources').getByRole('button', { name: 'Edit permission alpha.read' }).click();
  await page.getByLabel('English description').fill('Update alpha resources');
  const resourceCard = page.locator('.resource-card').filter({ hasText: 'alpha.example' }).first();
  await resourceCard.getByRole('button', { name: 'Remove endpoint GET /alpha/items', exact: true }).click();
  await resourceCard.getByLabel('Add endpoint for alpha.example').selectOption('102');
  await resourceCard.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  await page.getByRole('button', { name: 'Update Permission', exact: true }).click();

  const updated = permissionCard(page, 'Update alpha resources');
  await expect(updated).toContainText('alpha.read');
  await expect(updated).toContainText('POST');
  expect(findRequest(api.requests, 'PUT', '/v1/configuration/permissions').body).toEqual({ tenantId: 'tenant-alpha', permission: 'alpha.read', description: { add: { en: 'Update alpha resources' }, delete: [] }, endpointIds: [102] });
});

test('deletes a permission through the shared confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead] }, resources: { 'tenant-alpha': [alphaResource] } } });

  await permissionCard(page, 'Read alpha resources').getByRole('button', { name: 'Delete permission alpha.read' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect(findRequest(api.requests, 'DELETE', '/v1/configuration/permissions').searchParams).toEqual({ tenantId: 'tenant-alpha', permission: 'alpha.read' });
  await expect(page.locator('.permission-card')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No permissions yet', exact: true })).toBeVisible();
});
