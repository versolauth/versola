import { expect, test } from '@playwright/test';
import { findRequest, loadAdminApp, openTenantDropdown, tenantSelectorButton } from './fixtures';

test('filters tenants, switches the active tenant, and persists it across reloads', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=clients' });

  await openTenantDropdown(page);
  await page.getByLabel('Search tenants').fill('bravo');

  const dropdown = page.locator('tenant-selector .dropdown');
  await expect(dropdown.getByRole('button', { name: 'tenant-bravo' })).toBeVisible();
  await expect(dropdown.getByRole('button', { name: 'tenant-alpha' })).toHaveCount(0);

  await dropdown.getByRole('button', { name: 'tenant-bravo' }).click();
  await expect(page).toHaveURL(/tenant=tenant-bravo/);
  await expect(tenantSelectorButton(page)).toContainText('tenant-bravo');
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem('selectedTenantId'))).toBe('tenant-bravo');

  await page.reload();
  await expect(tenantSelectorButton(page)).toContainText('tenant-bravo');
});

test('creates a tenant, selects it, and refreshes the selector choices', async ({ page }) => {
  const api = await loadAdminApp(page, { path: '/?view=tenants&tenant=tenant-alpha' });

  await page.getByRole('button', { name: '+ Create Tenant' }).click();
  await page.getByLabel('Tenant ID').fill('tenant-gamma');
  await page.getByLabel('Description').fill('Gamma Workspace');
  await page.getByRole('button', { name: 'Create Tenant', exact: true }).click();

  const createdCard = page.locator('.tenant-card').filter({ hasText: 'Gamma Workspace' }).first();
  await expect(createdCard).toBeVisible();
  await expect(createdCard.getByText('Selected')).toBeVisible();
  await expect(page).toHaveURL(/tenant=tenant-gamma/);
  await expect(tenantSelectorButton(page)).toContainText('tenant-gamma');
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem('selectedTenantId'))).toBe('tenant-gamma');
  expect(findRequest(api.requests, 'POST', '/v1/configuration/tenants').body).toEqual({
    id: 'tenant-gamma',
    description: 'Gamma Workspace',
    edgeId: null,
  });

  await openTenantDropdown(page);
  await expect(page.locator('tenant-selector .dropdown').getByRole('button', { name: 'tenant-gamma' })).toBeVisible();
});

test('shows tenant id validation before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: '/?view=tenants&tenant=tenant-alpha' });

  await page.getByRole('button', { name: '+ Create Tenant' }).click();
  const tenantIdField = page.getByLabel('Tenant ID');
  await tenantIdField.fill('Bad-tenant');
  await page.getByLabel('Description').fill('Broken Tenant');

  await expect(tenantIdField).toHaveClass(/input-error/);
  await expect(tenantIdField).toHaveCSS('border-top-color', 'rgb(248, 81, 73)');
  await expect(page.locator('.error-message')).toHaveCount(0);

  await page.getByRole('button', { name: 'Create Tenant', exact: true }).click();

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/tenants')).toBeFalsy();
});

test('edits a tenant description and keeps the id secondary on the card', async ({ page }) => {
  const api = await loadAdminApp(page, { path: '/?view=tenants&tenant=tenant-alpha' });

  await page.locator('button[aria-label="Edit tenant"]').first().click();
  await page.getByLabel('Description').fill('Alpha Platform');
  await page.getByRole('button', { name: 'Save Changes' }).click();

  const updatedCard = page.locator('.tenant-card').filter({ hasText: 'Alpha Platform' }).first();
  await expect(updatedCard).toContainText('Alpha Platform');
  await expect(updatedCard).toContainText('tenant-alpha');
  await expect(updatedCard.locator('.tenant-id')).toHaveCSS('color', 'rgb(88, 166, 255)');
  await expect(updatedCard.getByText('Selected')).toBeVisible();
  await expect(tenantSelectorButton(page)).toContainText('tenant-alpha');
  await openTenantDropdown(page);
  await expect(page.locator('tenant-selector .dropdown').getByRole('button', { name: 'tenant-alpha' })).toBeVisible();
  await expect(page.locator('tenant-selector .dropdown').getByText('Alpha Platform')).toHaveCount(0);
  expect(findRequest(api.requests, 'PUT', '/v1/configuration/tenants').body).toEqual({
    id: 'tenant-alpha',
    description: 'Alpha Platform',
    edgeId: null,
  });
});

test('deletes a tenant through the confirm dialog and removes it from the selector', async ({ page }) => {
  const api = await loadAdminApp(page, { path: '/?view=tenants&tenant=tenant-alpha' });

  const bravoCard = page.locator('.tenant-card').filter({ hasText: 'Bravo Workspace' }).first();
  await bravoCard.getByRole('button', { name: 'Delete tenant' }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Delete tenant');
  await dialog.getByRole('button', { name: 'Delete' }).click();

  await expect(page.locator('.tenant-card').filter({ hasText: 'Bravo Workspace' })).toHaveCount(0);
  expect(findRequest(api.requests, 'DELETE', '/v1/configuration/tenants').searchParams.tenantId).toBe('tenant-bravo');

  await openTenantDropdown(page);
  await expect(page.locator('tenant-selector .dropdown').getByRole('button', { name: 'tenant-bravo' })).toHaveCount(0);
});