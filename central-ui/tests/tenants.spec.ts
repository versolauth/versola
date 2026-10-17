import { expect, test } from '@playwright/test';
import { INVALID_FIELD_BORDER, findRequest, loadAdminApp, openTenantDropdown, tenantSelectorButton, sidePanel, sidePanelDialog, expectSheetWidth } from './fixtures';
import { defaultChallengeSettings } from './mocks';

/**
 * Tenants on the table + side panel pattern, plus the tenant selector it feeds: the list with
 * the selected tenant first, the panel with the edge and the security profile read from the
 * challenge settings, create (selects the new tenant), the group editors, delete, the
 * read-only console and the sheet below 720px.
 */
const tenantsPath = '/?view=tenants&tenant=tenant-alpha';
const edges = [{ id: 'edge-alpha', hasOldKey: false, requireDpopNonce: true }];
const readOnly = { resources: { central: { permissions: ['tenants:read'] } }, isProd: false };

const panel = sidePanel;

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

test('lists tenants with the selected one first, filters by edge and searches', async ({ page }) => {
  await loadAdminApp(page, {
    path: '/?view=tenants&tenant=tenant-bravo',
    state: { tenants: [{ id: 'tenant-alpha', description: 'Alpha Workspace', edgeId: 'edge-alpha' }, { id: 'tenant-bravo', description: 'Bravo Workspace', edgeId: null }], edges },
  });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Tenant', 'Edge', 'Actions']);
  await expect(table.getByRole('button', { name: /^Open tenant/ })).toHaveText([/tenant-bravo/, /tenant-alpha/]);
  await expect(table.getByRole('row').filter({ hasText: 'Bravo Workspace' })).toContainText('Selected');
  await expect(table.getByRole('row').filter({ hasText: 'Bravo Workspace' })).toContainText('Not assigned');
  await expect(table.getByRole('row').filter({ hasText: 'Alpha Workspace' })).toContainText('edge-alpha');

  await page.getByRole('button', { name: /^On an edge\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open tenant/ })).toHaveText([/tenant-alpha/]);
  await page.getByRole('button', { name: /^All\s?2$/ }).click();
  await page.getByLabel('Search tenant list').fill('Bravo');
  await expect(table.getByRole('button', { name: /^Open tenant/ })).toHaveCount(1);
});

test('opens a tenant, reads its security profile from the challenge settings and warns without an edge', async ({ page }) => {
  await loadAdminApp(page, {
    path: tenantsPath,
    state: { challengeSettings: { 'tenant-bravo': { ...defaultChallengeSettings('tenant-bravo'), securityProfile: 'standard' } } },
  });

  await page.getByRole('button', { name: 'Open tenant tenant-bravo' }).click();
  const dialog = panel(page, 'Bravo Workspace');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expect(page).toHaveURL(/id=tenant-bravo/);
  await expect(dialog.getByText('Not assigned to an edge')).toBeVisible();
  await expect(dialog.getByRole('region', { name: 'Edge' })).toContainText('None (Central only)');
  const fixed = dialog.getByRole('region', { name: 'Fixed' });
  await expect(fixed).toContainText('Standard');
  await expect(fixed).toContainText('Chosen at creation; cannot be changed');
  await expect(dialog.locator('.chip', { hasText: 'Standard' })).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Open tenant tenant-bravo' })).toBeFocused();
});

test('creates a tenant in the panel, selects it, opens it and refreshes the selector', async ({ page }) => {
  const api = await loadAdminApp(page, { path: tenantsPath, state: { edges } });

  await page.getByRole('button', { name: '+ Create Tenant' }).click();
  const dialog = panel(page, 'Create tenant');
  await dialog.getByLabel('Tenant ID *').fill('tenant-gamma');
  await dialog.getByLabel('Description *').fill('Gamma Workspace');
  // Clients are held to FAPI 2.0 unless the tenant is created as a standard one.
  await expect(dialog.getByRole('button', { name: /FAPI 2.0 \(recommended\)/ })).toHaveAttribute('aria-pressed', 'true');
  await dialog.getByLabel('Edge (Optional)').selectOption('edge-alpha');
  await dialog.getByRole('button', { name: 'Create tenant', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/tenants')).body).toEqual({
    id: 'tenant-gamma', description: 'Gamma Workspace', edgeId: 'edge-alpha', securityProfile: 'fapi2',
  });
  const created = panel(page, 'Gamma Workspace');
  await expect(created.getByRole('dialog')).toBeVisible();
  await expect(created.locator('.chip', { hasText: 'Selected' })).toBeVisible();
  await expect(created.locator('.chip', { hasText: 'FAPI 2.0' })).toBeVisible();
  await expect(page).toHaveURL(/tenant=tenant-gamma/);
  await expect(tenantSelectorButton(page)).toContainText('tenant-gamma');
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem('selectedTenantId'))).toBe('tenant-gamma');

  await created.getByRole('button', { name: 'Close panel' }).click();
  await openTenantDropdown(page);
  await expect(page.locator('tenant-selector .dropdown').getByRole('button', { name: 'tenant-gamma' })).toBeVisible();
});

test('creates a standard tenant; the profile is Fixed afterwards', async ({ page }) => {
  const api = await loadAdminApp(page, { path: tenantsPath });

  await page.getByRole('button', { name: '+ Create Tenant' }).click();
  const dialog = panel(page, 'Create tenant');
  await dialog.getByLabel('Tenant ID *').fill('tenant-delta');
  await dialog.getByLabel('Description *').fill('Delta Workspace');
  await dialog.getByRole('button', { name: /^Standard/ }).click();
  await dialog.getByRole('button', { name: 'Create tenant', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/tenants')).body).toEqual({
    id: 'tenant-delta', description: 'Delta Workspace', edgeId: null, securityProfile: 'standard',
  });
  const created = panel(page, 'Delta Workspace');
  await expect(created.getByRole('region', { name: 'Fixed' })).toContainText('Standard');
  await created.getByRole('button', { name: 'Edit general' }).click();
  await expect(panel(page, 'Edit general').getByRole('group', { name: 'Security profile' })).toHaveCount(0);
});

test('refuses an invalid tenant ID before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: tenantsPath });

  await page.getByRole('button', { name: '+ Create Tenant' }).click();
  const dialog = panel(page, 'Create tenant');
  const id = dialog.getByLabel('Tenant ID *');
  await id.fill('Bad-tenant');
  await expect(id).toHaveAttribute('aria-invalid', 'true');
  await expect(id).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await dialog.getByLabel('Description *').fill('Broken Tenant');
  await dialog.getByRole('button', { name: 'Create tenant', exact: true }).click();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/tenants')).toBeFalsy();
});

test('edits the general group and keeps the edge; edits the edge and keeps the description', async ({ page }) => {
  const api = await loadAdminApp(page, { path: tenantsPath, state: { tenants: [{ id: 'tenant-alpha', description: 'Alpha Workspace', edgeId: 'edge-alpha' }], edges } });

  await page.getByRole('button', { name: 'Open tenant tenant-alpha' }).click();
  await panel(page, 'Alpha Workspace').getByRole('button', { name: 'Edit general' }).click();
  await panel(page, 'Edit general').getByLabel('Description *').fill('Alpha Platform');
  await panel(page, 'Edit general').getByRole('button', { name: 'Save general' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/tenants')).body).toEqual({ id: 'tenant-alpha', description: 'Alpha Platform', edgeId: 'edge-alpha' });
  await expect(page.locator('versola-data-table')).toContainText('Alpha Platform');

  await panel(page, 'Alpha Platform').getByRole('button', { name: 'Edit edge' }).click();
  await panel(page, 'Edit edge').getByLabel('Edge (Optional)').selectOption('');
  await panel(page, 'Edit edge').getByRole('button', { name: 'Save edge' }).click();
  await expect.poll(() => api.requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/tenants').length).toBe(2);
  expect((await findRequest(api.requests, 'PUT', '/configuration/tenants')).body).toEqual({ id: 'tenant-alpha', description: 'Alpha Platform', edgeId: null });
  await expect(panel(page, 'Alpha Platform').getByText('Not assigned to an edge')).toBeVisible();
});

test('selects a tenant from the row menu', async ({ page }) => {
  await loadAdminApp(page, { path: tenantsPath });

  await page.getByRole('button', { name: 'Actions for tenant-bravo' }).click();
  await page.getByRole('menuitem', { name: 'Select tenant tenant-bravo' }).click();
  await expect(page).toHaveURL(/tenant=tenant-bravo/);
  await expect(tenantSelectorButton(page)).toContainText('tenant-bravo');
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open tenant/ })).toHaveText([/tenant-bravo/, /tenant-alpha/]);
});

test('deletes a tenant from the panel through the confirm dialog and removes it from the selector', async ({ page }) => {
  const api = await loadAdminApp(page, { path: tenantsPath });

  await page.getByRole('button', { name: 'Open tenant tenant-bravo' }).click();
  await panel(page, 'Bravo Workspace').getByRole('button', { name: 'Delete tenant tenant-bravo' }).click();
  const confirm = page.getByRole('dialog', { name: 'Delete tenant' });
  await expect(confirm).toContainText('Delete tenant');
  await confirm.getByRole('button', { name: 'Delete', exact: true }).click();

  expect((await findRequest(api.requests, 'DELETE', '/configuration/tenants')).searchParams.tenantId).toBe('tenant-bravo');
  await expect(panel(page, 'Bravo Workspace')).toHaveCount(0);
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open tenant/ })).toHaveCount(1);
  await openTenantDropdown(page);
  await expect(page.locator('tenant-selector .dropdown').getByRole('button', { name: 'tenant-bravo' })).toHaveCount(0);
});

test('read-only console: no create, no Edit, no danger card', async ({ page }) => {
  await loadAdminApp(page, { path: tenantsPath, state: { myPermissions: readOnly } });

  await expect(page.getByRole('button', { name: '+ Create Tenant' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Actions for tenant-bravo' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy tenant ID', 'Select tenant']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const dialog = panel(page, 'Bravo Workspace');
  await expect(dialog.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Assign an edge' })).toHaveCount(0);
  await expect(dialog.getByRole('region', { name: 'Delete tenant' })).toHaveCount(0);
});

test('below 720px the panel is a full-screen sheet', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${tenantsPath}&id=tenant-alpha` });

  const dialog = panel(page, 'Alpha Workspace');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expectSheetWidth(page, (await dialog.getByRole('dialog').boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});
