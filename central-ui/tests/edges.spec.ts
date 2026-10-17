import { expect, test } from '@playwright/test';
import { findRequest, loadAdminApp, sidePanel, sidePanelDialog } from './fixtures';

/**
 * Edges on the table + side panel pattern: the list with the key and DPoP nonce state,
 * registering an edge in the panel (which lands on the new record with the one-time private
 * key banner), rotating and retiring keys, the DPoP nonce editor, delete, the read-only
 * console and the sheet below 720px.
 */
const edgesPath = '/?view=edges';
const alphaEdge = { id: 'edge-alpha', hasOldKey: false, requireDpopNonce: true };
const bravoEdge = { id: 'edge-bravo', hasOldKey: true, requireDpopNonce: false };
const tenants = [{ id: 'tenant-alpha', description: 'Alpha Workspace', edgeId: 'edge-alpha' }, { id: 'tenant-bravo', description: 'Bravo Workspace', edgeId: null }];
const readOnly = { resources: { central: { permissions: ['edges:read', 'tenants:read'] } }, isProd: false };

const panel = sidePanel;

test('lists edges with their tenants, key state and DPoP nonce, filters and searches', async ({ page }) => {
  await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge, bravoEdge], tenants } });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Edge', 'Tenants', 'Key', 'DPoP nonce', 'Actions']);
  const alpha = table.getByRole('row').filter({ hasText: 'edge-alpha' });
  await expect(alpha).toContainText('tenant-alpha');
  await expect(alpha).toContainText('Key current');
  await expect(alpha).toContainText('Required');
  const bravo = table.getByRole('row').filter({ hasText: 'edge-bravo' });
  await expect(bravo).toContainText('Key rotation');
  await expect(bravo).toContainText('Not required');

  await page.getByRole('button', { name: /^Key rotation\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open edge/ })).toHaveText([/edge-bravo/]);
  await page.getByRole('button', { name: /^No tenants\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open edge/ })).toHaveText([/edge-bravo/]);
  await page.getByRole('button', { name: /^All\s?2$/ }).click();
  await page.getByLabel('Search edges').fill('alpha');
  await expect(table.getByRole('button', { name: /^Open edge/ })).toHaveCount(1);
});

test('opens an edge and shows its key, DPoP nonce, linked tenants and Fixed', async ({ page }) => {
  await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge, bravoEdge], tenants } });

  await page.getByRole('button', { name: 'Open edge edge-bravo' }).click();
  const dialog = panel(page, 'edge-bravo');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expect(page).toHaveURL(/id=edge-bravo/);
  await expect(dialog.getByText('A key rotation is open: both keys verify')).toBeVisible();
  await expect(dialog.getByRole('region', { name: 'Signing key' })).toContainText('Rotation open');
  await expect(dialog.getByRole('button', { name: 'Retire old key' })).toBeVisible();
  await expect(dialog.getByRole('region', { name: 'DPoP nonce' })).toContainText('Not required');
  await expect(dialog.getByRole('region', { name: 'Linked tenants' })).toContainText('No tenants assigned to this edge.');
  await expect(dialog.getByRole('region', { name: 'Fixed' })).toContainText('Set at registration');

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Open edge edge-bravo' })).toBeFocused();

  await page.getByRole('button', { name: 'Open edge edge-alpha' }).click();
  await expect(panel(page, 'edge-alpha').getByRole('region', { name: 'Linked tenants' })).toContainText('tenant-alpha');
});

test('registers an edge in the panel and lands on it with the one-time private key banner', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [] } });

  await page.getByRole('button', { name: '+ Create Edge' }).click();
  const dialog = panel(page, 'Register edge');
  await dialog.getByLabel('Edge ID *').fill('new');
  await expect(dialog.getByText('Registered as edge-new')).toBeVisible();
  await dialog.getByRole('button', { name: 'Register edge', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/edges')).body).toEqual({ id: 'edge-new' });
  const record = panel(page, 'edge-new');
  await expect(record.getByText('Edge key generated')).toBeVisible();
  await expect(record.locator('.secret-value')).toHaveCount(3);
  await expect(record.getByRole('button', { name: 'Copy private key' })).toBeVisible();
  await expect(page.locator('versola-data-table')).toContainText('edge-new');

  await record.getByRole('button', { name: "I've saved it" }).click();
  await expect(record.getByText('Edge key generated')).toHaveCount(0);
});

test('refuses an invalid or duplicate edge ID before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge] } });

  await page.getByRole('button', { name: '+ Create Edge' }).click();
  const dialog = panel(page, 'Register edge');
  const id = dialog.getByLabel('Edge ID *');
  await id.fill('Bad-ID');
  await expect(id).toHaveAttribute('aria-invalid', 'true');
  await expect(dialog.getByText('Lowercase letters, digits and dashes.')).toBeVisible();
  await id.fill('alpha');
  await expect(dialog.getByText('An edge with this ID already exists.')).toBeVisible();
  await dialog.getByRole('button', { name: 'Register edge', exact: true }).click();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/edges')).toBeFalsy();
});

// RFC 9449 section 9 is per edge and lives in central, so the console is where it is turned
// off -- not in the edge's own config file, which no longer carries it.
test('turns off the DPoP nonce requirement in the group editor and reflects it after the save', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge] } });

  await page.getByRole('button', { name: 'Open edge edge-alpha' }).click();
  await panel(page, 'edge-alpha').getByRole('button', { name: 'Edit dpop nonce' }).click();
  const editor = panel(page, 'Edit DPoP nonce');
  await expect(page).toHaveURL(/edit=dpop/);
  await expect(editor).toContainText('use_dpop_nonce');
  await expect(editor).toContainText('Governs this edge only');
  const toggle = editor.getByRole('checkbox', { name: 'Required on every proxied call' });
  await expect(toggle).toBeChecked();
  await toggle.uncheck();
  await editor.getByRole('button', { name: 'Save DPoP nonce' }).click();

  const request = await findRequest(api.requests, 'PUT', '/configuration/edges/dpop');
  expect(request.searchParams).toEqual({ edgeId: 'edge-alpha' });
  expect(request.body).toEqual({ requireDpopNonce: false });
  await expect(panel(page, 'edge-alpha').getByRole('region', { name: 'DPoP nonce' })).toContainText('Not required');
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'edge-alpha' })).toContainText('Not required');
});

test('rotates the key, shows the new private key, then retires the old key', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge] } });

  await page.getByRole('button', { name: 'Open edge edge-alpha' }).click();
  const record = panel(page, 'edge-alpha');
  await record.getByRole('button', { name: 'Rotate key', exact: true }).click();
  await page.getByRole('dialog', { name: 'Rotate edge key' }).getByRole('button', { name: 'Rotate key', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/edges/rotate-key')).searchParams).toEqual({ edgeId: 'edge-alpha' });
  await expect(record.getByText('Edge key rotated')).toBeVisible();
  await expect(record.getByText('A key rotation is open: both keys verify')).toBeVisible();
  await expect(record.locator('.chip', { hasText: 'Key rotation' })).toBeVisible();

  await record.getByRole('button', { name: 'Retire old key' }).click();
  await page.getByRole('dialog', { name: 'Retire old key' }).getByRole('button', { name: 'Retire old key' }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/edges/old-key')).searchParams).toEqual({ edgeId: 'edge-alpha' });
  await expect(record.locator('.chip', { hasText: 'Key current' })).toBeVisible();
  await expect(record.getByRole('button', { name: 'Retire old key' })).toHaveCount(0);
});

test('deletes an edge from the panel through the confirm dialog and reaches the empty state', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [bravoEdge] } });

  await page.getByRole('button', { name: 'Open edge edge-bravo' }).click();
  await panel(page, 'edge-bravo').getByRole('button', { name: 'Delete edge edge-bravo' }).click();
  await page.getByRole('dialog', { name: 'Delete edge' }).getByRole('button', { name: 'Delete', exact: true }).click();

  expect((await findRequest(api.requests, 'DELETE', '/configuration/edges')).searchParams).toEqual({ edgeId: 'edge-bravo' });
  await expect(panel(page, 'edge-bravo')).toHaveCount(0);
  await expect(page.getByText('No edges yet')).toBeVisible();
  await expect(page.getByRole('button', { name: '+ Create Edge' })).toBeVisible();
});

test('read-only console: no create, no Edit, no key actions, no danger card', async ({ page }) => {
  await loadAdminApp(page, { path: edgesPath, state: { edges: [bravoEdge], myPermissions: readOnly } });

  await expect(page.getByRole('button', { name: '+ Create Edge' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Actions for edge-bravo' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy edge ID']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const dialog = panel(page, 'edge-bravo');
  await expect(dialog.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Rotate key' })).toHaveCount(0);
  await expect(dialog.getByRole('region', { name: 'Delete edge' })).toHaveCount(0);
});

test('below 720px the panel is a full-screen sheet', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${edgesPath}&id=edge-bravo`, state: { edges: [bravoEdge] } });

  const dialog = panel(page, 'edge-bravo');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  expect((await dialog.getByRole('dialog').boundingBox())?.width).toBe(await page.evaluate(() => document.documentElement.clientWidth));
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test('asks before the panel closes on a private key that is shown only once', async ({ page }) => {
  await loadAdminApp(page, { path: edgesPath, state: { edges: [] } });

  await page.getByRole('button', { name: '+ Create Edge' }).click();
  const dialog = panel(page, 'Register edge');
  await dialog.getByLabel('Edge ID *').fill('new');
  await dialog.getByRole('button', { name: 'Register edge', exact: true }).click();
  const record = panel(page, 'edge-new');
  await expect(record.getByText('Edge key generated')).toBeVisible();

  await page.keyboard.press('Escape');
  const confirm = page.getByRole('dialog', { name: 'Leave without saving the private key?' });
  await confirm.getByRole('button', { name: 'Stay' }).click();
  await expect(record.getByText('Edge key generated')).toBeVisible();

  await page.keyboard.press('Escape');
  await confirm.getByRole('button', { name: 'Leave' }).click();
  await expect(panel(page, 'edge-new')).toHaveCount(0);
});
