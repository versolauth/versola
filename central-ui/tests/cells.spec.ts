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
/** A public RSA JWK of 2048 bits (342 base64url characters of modulus) that is none of a real key. */
const rsaKey = (kid: string) => ({ kty: 'RSA', kid, n: `${'q'.repeat(342 - kid.length)}${kid}`, e: 'AQAB' });
const cell = (id: string, regionId: string, state: 'open' | 'closed' | 'draining', weight: number) =>
  ({ id, regionId, internalUrl: `http://auth-${id}.internal:9003`, mtlsUrl: null, trustedCertificates: [] as string[], state, weight, publicKeys: [rsaKey(`${id}-key`)] });
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
  await expect(table.getByRole('columnheader')).toHaveText(['Cell', 'Region', 'State', 'Weight', 'Users', 'Moving', 'Address', 'Actions']);
  const first = table.getByRole('row').filter({ hasText: 'kz-1' });
  await expect(first).toContainText('almaty');
  await expect(first).toContainText('Open');
  await expect(first).toContainText('100');
  await expect(first).toContainText('http://auth-kz-1.internal:9003');

  await page.getByRole('button', { name: 'Draining', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open cell/ })).toHaveText([/kz-2/]);
  await page.getByRole('group', { name: 'Filter cells' }).getByRole('button', { name: 'All', exact: true }).click();
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
  await expect(dialog.getByRole('button', { name: 'Delete cell kz-1', exact: true })).toBeDisabled();
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
  await create.getByRole('button', { name: 'Register cell', exact: true }).click();
  // The key is required: nothing is sent without it.
  await expect(create.getByRole('alert')).toContainText('A public key is required');
  expect(api.requests.filter(request => request.method === 'POST' && request.pathname === '/configuration/cells')).toEqual([]);
  await create.getByLabel('Public keys *').fill(JSON.stringify(rsaKey('kz-1-key')));
  // A new cell takes no users until it is opened.
  await expect(create.getByLabel('State')).toHaveValue('closed');
  await create.getByRole('button', { name: 'Register cell', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/cells')).body).toEqual({
    id: 'kz-1', regionId: 'almaty', state: 'closed', weight: 100,
    internalUrl: 'http://auth-kz-1.internal:9003', mtlsUrl: 'https://auth-kz-1.internal:9008', trustedCertificates: [pem],
    publicKeys: [rsaKey('kz-1-key')],
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
  await panel(page, 'kz-3').getByRole('button', { name: 'Delete cell kz-3', exact: true }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/cells')).searchParams).toEqual({ cellId: 'kz-3' });
  await expect(page.locator('versola-data-table').first()).not.toContainText('kz-3');
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
  await create.getByLabel('Public keys *').fill(JSON.stringify(rsaKey('kz-9-key')));
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

test('a create link opened on a fresh load still submits the region it shows', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${cellsPath}&new=1`, state: topology });

  const create = panel(page, 'Register cell');
  await expect(create.getByLabel('Region')).toHaveValue('almaty');
  await create.getByLabel('Cell ID *').fill('kz-9');
  await create.getByLabel('Internal URL *').fill('http://auth-kz-9.internal:9003');
  await create.getByLabel('Public keys *').fill(JSON.stringify(rsaKey('kz-9-key')));
  await create.getByRole('button', { name: 'Register cell', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/cells')).body).toMatchObject({ id: 'kz-9', regionId: 'almaty' });
});

test('a region that central refuses to delete stays listed, and the refusal is shown', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: { ...topology, regions: [...topology.regions, { id: 'shymkent', partitionId: 'kz' }] } });
  await page.route('**/configuration/regions*', route => route.request().method() === 'DELETE'
    ? route.fulfill({ status: 409, contentType: 'text/plain', body: "region 'shymkent' still has edges in it: edge-x" })
    : route.fallback());

  await page.getByRole('button', { name: 'Delete region shymkent' }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();

  await expect(page.getByRole('alert').filter({ hasText: 'still has edges in it' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Region shymkent' })).toBeVisible();
});

test('a failure while placing edge sites names the edge that was not changed', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: topology });
  await page.route('**/configuration/edges/region*', route => route.request().url().includes('edgeId=edge-almaty')
    ? route.fulfill({ status: 400, contentType: 'text/plain', body: 'topology_invalid' })
    : route.fallback());

  await page.getByRole('button', { name: 'Edit edge sites of almaty' }).click();
  const sites = panel(page, 'Edit edge sites');
  await sites.getByLabel('edge-aktau').check();
  await sites.getByLabel('edge-almaty').uncheck();
  await sites.getByRole('button', { name: 'Save edge sites' }).click();

  await expect(sites.getByRole('alert')).toContainText('Edge edge-almaty was not changed');
});

test('a cell is registered with two keys, the first one named as the key others encrypt to', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${cellsPath}&new=1`, state: topology });

  const create = panel(page, 'Register cell');
  await expect(create.getByRole('heading', { name: 'Public keys' })).toBeVisible();
  await expect(create).toContainText('The first one is the key other cells encrypt to');
  await expect(create).toContainText('Register the cell here before its auth is started');
  await create.getByLabel('Cell ID *').fill('kz-9');
  await create.getByLabel('Internal URL *').fill('http://auth-kz-9.internal:9003');
  // Two objects one after another are read as two keys.
  await create.getByLabel('Public keys *').fill(`${JSON.stringify(rsaKey('old'))}\n\n${JSON.stringify(rsaKey('new'))}`);
  await create.getByRole('button', { name: 'Register cell', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/cells')).body).toMatchObject({ id: 'kz-9', publicKeys: [rsaKey('old'), rsaKey('new')] });
});

test('refuses a key that is not a public RSA JWK before anything is sent', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${cellsPath}&new=1`, state: topology });

  const create = panel(page, 'Register cell');
  await create.getByLabel('Cell ID *').fill('kz-9');
  await create.getByLabel('Internal URL *').fill('http://auth-kz-9.internal:9003');
  const keys = create.getByLabel('Public keys *');
  const rejects = async (value: string, message: string) => {
    await keys.fill(value);
    await expect(create.getByRole('alert')).toContainText(message);
    await create.getByRole('button', { name: 'Register cell', exact: true }).click();
    expect(api.requests.filter(request => request.method === 'POST' && request.pathname === '/configuration/cells')).toEqual([]);
  };
  await rejects('{ not json', 'Must be valid JSON');
  await rejects('"a string"', 'Must be a JWK');
  await rejects(JSON.stringify({ ...rsaKey('a'), kty: 'EC' }), 'Must be an RSA key');
  await rejects(JSON.stringify({ kty: 'RSA', kid: 'a', e: 'AQAB' }), 'Missing "n"');
  await rejects(JSON.stringify({ ...rsaKey('a'), kid: '' }), 'Missing "kid"');
  for (const member of ['d', 'p', 'q', 'dp', 'dq', 'qi']) await rejects(JSON.stringify({ ...rsaKey('a'), [member]: 'secret' }), `A private member ("${member}")`);
  await rejects(JSON.stringify({ ...rsaKey('a'), n: 'AAAA' }), '2048 to 8192 bits');
  await rejects(JSON.stringify([rsaKey('a'), rsaKey('b'), rsaKey('c')]), 'At most 2 keys');
  await rejects(JSON.stringify([rsaKey('a'), { ...rsaKey('b'), kid: 'a' }]), 'same "kid"');
  await rejects(JSON.stringify([rsaKey('a'), { ...rsaKey('a'), kid: 'b' }]), 'same key is pasted twice');
});

test('the refusal central gives for a key is shown, and edits the key set as a whole', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: topology });

  await page.getByRole('button', { name: 'Open cell kz-1' }).click();
  await expect(panel(page, 'kz-1').getByRole('region', { name: 'Public keys' })).toContainText('kz-1-key');
  await panel(page, 'kz-1').getByRole('region', { name: 'Public keys' }).getByRole('button', { name: 'Edit' }).click();
  const keys = panel(page, 'Edit public keys');
  // The key set is shown for editing as it is.
  await expect(keys.getByLabel('Public keys *')).toHaveValue(JSON.stringify(rsaKey('kz-1-key'), null, 2));
  await page.route('**/configuration/cells', route => route.request().method() === 'PUT'
    ? route.fulfill({ status: 400, contentType: 'text/plain', body: "publicKeys[0] is already cell 'kz-3''s key" })
    : route.fallback());
  await keys.getByRole('button', { name: 'Save public keys' }).click();
  await expect(keys.getByRole('alert')).toContainText("already cell 'kz-3''s key");
  await page.unroute('**/configuration/cells');

  await keys.getByLabel('Public keys *').fill(JSON.stringify([rsaKey('kz-1-key'), rsaKey('kz-1-next')]));
  await keys.getByRole('button', { name: 'Save public keys' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/cells')).body).toEqual({
    ...cell('kz-1', 'almaty', 'open', 100), publicKeys: [rsaKey('kz-1-key'), rsaKey('kz-1-next')],
  });
});

const busy = {
  ...topology,
  cells: [
    { ...cell('kz-1', 'almaty', 'open', 100), users: 1200 },
    { ...cell('kz-2', 'almaty', 'draining', 0), users: 340, tokens: 12, tokensUntil: '2026-12-01T10:00:00Z' },
    { ...cell('kz-3', 'astana', 'closed', 50), users: 0, tokens: 0 },
  ],
  moves: [
    { userId: '11111111-1111-1111-1111-111111111111', from: 'kz-2', to: 'kz-1', phase: 'leaving', reason: 'drain', startedAt: '2026-11-20T10:00:00Z', updatedAt: '2026-11-20T10:00:05Z', attempts: 0 },
    { userId: '22222222-2222-2222-2222-222222222222', from: 'kz-2', to: 'kz-1', phase: 'flipped', reason: 'drain', startedAt: '2026-11-20T09:00:00Z', updatedAt: '2026-11-20T09:30:00Z', attempts: 2, error: 'complete_failed' },
    { userId: '33333333-3333-3333-3333-333333333333', from: 'kz-1', to: 'kz-3', phase: 'failed', reason: 'admin', startedAt: '2026-11-19T09:00:00Z', updatedAt: '2026-11-19T09:10:00Z', attempts: 5, error: 'target_has_user' },
    { userId: '44444444-4444-4444-4444-444444444444', from: 'kz-1', to: 'kz-3', phase: 'done', reason: 'admin', startedAt: '2026-11-18T09:00:00Z', updatedAt: '2026-11-18T09:10:00Z', attempts: 0 },
  ],
};

test('shows the users and the moves under way in the table, and how far a drain has got', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: busy });

  const table = page.locator('versola-data-table').first();
  await expect(table.getByRole('row').filter({ hasText: 'kz-1' })).toContainText('1200');
  // kz-2 holds 340 users, and two of the moves out of it are not finished.
  await expect(table.getByRole('row').filter({ hasText: 'kz-2' })).toContainText('340');
  await expect(table.getByRole('row').filter({ hasText: 'kz-2' })).toContainText('2');

  await page.getByRole('button', { name: 'Open cell kz-2' }).click();
  const progress = panel(page, 'kz-2').getByRole('region', { name: 'Drain progress' });
  await expect(progress).toContainText('Users left');
  await expect(progress).toContainText('340');
  await expect(progress).toContainText('Moves in progress');
  await expect(progress).toContainText('12');
  await expect(progress).toContainText('the last expires');
  await expect(progress).not.toContainText('The drain is waiting');
});

test('says that a drain cannot proceed when no open cell can take the users', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: { ...busy, cells: busy.cells.map(item => item.id === 'kz-2' ? { ...item, drainBlocked: 'no_open_cell' } : item) } });

  await expect(page.locator('versola-data-table').first().getByRole('row').filter({ hasText: 'kz-2' })).toContainText('Blocked');
  await page.getByRole('button', { name: 'Open cell kz-2' }).click();
  await expect(panel(page, 'kz-2').getByRole('region', { name: 'Drain progress' })).toContainText('No open cell of the partition with a weight above 0 can take these users');
});

test('a cell that did not report its carried refresh tokens shows them as unknown, not as zero', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: { ...busy, cells: busy.cells.map(item => item.id === 'kz-1' ? { ...item, tokens: null } : item) } });

  await page.getByRole('button', { name: 'Open cell kz-1' }).click();
  const card = panel(page, 'kz-1').getByRole('region', { name: 'Users and moves' });
  await expect(card).toContainText('Unknown: the cell did not answer');
  await page.getByRole('button', { name: 'Close panel' }).click();
  await page.getByRole('button', { name: 'Open cell kz-3' }).click();
  await expect(panel(page, 'kz-3').getByRole('region', { name: 'Users and moves' }).getByText('Unknown')).toHaveCount(0);
});

const deleteCellRequests = (api: { requests: Array<{ method: string; pathname: string }> }) =>
  api.requests.filter(request => request.method === 'DELETE' && request.pathname === '/configuration/cells') as unknown as Array<{ searchParams: Record<string, string> }>;

test('a cell that still holds users is not deleted, and the way out is named', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: { ...busy, cells: busy.cells.map(item => item.id === 'kz-3' ? { ...item, users: 7 } : item) } });

  await page.getByRole('button', { name: 'Open cell kz-3' }).click();
  await panel(page, 'kz-3').getByRole('button', { name: 'Delete cell kz-3', exact: true }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();

  await expect(page.getByRole('alert').filter({ hasText: 'still holds 7 user(s)' })).toBeVisible();
  // `force` does not lift this refusal, so it is not offered.
  await expect(page.getByRole('button', { name: 'Delete and invalidate' })).toHaveCount(0);
  expect(deleteCellRequests(api).map(request => request.searchParams)).toEqual([{ cellId: 'kz-3' }]);
  await expect(page.locator('versola-data-table').first()).toContainText('kz-3');
});

test('a cell with a move that is not finished is not deleted', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: { ...busy, moves: [{ ...busy.moves[3], phase: 'arrived' }] } });

  await page.getByRole('button', { name: 'Open cell kz-3' }).click();
  await panel(page, 'kz-3').getByRole('button', { name: 'Delete cell kz-3', exact: true }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();

  await expect(page.getByRole('alert').filter({ hasText: 'A move from or to this cell is not finished' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Delete and invalidate' })).toHaveCount(0);
  expect(deleteCellRequests(api)).toHaveLength(1);
});

test('a cell that still answers for refresh tokens is deleted only after the consequence is accepted, with force', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: { ...busy, cells: busy.cells.map(item => item.id === 'kz-3' ? { ...item, tokens: 5 } : item) } });

  await page.getByRole('button', { name: 'Open cell kz-3' }).click();
  await panel(page, 'kz-3').getByRole('button', { name: 'Delete cell kz-3', exact: true }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();

  const dialog = page.getByRole('dialog', { name: /Delete the cell/ });
  await expect(dialog).toContainText('still answers for 5 refresh token(s)');
  await expect(dialog).toContainText('makes those refresh tokens invalid');
  // Declining leaves the cell where it is and sends nothing more.
  await page.getByRole('button', { name: 'Cancel' }).click();
  expect(deleteCellRequests(api).map(request => request.searchParams)).toEqual([{ cellId: 'kz-3' }]);
  await expect(page.locator('versola-data-table').first()).toContainText('kz-3');

  await panel(page, 'kz-3').getByRole('button', { name: 'Delete cell kz-3', exact: true }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await page.getByRole('button', { name: 'Delete and invalidate' }).click();
  await expect.poll(() => deleteCellRequests(api).map(request => request.searchParams).at(-1)).toEqual({ cellId: 'kz-3', force: 'true' });
  await expect(page.locator('versola-data-table').first()).not.toContainText('kz-3');
});

test('a cell that did not answer is also deleted only with force, and says so', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: { ...busy, cells: busy.cells.map(item => item.id === 'kz-3' ? { ...item, tokens: null } : item) } });

  await page.getByRole('button', { name: 'Open cell kz-3' }).click();
  await panel(page, 'kz-3').getByRole('button', { name: 'Delete cell kz-3', exact: true }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByRole('dialog', { name: /Delete the cell/ })).toContainText('did not say whether it still answers');
  await page.getByRole('button', { name: 'Delete and invalidate' }).click();
  await expect.poll(() => deleteCellRequests(api).map(request => request.searchParams).at(-1)).toEqual({ cellId: 'kz-3', force: 'true' });
});

test('a lost cell is deleted with lost=true after its own warning, not as a retirement', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: { ...busy, cells: busy.cells.map(item => item.id === 'kz-3' ? { ...item, users: 7 } : item) } });

  await page.getByRole('button', { name: 'Open cell kz-3' }).click();
  const lost = panel(page, 'kz-3').getByRole('region', { name: 'The cell is lost' });
  await expect(lost).toContainText('cannot sign in until they are deleted');
  await lost.getByRole('button', { name: 'Delete cell kz-3 as lost' }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete a lost cell' });
  await expect(dialog).toContainText('gone for good');
  await expect(dialog).toContainText('7 user(s)');
  await page.getByRole('button', { name: 'Delete as lost' }).click();

  await expect.poll(() => deleteCellRequests(api).map(request => request.searchParams).at(-1)).toEqual({ cellId: 'kz-3', lost: 'true' });
  await expect(page.locator('versola-data-table').first()).not.toContainText('kz-3');
});

test('an open cell offers neither delete, because both need it closed', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: busy });

  await page.getByRole('button', { name: 'Open cell kz-1' }).click();
  await expect(panel(page, 'kz-1').getByRole('button', { name: 'Delete cell kz-1 as lost' })).toBeDisabled();
});

test('lists the moves with readable phases, reasons and errors, warns about one stuck in flipped, and filters by phase', async ({ page }) => {
  const api = await loadAdminApp(page, { path: cellsPath, state: busy });

  const moves = page.getByRole('region', { name: 'User moves' });
  await expect(moves.getByRole('row')).toHaveCount(5);
  // The page size is asked for explicitly, whatever the server's default is.
  expect((await findRequest(api.requests, 'GET', '/users/moves')).searchParams).toMatchObject({ limit: '100' });
  await expect(moves.getByRole('columnheader')).toHaveText(['User', 'From → to', 'Phase', 'Reason', 'Started', 'Updated']);
  await expect(moves.getByRole('row')).toHaveCount(5);
  await expect(moves).toContainText('Draining the cell');
  await expect(moves).toContainText('Requested by an administrator');
  await expect(moves).toContainText('The old cell could not drop the user\'s data');
  await expect(moves).toContainText('2 failed attempts');
  await expect(moves).toContainText('The new cell already has this user');
  await expect(moves.getByRole('status')).toContainText('1 move is flipped but not complete');
  await expect(moves.getByRole('status')).toContainText('does not serve these users until it completes');
  await expect(moves.getByRole('row').filter({ hasText: '2222' })).toContainText('The old cell does not serve this user until the move completes');

  await moves.getByRole('button', { name: 'Failed', exact: true }).click();
  await expect(moves.getByRole('row')).toHaveCount(2);
  await expect(moves.locator('versola-data-table')).not.toContainText('Flipped');
  expect((await findRequest(api.requests, 'GET', '/users/moves')).searchParams).toMatchObject({ phase: 'failed' });
  await expect(moves.getByRole('status')).toHaveCount(0);

  await moves.getByRole('button', { name: 'All', exact: true }).click();
  await moves.getByLabel('Filter moves by cell').selectOption('kz-3');
  await expect(moves.getByRole('row')).toHaveCount(3);
  expect((await findRequest(api.requests, 'GET', '/users/moves')).searchParams).toMatchObject({ cell: 'kz-3' });
});

test('says so when no user has been moved', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: topology });

  await expect(page.getByRole('region', { name: 'User moves' })).toContainText('No user has been moved between cells');
});

test('the moves list and the cell record pass the accessibility checks', async ({ page }) => {
  await loadAdminApp(page, { path: cellsPath, state: busy });

  await expect(page.getByRole('region', { name: 'User moves' }).getByRole('row')).toHaveCount(5);
  expect(await axe(page)).toEqual([]);
  await page.getByRole('button', { name: 'Open cell kz-2' }).click();
  await expect(panel(page, 'kz-2').getByRole('region', { name: 'Drain progress' })).toBeVisible();
  expect(await axe(page)).toEqual([]);
});
