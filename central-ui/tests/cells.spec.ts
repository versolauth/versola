import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { findRequest, loadAdminApp, sidePanel, sidePanelDialog } from './fixtures';

/**
 * Cells (#557): the registry of cells and regions. The table and the record panel, registering a cell, the two group
 * editors, what may be deleted, adding a region with its partition, placing edge sites in a region, the read-only
 * console and a refusal of central shown where it happened.
 */
const cellsPath = '/?view=cells';
const pem = '-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIUexample\n-----END CERTIFICATE-----';
const cell = (id: string, regionId: string, state: 'open' | 'closed' | 'draining', weight: number) =>
  ({ id, regionId, internalUrl: `http://auth-${id}.internal:9003`, mtlsUrl: null, trustedCertificates: [] as string[], state, weight });
const topology = {
  partitions: [{ id: 'kz' }],
  regions: [{ id: 'almaty', partitionId: 'kz' }, { id: 'astana', partitionId: 'kz' }],
  cells: [cell('kz-1', 'almaty', 'open', 100), cell('kz-2', 'almaty', 'draining', 0), cell('kz-3', 'astana', 'closed', 50)],
  edges: [
    { id: 'edge-almaty', hasOldKey: false, requireDpopNonce: true, regionId: 'almaty' },
    { id: 'edge-astana', hasOldKey: false, requireDpopNonce: true, regionId: 'astana' },
    { id: 'edge-aktau', hasOldKey: false, requireDpopNonce: true },
  ],
};
const readOnly = { resources: { central: { permissions: ['edges:read'] } }, isProd: false };

const panel = sidePanel;
const axe = async (page: import('@playwright/test').Page) =>
  (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`);

test('lists cells with their region, state, weight and address, filters by state and searches', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: topology });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Cell', 'Region', 'State', 'Weight', 'Address', 'Actions']);
  const first = table.getByRole('row').filter({ hasText: 'kz-1' });
  await expect(first).toContainText('almaty');
  await expect(first).toContainText('Open');
  await expect(first).toContainText('100');
  await expect(first).toContainText('http://auth-kz-1.internal:9003');

  await page.getByRole('button', { name: 'Draining', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open cell/ })).toHaveText([/kz-2/]);
  await page.getByRole('button', { name: 'All', exact: true }).click();
  await page.getByLabel('Search cells').fill('astana');
  await expect(table.getByRole('button', { name: /^Open cell/ })).toHaveText([/kz-3/]);
});

test('shows each region with its partition, its cells and its edge sites', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: topology });

  const almaty = page.getByRole('region', { name: 'Region almaty' });
  await expect(almaty).toContainText('Partition kz');
  await expect(almaty).toContainText('kz-1');
  await expect(almaty).toContainText('kz-2');
  await expect(almaty).toContainText('edge-almaty');
  await expect(almaty).not.toContainText('edge-astana');
  // A region with a cell or an edge in it cannot be deleted, so the button is not offered.
  await expect(almaty.getByRole('button', { name: 'Delete region almaty' })).toHaveCount(0);
});

test('an installation with nothing registered says so and offers a region first', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath });

  await expect(page.getByText('No cells registered')).toBeVisible();
  await expect(page.getByRole('button', { name: '+ Register Cell' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Add region' })).toBeVisible();
  expect(await axe(page)).toEqual([]);
});

test('opens a cell: placement, addresses, what is fixed, and a delete that waits for the cell to be closed', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: { ...topology, cells: [{ ...cell('kz-1', 'almaty', 'open', 100), mtlsUrl: 'https://auth-kz-1.internal:9008', trustedCertificates: [pem] }] } });

  await page.getByRole('button', { name: 'Open cell kz-1' }).click();
  const dialog = panel(page, 'kz-1');
  await expect(dialog.getByRole('region', { name: 'Placement' })).toContainText('New users are placed here');
  await expect(dialog.getByRole('region', { name: 'Addresses' })).toContainText('https://auth-kz-1.internal:9008');
  await expect(dialog.getByRole('region', { name: 'Addresses' })).toContainText('Certificate 1');
  await expect(dialog.getByRole('region', { name: 'Fixed' })).toContainText('almaty');
  await expect(dialog.getByRole('region', { name: 'Fixed' })).toContainText('kz');
  await expect(dialog.getByRole('button', { name: 'Delete cell kz-1' })).toBeDisabled();
  await expect(dialog).toContainText('Only a closed cell is deleted');
  expect(await axe(page)).toEqual([]);
});

test('adds a region with a new partition when there is none, then registers a closed cell in it', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath });

  await page.getByRole('button', { name: 'Add region' }).click();
  const region = panel(page, 'Add region');
  await expect(sidePanelDialog(page, 'Add region')).toBeVisible();
  expect(await axe(page)).toEqual([]);
  await region.getByLabel('Region ID *').fill('Almaty');
  await expect(region.getByRole('alert')).toContainText('Lowercase letters');
  await region.getByLabel('Region ID *').fill('almaty');
  await region.getByLabel('Partition ID *').fill('kz');
  await region.getByRole('button', { name: 'Add region', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/partitions')).body).toEqual({ id: 'kz' });
  expect((await findRequest(api.requests, 'POST', '/configuration/regions')).body).toEqual({ id: 'almaty', partitionId: 'kz' });
  await expect(page.getByRole('region', { name: 'Region almaty' })).toContainText('Partition kz');

  await page.getByRole('button', { name: '+ Register Cell' }).click();
  const create = panel(page, 'Register cell');
  expect(await axe(page)).toEqual([]);
  await create.getByLabel('Cell ID *').fill('kz-1');
  await create.getByLabel('Internal URL *').fill('auth-kz-1');
  await expect(create.getByRole('alert')).toContainText('An absolute http or https URL');
  await create.getByLabel('Internal URL *').fill('http://auth-kz-1.internal:9003');
  await create.getByLabel('Mutual-TLS URL (Optional)').fill('https://auth-kz-1.internal:9008');
  await create.getByLabel('Pinned certificates (Optional)').fill(pem);
  // A new cell takes no users until it is opened.
  await expect(create.getByLabel('State')).toHaveValue('closed');
  await create.getByRole('button', { name: 'Register cell', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/cells')).body).toEqual({
    id: 'kz-1', regionId: 'almaty', state: 'closed', weight: 100,
    internalUrl: 'http://auth-kz-1.internal:9003', mtlsUrl: 'https://auth-kz-1.internal:9008', trustedCertificates: [pem],
  });
  await expect(sidePanelDialog(page, 'kz-1')).toBeVisible();
  await expect(page.locator('versola-data-table')).toContainText('kz-1');
});

test('a region is added to an existing partition without creating one', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: topology });

  await page.getByRole('button', { name: 'Add region' }).click();
  const region = panel(page, 'Add region');
  await expect(region.getByLabel('Partition *')).toHaveValue('kz');
  await region.getByLabel('Region ID *').fill('almaty');
  await expect(region.getByRole('alert')).toContainText('A region with this ID already exists.');
  await region.getByLabel('Region ID *').fill('aktau');
  await region.getByRole('button', { name: 'Add region', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/regions')).body).toEqual({ id: 'aktau', partitionId: 'kz' });
  expect(api.requests.filter(request => request.pathname === '/configuration/partitions')).toEqual([]);
  // An empty region can be deleted.
  await page.getByRole('button', { name: 'Delete region aktau' }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/regions')).searchParams).toEqual({ regionId: 'aktau' });
  await expect(page.getByRole('region', { name: 'Region aktau' })).toHaveCount(0);
});

test('edits the placement and keeps the addresses; edits the addresses and keeps the placement', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: topology });

  await page.getByRole('button', { name: 'Open cell kz-1' }).click();
  await panel(page, 'kz-1').getByRole('button', { name: 'Edit placement' }).click();
  const placement = panel(page, 'Edit placement');
  expect(await axe(page)).toEqual([]);
  await placement.getByLabel('State').selectOption('draining');
  await placement.getByLabel('Weight *').fill('-1');
  await expect(placement.getByRole('alert')).toContainText('A whole number, 0 or more.');
  await placement.getByLabel('Weight *').fill('0');
  await placement.getByRole('button', { name: 'Save placement' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/cells')).body).toEqual({ ...cell('kz-1', 'almaty', 'draining', 0) });
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'kz-1' })).toContainText('Draining');

  api.requests.length = 0;
  await panel(page, 'kz-1').getByRole('button', { name: 'Edit addresses' }).click();
  const addresses = panel(page, 'Edit addresses');
  await addresses.getByLabel('Mutual-TLS URL (Optional)').fill('http://auth-kz-1.internal:9008');
  await expect(addresses.getByRole('alert')).toContainText('An absolute https URL');
  await addresses.getByLabel('Mutual-TLS URL (Optional)').fill('https://auth-kz-1.internal:9008');
  await addresses.getByLabel('Pinned certificates (Optional)').fill('not a certificate');
  await expect(addresses.getByRole('alert')).toContainText('PEM certificates only');
  await addresses.getByLabel('Pinned certificates (Optional)').fill(`${pem}\n`);
  await addresses.getByRole('button', { name: 'Save addresses' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/cells')).body).toEqual({
    ...cell('kz-1', 'almaty', 'draining', 0), mtlsUrl: 'https://auth-kz-1.internal:9008', trustedCertificates: [pem],
  });
});

test('deletes a closed cell', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: topology });

  await page.getByRole('button', { name: 'Open cell kz-3' }).click();
  await panel(page, 'kz-3').getByRole('button', { name: 'Delete cell kz-3' }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/cells')).searchParams).toEqual({ cellId: 'kz-3' });
  await expect(page.locator('versola-data-table')).not.toContainText('kz-3');
});

test('a refusal of central is shown in the editor, and the cell is not listed', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: topology });
  await page.route('**/configuration/cells', route => route.request().method() === 'POST'
    ? route.fulfill({ status: 400, contentType: 'text/plain', body: 'trustedCertificates[0] is not a PEM certificate' })
    : route.fallback());

  await page.getByRole('button', { name: '+ Register Cell' }).click();
  const create = panel(page, 'Register cell');
  await create.getByLabel('Cell ID *').fill('kz-9');
  await create.getByLabel('Internal URL *').fill('http://auth-kz-9.internal:9003');
  await create.getByRole('button', { name: 'Register cell', exact: true }).click();

  await expect(create.getByRole('alert')).toContainText('trustedCertificates[0] is not a PEM certificate');
  await expect(page.locator('versola-data-table')).not.toContainText('kz-9');
});

test('puts an edge in a region and takes another out, one request for each edge that changes', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: topology });

  await page.getByRole('button', { name: 'Edit edge sites of almaty' }).click();
  const sites = panel(page, 'Edit edge sites');
  expect(await axe(page)).toEqual([]);
  await expect(sites.getByLabel('edge-almaty')).toBeChecked();
  await expect(sites.getByLabel(/edge-astana/)).not.toBeChecked();
  await expect(sites).toContainText('now in astana');
  await sites.getByLabel('edge-aktau').check();
  await sites.getByLabel('edge-almaty').uncheck();
  await sites.getByRole('button', { name: 'Save edge sites' }).click();

  await expect(page.getByRole('region', { name: 'Region almaty' })).toContainText('edge-aktau');
  const changes = api.requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/edges/region');
  expect(changes.map(request => [request.searchParams.edgeId, request.body])).toEqual([
    ['edge-aktau', { regionId: 'almaty' }],
    ['edge-almaty', {}],
  ]);
  await expect(page.getByRole('region', { name: 'Region almaty' })).not.toContainText('edge-almaty');
});

test('a read-only console shows the registry and offers no change', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: { ...topology, myPermissions: readOnly } });

  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open cell/ })).toHaveCount(3);
  await expect(page.getByRole('button', { name: '+ Register Cell' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Add region' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Edit edge sites/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Open cell kz-3' }).click();
  await expect(panel(page, 'kz-3').getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(panel(page, 'kz-3').getByRole('button', { name: /^Delete cell/ })).toHaveCount(0);
});

test('the edge record names the region of the edge site', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=edges', state: topology });

  await page.getByRole('button', { name: 'Open edge edge-almaty' }).click();
  await expect(panel(page, 'edge-almaty').getByRole('region', { name: 'Region' })).toContainText('almaty');
  await panel(page, 'edge-almaty').getByRole('button', { name: 'Close panel' }).click();
  await page.getByRole('button', { name: 'Open edge edge-aktau' }).click();
  await expect(panel(page, 'edge-aktau').getByRole('region', { name: 'Region' })).toContainText('None');
});
