import { expect, test } from '@playwright/test';
import { INVALID_FIELD_BORDER, findRequest, loadAdminApp, sidePanel, sidePanelDialog, expectSheetWidth } from './fixtures';

/**
 * Permissions on the table + side panel pattern: the list with resource chips and detached
 * counts, the panel with endpoints per resource and who grants it, create with the endpoint
 * picker, the group editors, delete, the read-only console and the sheet below 720px.
 */
const permissionsPath = '/?view=permissions&tenant=tenant-alpha';
const alphaResource = { resourceId: 'alpha', resource: 'https://alpha.example', endpoints: [
  { id: 101, method: 'GET', path: '/alpha/items', fetchUserInfo: false, allow: 'true', inject: [] },
  { id: 102, method: 'POST', path: '/alpha/items', fetchUserInfo: false, allow: 'true', inject: [] },
] };
const reportsResource = { resourceId: 'reports', resource: 'https://reports.example', endpoints: [{ id: 201, method: 'GET', path: '/reports', fetchUserInfo: false, allow: 'true', inject: [] }] };
const alphaRead = { permission: 'alpha:read', description: { en: 'Read alpha resources' }, endpointIds: [101] };
const reportsRead = { permission: 'reports:read', description: { en: 'Read reports' }, endpointIds: [201, 999] };
const auditRead = { permission: 'audit:read', description: { en: 'Read the audit log' }, endpointIds: [] };
const resources = { 'tenant-alpha': [alphaResource, reportsResource] };
const readOnly = { resources: { central: { permissions: ['access:read'] } }, isProd: false };

const panel = sidePanel;

test('lists permissions with resources and endpoint values, filters by resource and state, searches', async ({ page }) => {
  await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead, reportsRead, auditRead] }, resources } });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Permission', 'Resources', 'Endpoints', 'Actions']);
  const alphaRow = table.getByRole('row').filter({ hasText: 'Read alpha resources' });
  await expect(alphaRow).toContainText('alpha.example');
  await expect(alphaRow.getByRole('cell').nth(2)).toHaveText('GET /alpha/items');
  await expect(table.getByRole('row').filter({ hasText: 'Read reports' })).toContainText('Detached');
  await expect(table.getByRole('row').filter({ hasText: 'Read reports' }).getByRole('cell').nth(2)).toContainText('GET /reports');
  await expect(table.getByRole('row').filter({ hasText: 'Read the audit log' }).getByRole('cell').nth(2)).toHaveText('—');

  // The resources are a typeahead, not a chip per resource; All, Unlinked and Detached stay chips.
  await expect(page.getByRole('button', { name: /^reports\.example/ })).toHaveCount(0);
  const resource = page.getByRole('combobox', { name: 'Resource' });
  await resource.click();
  await expect(page.getByRole('listbox', { name: 'Resource' }).getByRole('option')).toHaveText(['alpha.example', 'reports.example']);
  await resource.fill('rep');
  await expect(page.getByRole('listbox', { name: 'Resource' }).getByRole('option')).toHaveText(['reports.example']);
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect(resource).toHaveValue('reports.example');
  await expect(page.getByRole('listbox', { name: 'Resource' })).toHaveCount(0);
  await expect(table.getByRole('button', { name: /^Open permission/ })).toHaveText([/reports:read/]);
  await expect(page.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await page.getByRole('button', { name: 'Clear resource' }).click();
  await expect(resource).toHaveValue('');
  await expect(table.getByRole('button', { name: /^Open permission/ })).toHaveCount(3);

  await page.getByRole('button', { name: 'Unlinked', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open permission/ })).toHaveText([/audit:read/]);
  await page.getByRole('button', { name: 'Detached', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open permission/ })).toHaveText([/reports:read/]);
  await page.getByRole('button', { name: 'All', exact: true }).click();

  await page.getByLabel('Search permissions').fill('audit');
  await expect(table.getByRole('button', { name: /^Open permission/ })).toHaveCount(1);
  await page.getByLabel('Search permissions').fill('missing');
  await expect(page.getByRole('heading', { name: 'No permissions match' })).toBeVisible();
});

test('the resource typeahead is keyboard operable and shows a message when nothing matches', async ({ page }) => {
  await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead, reportsRead, auditRead] }, resources } });

  const resource = page.getByRole('combobox', { name: 'Resource' });
  await resource.focus();
  await expect(resource).toHaveAttribute('aria-expanded', 'true');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Escape');
  await expect(resource).toHaveAttribute('aria-expanded', 'false');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect(resource).toHaveValue('reports.example');
  await resource.fill('zzz');
  await expect(page.getByText('No resource matches')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(resource).toHaveValue('reports.example');
});

test('shows at most two resources and two endpoints in a row, the rest in the +N title', async ({ page }) => {
  const many = { permission: 'big:read', description: { en: 'Read everything' }, endpointIds: [101, 102, 201] };
  const third = { resourceId: 'third', resource: 'https://third.example', endpoints: [{ id: 301, method: 'GET', path: '/third', fetchUserInfo: false, allow: 'true', inject: [] }] };
  await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [{ ...many, endpointIds: [101, 102, 201, 301] }] }, resources: { 'tenant-alpha': [alphaResource, reportsResource, third] } } });

  const row = page.locator('versola-data-table').getByRole('row').filter({ hasText: 'Read everything' });
  const resourcesCell = row.getByRole('cell').nth(1);
  await expect(resourcesCell.locator('.chip.value')).toHaveCount(2);
  await expect(resourcesCell.locator('.chip.more')).toHaveText('+1');
  await expect(resourcesCell.locator('.chip.more')).toHaveAttribute('title', 'third.example');
  const endpointsCell = row.getByRole('cell').nth(2);
  await expect(endpointsCell.locator('.chip.value')).toHaveCount(2);
  await expect(endpointsCell.locator('.chip.more')).toHaveText('+2');
  await expect(endpointsCell.locator('.chip.more')).toHaveAttribute('title', 'GET /reports, GET /third');
});

test('refuses a dotted permission ID: only the colon separates segments', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead] }, resources } });

  await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
  const dialog = panel(page, 'Create permission');
  const id = dialog.getByLabel('Permission ID *');
  await expect(dialog.getByText('Dots are not allowed.')).toBeVisible();
  await id.fill('alpha.write');
  await expect(id).toHaveAttribute('aria-invalid', 'true');
  await expect(dialog.getByRole('alert').filter({ hasText: 'Dots are not allowed.' })).toBeVisible();
  await dialog.locator('#permission-description').fill('Write alpha resources');
  await dialog.getByRole('button', { name: 'Create permission', exact: true }).click();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/permissions')).toBeFalsy();
  await id.fill('alpha:write:own');
  await expect(id).not.toHaveAttribute('aria-invalid', 'true');
});

test('opens a permission and shows its endpoints, who grants it, the detached banner and Fixed', async ({ page }) => {
  await loadAdminApp(page, {
    path: permissionsPath,
    state: {
      permissions: { 'tenant-alpha': [reportsRead] },
      resources,
      roles: { 'tenant-alpha': [{ id: 'auditor', description: { en: 'Auditor' }, permissions: ['reports:read'], active: true }] },
      clients: { 'tenant-alpha': [{ id: 'reporting-job', clientName: { en: 'Reporting job' }, redirectUris: [], scope: [], permissions: ['reports:read'], secretRotation: false }] },
    },
  });

  await page.getByRole('button', { name: /^Open permission (.* )?reports:read$/ }).click();
  const dialog = panel(page, 'Read reports');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expect(dialog.getByText('1 linked endpoint no longer exists in Resources')).toBeVisible();
  const endpoints = dialog.getByRole('region', { name: 'Endpoints' });
  await expect(endpoints).toContainText('reports.example');
  await expect(endpoints).toContainText('GET');
  await expect(endpoints).toContainText('/reports');
  await expect(endpoints).toContainText('#999');
  const grantedBy = dialog.getByRole('region', { name: 'Granted by' });
  await expect(grantedBy).toContainText('auditor');
  await expect(grantedBy).toContainText('reporting-job');
  await expect(dialog.getByRole('region', { name: 'Fixed' })).toContainText('roles and tokens carry this value');

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Open permission (.* )?reports:read$/ })).toBeFocused();
});

test('a permission whose ID has a dot is flagged as a legacy ID, read-only except Delete', async ({ page }) => {
  const legacy = { permission: 'alpha.write', description: { en: 'Write alpha resources' }, endpointIds: [101] };
  await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [legacy, alphaRead] }, resources } });

  const row = page.locator('versola-data-table').getByRole('row').filter({ hasText: 'Write alpha resources' });
  await expect(row.locator('.chip.warn')).toHaveText('Legacy ID');
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'Read alpha resources' }).locator('.chip.warn')).toHaveCount(0);

  await page.getByRole('button', { name: /^Open permission (.* )?alpha\.write$/ }).click();
  const dialog = panel(page, 'Write alpha resources');
  await expect(dialog.getByText('This ID uses dots, which are no longer allowed. Recreate the permission with a colon ID (orders:read) and move its roles and clients.')).toBeVisible();
  await expect(dialog.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Delete permission alpha.write' })).toBeVisible();
});

for (const width of [1440, 375]) {
  test(`a long resource host, method and path stay inside the panel's endpoints group at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const longResource = { resourceId: 'long', resource: 'https://payments-ledger-internal.eu-central-1.prod.example.com:8443', endpoints: [
      { id: 401, method: 'DELETE', path: '/v2/organizations/{organizationId}/ledgers/{ledgerId}/entries/{entryId}/attachments', fetchUserInfo: false, allow: 'true', inject: [] },
      { id: 402, method: 'GET', path: '/v2/ledgers', fetchUserInfo: false, allow: 'true', inject: [] },
    ] };
    const perm = { permission: 'ledger:manage', description: { en: 'Manage ledgers' }, endpointIds: [401, 402] };
    await loadAdminApp(page, { path: `${permissionsPath}&id=ledger:manage`, state: { permissions: { 'tenant-alpha': [perm] }, resources: { 'tenant-alpha': [longResource] } } });

    const dialog = panel(page, 'Manage ledgers');
    const endpoints = dialog.getByRole('region', { name: 'Endpoints' });
    const host = endpoints.locator('.endpoint-host');
    await expect(host).toHaveAttribute('title', /payments-ledger-internal/);
    const rows = endpoints.locator('.endpoint');
    await expect(rows).toHaveCount(2);
    const fits = await endpoints.evaluate(card => {
      const outer = card.getBoundingClientRect();
      return Array.from(card.querySelectorAll('.endpoint-host, .endpoint, .method-badge, .endpoint-path'))
        .every(node => { const box = node.getBoundingClientRect(); return box.left >= outer.left - 0.5 && box.right <= outer.right + 0.5; });
    });
    expect(fits).toBe(true);
    // The badge and the path do not overlap: side by side, or the path under the badge.
    const first = rows.first();
    const badge = await first.locator('.method-badge').boundingBox();
    const path = await first.locator('.endpoint-path').boundingBox();
    expect(badge!.x + badge!.width <= path!.x + 1 || path!.y >= badge!.y + badge!.height - 1).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
  });
}

test('the list row is not selected while a group editor is open, and is again after it closes', async ({ page }) => {
  await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead] }, resources } });

  const row = page.locator('versola-data-table').getByRole('row').filter({ hasText: 'Read alpha resources' });
  await page.getByRole('button', { name: /^Open permission (.* )?alpha:read$/ }).click();
  await expect(row).toHaveAttribute('aria-current', 'true');
  await panel(page, 'Read alpha resources').getByRole('button', { name: 'Edit endpoints' }).click();
  await expect(panel(page, 'Edit endpoints').getByRole('dialog')).toBeVisible();
  await expect(row).not.toHaveAttribute('aria-current', 'true');
  await panel(page, 'Edit endpoints').getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(row).toHaveAttribute('aria-current', 'true');
});

test('creates a permission bound to a selected resource endpoint', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [] }, resources } });

  await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
  const dialog = panel(page, 'Create permission');
  await dialog.getByLabel('Permission ID *').fill('alpha:write');
  await dialog.locator('#permission-description').fill('Write alpha resources');
  await dialog.getByLabel('Add resource').selectOption('alpha');
  await dialog.getByRole('button', { name: 'Add resource', exact: true }).click();
  await expect(dialog.getByText('No endpoints added yet.')).toBeVisible();
  await dialog.getByLabel('Add endpoint for alpha.example').selectOption('102');
  await dialog.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  await expect(dialog.locator('.endpoint-path', { hasText: '/alpha/items' })).toBeVisible();
  await dialog.getByRole('button', { name: 'Create permission', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/permissions')).body).toEqual({
    tenantId: 'tenant-alpha', permission: 'alpha:write', description: { en: 'Write alpha resources' }, endpointIds: [102],
  });
  await expect(panel(page, 'Write alpha resources').getByRole('region', { name: 'Endpoints' })).toContainText('POST');
});

test('refuses an invalid permission ID with a red border before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [] }, resources } });

  await page.getByRole('button', { name: '+ Create Permission', exact: true }).click();
  const id = panel(page, 'Create permission').getByLabel('Permission ID *');
  await id.fill('Bad.Permission');
  await expect(id).toHaveAttribute('aria-invalid', 'true');
  await expect(id).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await panel(page, 'Create permission').getByRole('button', { name: 'Create permission', exact: true }).click();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/permissions')).toBeFalsy();
});

test('edits the endpoints group and sends the full endpoint list', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead] }, resources } });

  await page.getByRole('button', { name: /^Open permission (.* )?alpha:read$/ }).click();
  await panel(page, 'Read alpha resources').getByRole('button', { name: 'Edit endpoints' }).click();
  const editor = panel(page, 'Edit endpoints');
  await editor.getByRole('button', { name: 'Remove endpoint GET /alpha/items' }).click();
  await editor.getByLabel('Add endpoint for alpha.example').selectOption('102');
  await editor.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  await editor.getByRole('button', { name: 'Save endpoints' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/permissions')).body).toEqual({
    tenantId: 'tenant-alpha', permission: 'alpha:read', description: { add: {}, delete: [] }, endpointIds: [102],
  });
  await expect(panel(page, 'Read alpha resources').getByRole('region', { name: 'Endpoints' })).toContainText('POST');
});

test('edits the general group and sends only the description patch', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead] }, resources } });

  await page.getByRole('button', { name: /^Open permission (.* )?alpha:read$/ }).click();
  await panel(page, 'Read alpha resources').getByRole('button', { name: 'Edit general' }).click();
  await panel(page, 'Edit general').locator('#permission-description').fill('Update alpha resources');
  await panel(page, 'Edit general').getByRole('button', { name: 'Save general' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/permissions')).body).toEqual({
    tenantId: 'tenant-alpha', permission: 'alpha:read', description: { add: { en: 'Update alpha resources' }, delete: [] },
  });
  await expect(page.locator('versola-data-table')).toContainText('Update alpha resources');
});

test('deletes a permission from the panel through the confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead] }, resources } });

  await page.getByRole('button', { name: /^Open permission (.* )?alpha:read$/ }).click();
  await panel(page, 'Read alpha resources').getByRole('button', { name: 'Delete permission alpha:read' }).click();
  await page.getByRole('dialog', { name: 'Delete permission' }).getByRole('button', { name: 'Delete', exact: true }).click();

  expect((await findRequest(api.requests, 'DELETE', '/configuration/permissions')).searchParams).toEqual({ tenantId: 'tenant-alpha', permission: 'alpha:read' });
  await expect(panel(page, 'Read alpha resources')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No permissions yet', exact: true })).toBeVisible();
});

test('read-only console: no create, no Edit, no danger card, row menu without Delete', async ({ page }) => {
  await loadAdminApp(page, { path: permissionsPath, state: { permissions: { 'tenant-alpha': [alphaRead] }, resources, myPermissions: readOnly } });

  await expect(page.getByRole('button', { name: '+ Create Permission' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Actions for alpha:read' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy permission ID']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const dialog = panel(page, 'Read alpha resources');
  await expect(dialog.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(dialog.getByRole('region', { name: 'Delete permission' })).toHaveCount(0);
});

test('below 720px the panel is a full-screen sheet', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${permissionsPath}&id=alpha:read`, state: { permissions: { 'tenant-alpha': [alphaRead] }, resources } });

  const dialog = panel(page, 'Read alpha resources');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expectSheetWidth(page, (await dialog.getByRole('dialog').boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});
