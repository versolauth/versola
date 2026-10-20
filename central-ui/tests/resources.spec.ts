import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';
import { catalogApi, loyaltyApi, ordersApi, shopClients, shopState, shopTenant } from './resources-fixtures';

const listPath = `/?view=resources&tenant=${shopTenant}`;
const recordPath = (id: string, edit?: string) => `${listPath}&id=${id}${edit ? `&edit=${edit}` : ''}`;
const readOnly = { resources: { central: { permissions: ['resources:read', 'permissions:read', 'oauth:read'] } }, isProd: false };

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
const resourceRow = (page: Page, id: string) => page.getByRole('row').filter({ has: page.getByRole('button', { name: `Open resource ${id}` }) });
const card = (page: Page, name: string) => page.getByRole('region', { name, exact: true });
const endpointRow = (page: Page, label: string) =>
  page.getByRole('list', { name: 'Endpoints' }).getByRole('button', { name: new RegExp(`^${escape(label)}(?= |$)`) });
const dialog = (page: Page) => page.getByRole('dialog');
const putBodies = (requests: Array<{ method: string; pathname: string; body?: unknown }>) =>
  requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/resources');

test.describe('list', () => {
  test('shows a row per resource: the ID with its host under it, type, first endpoints and clients', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: shopState });

    await expect(page.getByRole('heading', { level: 1, name: 'Resources' })).toBeVisible();
    await expect(page.getByRole('columnheader')).toHaveText(['Resource', 'Type', 'Endpoints', 'Audience', 'Actions']);

    const orders = resourceRow(page, 'orders-api');
    await expect(orders).toContainText('orders.internal.example.com');
    await expect(orders).toContainText('Internal');
    await expect(orders).toContainText('Secret rotation');
    // At most two endpoints, one per line, and a muted overflow line under them.
    await expect(orders.locator('.method-badge')).toHaveText(['GET', 'GET']);
    await expect(orders.locator('.cell-line')).toHaveCount(2);
    const lines = await orders.locator('.cell-line').evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().top));
    expect(lines[1]).toBeGreaterThan(lines[0]);
    await expect(orders.locator('.more-endpoints')).toHaveText('+4 more endpoints');
    await expect(orders.locator('.more-endpoints')).toHaveAttribute('title', /POST \/orders\/\{orderId\}\/refund/);
    await expect(resourceRow(page, 'catalog-api').locator('.more-endpoints')).toHaveText('+1 more endpoint');
    await expect(resourceRow(page, 'support-desk').locator('.more-endpoints')).toHaveCount(0);
    await expect(orders.locator('.tag')).toHaveText(['storefront-web', 'shop-mobile']);
    await expect(orders.locator('.chip.more')).toHaveText('+2');

    await expect(resourceRow(page, 'catalog-api')).toContainText('Public');
    await expect(resourceRow(page, 'loyalty-api')).toContainText('None yet');
    await expect(resourceRow(page, 'loyalty-api')).toContainText('No clients');
    // One primary action, and filter pills without counts.
    await expect(page.getByRole('button', { name: '+ Create Resource' })).toHaveCount(1);
    await expect(page.getByRole('group', { name: 'Filter resources' }).getByRole('button')).toHaveText(['All', 'Internal', 'Public', 'Secret rotation']);
  });

  test('a long ID and host stay whole words and the page never scrolls sideways', async ({ page }) => {
    for (const width of [1440, 375]) {
      await page.setViewportSize({ width, height: 900 });
      await loadAdminApp(page, { path: listPath, state: shopState });
      const open = page.getByRole('button', { name: 'Open resource payments-gateway-eu-central-1' });
      await expect(open).toBeVisible();
      // The ID is one unbroken line of text: its box is as high as one line.
      const box = await open.locator('.row-title').boundingBox();
      expect(box!.height).toBeLessThan(30);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    }
  });

  test('filters by type and by an open secret rotation', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: shopState });
    const filter = page.getByRole('group', { name: 'Filter resources' });

    await filter.getByRole('button', { name: 'Internal' }).click();
    await expect(page.getByRole('row').filter({ has: page.getByRole('button', { name: /^Open resource/ }) })).toHaveCount(2);
    await expect(resourceRow(page, 'orders-api')).toBeVisible();
    await expect(resourceRow(page, 'payments-gateway-eu-central-1')).toBeVisible();

    await filter.getByRole('button', { name: 'Public' }).click();
    await expect(resourceRow(page, 'orders-api')).toHaveCount(0);
    await expect(resourceRow(page, 'catalog-api')).toBeVisible();

    await filter.getByRole('button', { name: 'Secret rotation' }).click();
    await expect(page.getByRole('row').filter({ has: page.getByRole('button', { name: /^Open resource/ }) })).toHaveCount(1);
    await expect(resourceRow(page, 'orders-api')).toBeVisible();
  });

  test('searches by ID, host, /path and METHOD /path', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: shopState });
    const search = page.getByLabel('Search resources');
    const rows = page.getByRole('row').filter({ has: page.getByRole('button', { name: /^Open resource/ }) });

    await search.fill('catalog');
    await expect(rows).toHaveCount(1);
    await search.fill('support.example');
    await expect(resourceRow(page, 'support-desk')).toBeVisible();
    await expect(rows).toHaveCount(1);

    await search.fill('/tickets');
    await expect(rows).toHaveCount(1);
    await expect(resourceRow(page, 'support-desk').locator('.method-badge')).toHaveText(['GET', 'POST']);

    await search.fill('POST /tickets');
    await expect(resourceRow(page, 'support-desk').locator('.method-badge')).toHaveText(['POST']);

    await search.fill('DELETE /v2');
    await expect(page.getByRole('heading', { name: 'No resources match' })).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Create Resource' })).toBeVisible();
  });

  test('the row opens the record; the menu has Open, Copy ID and Delete resource', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: shopState });

    await page.getByRole('button', { name: 'Actions for catalog-api' }).click();
    await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy ID', 'Delete resource']);
    await page.getByRole('menuitem', { name: 'Open' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'catalog-api' })).toBeVisible();
    await expect(page).toHaveURL(/view=resources.*id=catalog-api/);

    await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Resources' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Resources' })).toBeVisible();
    await resourceRow(page, 'orders-api').getByRole('button', { name: 'Open resource orders-api' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'orders-api' })).toBeVisible();
  });

  test('deletes from the row menu through the confirm, naming what detaches', async ({ page }) => {
    const api = await loadAdminApp(page, { path: listPath, state: shopState });

    await page.getByRole('button', { name: 'Actions for orders-api' }).click();
    await page.getByRole('menuitem', { name: 'Delete resource' }).click();
    await expect(dialog(page)).toContainText('Delete resource orders-api?');
    await expect(dialog(page)).toContainText('orders:admin, orders:read, orders:refund, orders:write');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    expect(api.requests.some(request => request.method === 'DELETE')).toBe(false);

    await page.getByRole('button', { name: 'Actions for orders-api' }).click();
    await page.getByRole('menuitem', { name: 'Delete resource' }).click();
    await dialog(page).getByRole('button', { name: 'Delete', exact: true }).click();
    expect((await findRequest(api.requests, 'DELETE', '/configuration/resources')).searchParams).toEqual({ resourceId: 'orders-api' });
    await expect(resourceRow(page, 'orders-api')).toHaveCount(0);
    await expect(page.getByText('Deleted resource orders-api.')).toBeVisible();
  });

  test('an empty tenant offers the first resource; a failed load offers Retry', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: { resources: { [shopTenant]: [] } } });
    await expect(page.getByRole('heading', { name: 'No resources yet' })).toBeVisible();
    await page.getByRole('button', { name: '+ Create Resource' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Create resource' })).toBeVisible();

    let attempts = 0;
    await page.route('**/configuration/resources?*', async route => {
      attempts += 1;
      await new Promise(resolve => setTimeout(resolve, 200));
      if (attempts === 1) await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ message: 'Database unavailable' }) });
      else await route.fallback();
    });
    await page.goto(listPath);
    await expect(page.locator('versola-resources-list versola-loading-cards')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Could not load resources' })).toBeVisible();
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByRole('heading', { name: 'No resources yet' })).toBeVisible();
  });

  test('read-only console: no create, no Delete in the row menu', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: { ...shopState, myPermissions: readOnly } });
    await expect(resourceRow(page, 'orders-api')).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Create Resource' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Actions for orders-api' }).click();
    await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy ID']);
  });
});

test.describe('record', () => {
  test('reads the whole resource without opening an editor', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('orders-api'), state: shopState });

    await expect(page.getByRole('heading', { level: 1, name: 'orders-api' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Copy resource ID' })).toBeVisible();
    await expect(page.locator('versola-record-header')).toContainText('Internal');
    await expect(page.locator('versola-record-header')).toContainText('Secret rotation');
    // No primary action on the page.
    await expect(page.locator('.btn-primary')).toHaveCount(0);

    await expect(card(page, 'General')).toContainText('https://orders.internal.example.com');
    await expect(card(page, 'General')).toContainText('/resources/orders-api/*');
    await expect(card(page, 'Audience').getByRole('button')).toHaveText(['Edit', 'storefront-web', 'shop-mobile', 'admin-console', 'partner-portal']);

    const table = page.getByRole('table', { name: 'Endpoints of orders-api' });
    await expect(table.getByRole('columnheader')).toHaveText(['Endpoint', 'Who may call', 'Step-up', 'Added to the request']);
    await expect(table.getByRole('row')).toHaveCount(7);
    // Each injection is stacked: badge and name on one line, the value under it.
    const injection = table.locator('tr[data-endpoint="GET /orders"] .inj').first();
    const head = await injection.locator('.inj-head').boundingBox();
    const value = await injection.locator('.inj-value').boundingBox();
    expect(value!.y).toBeGreaterThan(head!.y + head!.height - 1);
    const refund = table.locator('tr[data-endpoint="POST /orders/{orderId}/refund"]');
    await expect(refund).toContainText('userinfo');
    await expect(refund).toContainText('orders:refund');
    await expect(refund).toContainText('request.body.amount <= 50000');
    await expect(refund).toContainText('mfa');
    await expect(refund).toContainText('request.body.amount > 10000');
    await expect(refund.getByTitle('300 seconds')).toHaveText('at most 5m');
    await expect(refund).toContainText('refundedBy');
    const remove = table.locator('tr[data-endpoint="DELETE /orders/{orderId}"]');
    await expect(remove).toContainText('orders:admin');
    await expect(remove).toContainText('or');
    await expect(remove).toContainText('orders:refund');
    await expect(remove.getByTitle('600 seconds')).toBeVisible();
    await expect(remove).toContainText('Nothing');
    await expect(table.locator('tr[data-endpoint="GET /orders"]')).toContainText('No extra check');

    await expect(card(page, 'Secret')).toContainText('Set. It is shown once, when it is created or rotated.');
    await expect(card(page, 'Secret')).toContainText('Waiting for activation');
    await expect(card(page, 'Fixed')).toContainText('Resource ID');
    await expect(card(page, 'Fixed')).toContainText('Internal: edge authenticates with its secret');
    await expect(card(page, 'Fixed').getByText('Set at creation')).toHaveCount(2);
    // The danger card is last.
    await expect(page.locator('versola-danger-card').last().getByRole('button', { name: 'Delete resource orders-api' })).toBeVisible();
  });

  test('a public resource has no secret, and says so when it has no endpoints or clients', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('loyalty-api'), state: shopState });

    await expect(page.locator('versola-record-header')).toContainText('Public');
    await expect(card(page, 'Secret')).toHaveCount(0);
    await expect(page.getByText('A new secret was issued')).toHaveCount(0);
    await expect(card(page, 'Audience')).toContainText('No clients are assigned to this resource.');
    await expect(card(page, 'Endpoints')).toContainText('No endpoints yet.');
    await expect(card(page, 'Fixed')).toContainText("Public: edge forwards the caller's token");
  });

  test('opens from a reload, and an unknown ID says the resource was not found', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('catalog-api'), state: shopState });
    await expect(page.getByRole('heading', { level: 1, name: 'catalog-api' })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { level: 1, name: 'catalog-api' })).toBeVisible();

    await page.goto(recordPath('no-such-api'));
    await expect(page.getByRole('heading', { name: 'Resource not found' })).toBeVisible();
    await page.getByRole('button', { name: 'Back to resources' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Resources' })).toBeVisible();
  });

  test('a client in the audience opens that client', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('catalog-api'), state: shopState });
    await card(page, 'Audience').getByRole('button', { name: 'Open client shop-mobile' }).click();
    await expect(page).toHaveURL(/view=clients/);
    await expect(page.getByRole('heading', { level: 1, name: 'Clients' })).toBeVisible();
  });

  test('read-only console: no Edit, no secret actions, no danger card', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('orders-api'), state: { ...shopState, myPermissions: readOnly } });
    await expect(page.getByRole('heading', { level: 1, name: 'orders-api' })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Activate new secret|Rotate secret/ })).toHaveCount(0);
    await expect(page.locator('versola-danger-card')).toHaveCount(0);
    // An edit URL does not open an editor for someone who cannot save.
    await page.goto(recordPath('orders-api', 'general'));
    await expect(page.getByRole('heading', { level: 1, name: 'orders-api' })).toBeVisible();
  });

  test('at 375 each endpoint is a card of vertical blocks; General and Audience stack at 1024 and 375', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 900 });
    await loadAdminApp(page, { path: recordPath('orders-api'), state: shopState });
    const row = page.locator('tr[data-endpoint="POST /orders/{orderId}/refund"]');
    const cells = await row.locator('td').evaluateAll(nodes => nodes.map(node => { const box = node.getBoundingClientRect(); return [box.top, box.left]; }));
    for (let i = 1; i < cells.length; i++) {
      expect(cells[i][0]).toBeGreaterThan(cells[i - 1][0]);
      expect(cells[i][1]).toBe(cells[0][1]);
    }
    await expect(row.locator('td[data-label]')).toHaveCount(3);

    for (const width of [1024, 375]) {
      await page.setViewportSize({ width, height: 900 });
      const general = await card(page, 'General').boundingBox();
      const audience = await card(page, 'Audience').boundingBox();
      expect(audience!.y).toBeGreaterThan(general!.y + general!.height - 1);
    }
  });

  test('the page never scrolls sideways at 1024 and 375', async ({ page }) => {
    for (const width of [1024, 375]) {
      await page.setViewportSize({ width, height: 900 });
      await loadAdminApp(page, { path: recordPath('orders-api'), state: shopState });
      await expect(page.getByRole('table', { name: 'Endpoints of orders-api' })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    }
  });
});

test.describe('general and audience editors', () => {
  test('Edit general saves only the URI', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('orders-api'), state: shopState });
    await card(page, 'General').getByRole('button', { name: 'Edit general' }).click();
    await expect(page).toHaveURL(/edit=general/);
    await expect(page.getByRole('heading', { level: 1, name: 'Edit general' })).toBeVisible();

    await page.getByLabel('Absolute resource URI').fill('https://orders-v2.internal.example.com');
    await page.getByRole('button', { name: 'Save general' }).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/resources')).body).toEqual({
      resourceId: 'orders-api',
      resource: 'https://orders-v2.internal.example.com',
      audience: { add: [], remove: [] },
      deleteEndpoints: [],
      createEndpoints: [],
    });
    await expect(page.getByRole('heading', { level: 1, name: 'orders-api' })).toBeVisible();
    await expect(card(page, 'General')).toContainText('https://orders-v2.internal.example.com');
    expect(api.requests.filter(request => request.method === 'GET' && request.pathname === '/configuration/resources')).toHaveLength(1);
  });

  test('refuses a URI with a path or a reserved scheme before sending', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('orders-api', 'general'), state: shopState });
    const field = page.getByLabel('Absolute resource URI');

    await field.fill('https://orders.example.com/api');
    await expect(field).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByText('Resource URI path must be empty')).toBeVisible();
    await page.getByRole('button', { name: 'Save general' }).click();
    await field.fill('resource://orders');
    await page.getByRole('button', { name: 'Save general' }).click();
    await expect(page.getByText('Resource URI scheme resource:// is reserved')).toBeVisible();
    await field.fill('');
    await expect(page.getByText('Resource URI cannot be empty')).toBeVisible();
    expect(putBodies(api.requests)).toHaveLength(0);
  });

  test('keeps the editor open and shows what central answered', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('orders-api', 'general'), state: shopState });
    await page.route('**/configuration/resources', route => route.request().method() === 'PUT'
      ? route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ message: 'Resource URI is not reachable' }) })
      : route.fallback());
    await page.getByLabel('Absolute resource URI').fill('https://down.example.com');
    await page.getByRole('button', { name: 'Save general' }).click();
    await expect(page.getByRole('alert')).toContainText('Resource URI is not reachable');
    await expect(page.getByRole('heading', { level: 1, name: 'Edit general' })).toBeVisible();
  });

  test('Cancel asks before throwing away a change, and not when nothing changed', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('orders-api', 'general'), state: shopState });
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'orders-api' })).toBeVisible();

    await card(page, 'General').getByRole('button', { name: 'Edit general' }).click();
    await page.getByLabel('Absolute resource URI').fill('https://changed.example.com');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog(page)).toContainText('Discard changes?');
    await dialog(page).getByRole('button', { name: 'Keep editing' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Edit general' })).toBeVisible();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await dialog(page).getByRole('button', { name: 'Discard' }).click();
    await expect(card(page, 'General')).toContainText('https://orders.internal.example.com');
  });

  test('Edit audience sends the clients added and removed', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('catalog-api'), state: shopState });
    await card(page, 'Audience').getByRole('button', { name: 'Edit audience' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Edit audience' })).toBeVisible();
    await expect(page.getByRole('group', { name: 'Audience' })).toContainText(`Clients in ${shopTenant}`);

    await page.getByRole('checkbox', { name: /shop-mobile/ }).uncheck();
    await page.getByRole('checkbox', { name: /admin-console/ }).check();
    await page.getByRole('button', { name: 'Save audience' }).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/resources')).body).toEqual({
      resourceId: 'catalog-api',
      audience: { add: ['admin-console'], remove: ['shop-mobile'] },
      deleteEndpoints: [],
      createEndpoints: [],
    });
    await expect(card(page, 'Audience').locator('.tag')).toHaveText(['storefront-web', 'admin-console']);
  });

  test('Select all and the filter work on the audience, and a client that is gone can be removed', async ({ page }) => {
    const api = await loadAdminApp(page, {
      path: recordPath('loyalty-api'),
      state: { ...shopState, resources: { [shopTenant]: [{ ...loyaltyApi, audience: ['retired-client'] }] } },
    });
    await card(page, 'Audience').getByRole('button', { name: 'Edit audience' }).click();
    await expect(page.getByText('no longer exists in this tenant')).toBeVisible();

    await page.getByLabel('Filter clients').fill('shop');
    await expect(page.getByRole('checkbox')).toHaveCount(1);
    await page.getByLabel('Filter clients').fill('');
    await page.getByRole('button', { name: 'Select all' }).click();
    await expect(page.getByRole('checkbox', { checked: true })).toHaveCount(shopClients.length + 1);
    await page.getByRole('checkbox', { name: /retired-client/ }).uncheck();
    await page.getByRole('button', { name: 'Save audience' }).click();

    const body = (await findRequest(api.requests, 'PUT', '/configuration/resources')).body as { audience: { add: string[]; remove: string[] } };
    expect(body.audience.remove).toEqual(['retired-client']);
    expect(body.audience.add.sort()).toEqual(shopClients.map(client => client.id).sort());
  });
});

test.describe('endpoints editor', () => {
  const open = (page: Page, state = shopState) => loadAdminApp(page, { path: recordPath('orders-api', 'endpoints'), state });

  test('lists every endpoint with the rules it carries; the selected one fills the four panels', async ({ page }) => {
    await open(page);
    await expect(page.getByRole('heading', { level: 1, name: 'Edit endpoints' })).toBeVisible();
    await expect(page.locator('.entity-id-meta')).toContainText('orders-api');
    await expect(page.locator('.entity-id-meta')).toContainText('https://orders.internal.example.com');

    const list = page.getByRole('list', { name: 'Endpoints' });
    await expect(list.getByRole('button')).toHaveCount(6);
    // The registry names the endpoint only: no rule summary.
    await expect(list).not.toContainText(/allow|inject|step-up/i);

    await endpointRow(page, 'POST /orders/{orderId}/refund').click();
    await expect(endpointRow(page, 'POST /orders/{orderId}/refund')).toHaveAttribute('aria-current', 'true');
    await expect(page.getByRole('region', { name: 'Request' }).getByLabel('Relative path')).toHaveValue('/orders/{orderId}/refund');
    await expect(page.getByRole('region', { name: 'Request' }).getByLabel('Method')).toHaveValue('POST');
    await expect(page.getByRole('switch', { name: 'Fetch userinfo' })).toBeChecked();
    await expect(page.getByRole('region', { name: 'Who may call' })).toContainText('orders:refund');
    await expect(page.getByRole('textbox', { name: 'Allow expression' })).toHaveValue('request.body.amount <= 50000 && "shop-manager" in user.roles');
    await expect(page.getByRole('textbox', { name: 'Step-up condition' })).toHaveValue('request.body.amount > 10000');
    await expect(page.getByRole('checkbox', { name: 'mfa' })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: 'pwd' })).not.toBeChecked();
    await expect(page.getByLabel('Max auth age (seconds)')).toHaveValue('300');
    await expect(page.getByRole('region', { name: 'Inject' }).getByLabel('Name').first()).toHaveValue('X-User-Id');
    await expect(page.getByRole('region', { name: 'Inject' }).getByLabel('Name').last()).toHaveValue('refundedBy');
    await expect(page.getByRole('button', { name: 'Save endpoints' })).toBeDisabled();
    await expect(page.getByRole('status').filter({ hasText: 'No changes yet.' })).toBeVisible();
  });

  test('an edit sends the endpoint under its own ID; only what changed is in the request', async ({ page }) => {
    const api = await open(page);
    await endpointRow(page, 'PUT /orders/{orderId}/address').click();
    await page.getByRole('textbox', { name: 'Allow expression' }).fill('has(request.body.postalCode) && has(request.body.city)');
    await expect(endpointRow(page, 'PUT /orders/{orderId}/address')).toContainText('Edited');
    await expect(page.getByRole('status')).toContainText('Not saved yet: PUT /orders/{orderId}/address (edited)');
    await page.getByRole('button', { name: 'Save endpoints' }).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/resources')).body).toEqual({
      resourceId: 'orders-api',
      audience: { add: [], remove: [] },
      deleteEndpoints: [],
      createEndpoints: [{
        id: 4, method: 'PUT', path: '/orders/{orderId}/address', fetchUserInfo: false,
        allow: 'has(request.body.postalCode) && has(request.body.city)', inject: [], stepUpCondition: null, stepUpAcr: null, maxAge: null,
      }],
    });
    await expect(page.getByRole('heading', { level: 1, name: 'orders-api' })).toBeVisible();
    await expect(page.locator('tr[data-endpoint="PUT /orders/{orderId}/address"]')).toContainText('has(request.body.city)');
  });

  test('adds an endpoint with step-up and an inject rule', async ({ page }) => {
    const api = await open(page);
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(page.getByLabel('Relative path')).toBeFocused();
    await expect(endpointRow(page, 'GET /')).toContainText('New');
    await expect(endpointRow(page, 'GET /')).toHaveAttribute('aria-current', 'true');

    await page.getByLabel('Method').selectOption('PATCH');
    await page.getByLabel('Relative path').fill('/orders/{orderId}/note');
    await page.getByRole('switch', { name: 'Fetch userinfo' }).check();
    await page.getByRole('textbox', { name: 'Step-up condition' }).fill('true');
    await page.getByRole('checkbox', { name: 'hwk' }).check();
    await page.getByRole('checkbox', { name: 'pwd' }).check();
    await page.getByLabel('Max auth age (seconds)').fill('90');
    await expect(page.getByText('90 seconds.')).toBeVisible();
    await page.getByRole('button', { name: 'Add rule' }).click();
    await page.getByLabel('Target').selectOption('query');
    await page.getByRole('region', { name: 'Inject' }).getByLabel('Name').fill('tenant');
    await page.getByRole('textbox', { name: 'Inject expression' }).fill('"example-shop"');
    await expect(page.getByRole('status')).toContainText('PATCH /orders/{orderId}/note (new)');
    await page.getByRole('button', { name: 'Save endpoints' }).click();

    const body = (await findRequest(api.requests, 'PUT', '/configuration/resources')).body as { deleteEndpoints: unknown[]; createEndpoints: unknown[] };
    expect(body.deleteEndpoints).toEqual([]);
    expect(body.createEndpoints).toEqual([{
      id: expect.any(String), method: 'PATCH', path: '/orders/{orderId}/note', fetchUserInfo: true, allow: null,
      inject: [{ target: 'query', name: 'tenant', expression: '"example-shop"' }],
      stepUpCondition: 'true', stepUpAcr: 'hwk pwd', maxAge: 90,
    }]);
    await expect(page.locator('tr[data-endpoint="PATCH /orders/{orderId}/note"]')).toContainText('at most');
  });

  test('removing an endpoint asks, names the permissions that lose it, and sends its ID', async ({ page }) => {
    const api = await open(page);
    await endpointRow(page, 'DELETE /orders/{orderId}').click();
    await page.getByRole('button', { name: 'Remove endpoint DELETE /orders/{orderId}' }).click();
    await expect(dialog(page)).toContainText('orders:admin, orders:refund lose it');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await expect(endpointRow(page, 'DELETE /orders/{orderId}')).toBeVisible();

    await page.getByRole('button', { name: 'Remove endpoint DELETE /orders/{orderId}' }).click();
    await dialog(page).getByRole('button', { name: 'Remove endpoint', exact: true }).click();
    await expect(endpointRow(page, 'DELETE /orders/{orderId}')).toHaveCount(0);
    await expect(page.getByRole('status')).toContainText('DELETE /orders/{orderId} (removed)');
    await page.getByRole('button', { name: 'Save endpoints' }).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/resources')).body).toMatchObject({ deleteEndpoints: [6], createEndpoints: [] });
    await expect(page.locator('tr[data-endpoint="DELETE /orders/{orderId}"]')).toHaveCount(0);
  });

  test('a new endpoint that was never saved is dropped without asking', async ({ page }) => {
    await open(page);
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await page.getByRole('button', { name: 'Remove endpoint GET /' }).click();
    await expect(dialog(page)).toHaveCount(0);
    await expect(page.getByRole('list', { name: 'Endpoints' }).getByRole('button')).toHaveCount(6);
    await expect(page.getByRole('status')).toContainText('No changes yet.');
  });

  test('refuses a bad path, an ambiguous or a duplicate endpoint before sending', async ({ page }) => {
    const api = await open(page);
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    const path = page.getByLabel('Relative path');

    await path.fill('users');
    await expect(path).toHaveAttribute('aria-invalid', 'true');
    await expect(path).toHaveClass(/input-error/);
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').first()).toContainText('Endpoint path must start with "/" and contain only latin letters, digits, "-", or a "{name}" parameter per segment (no consecutive "/", no repeated parameter names): GET users');

    await path.fill('/users/{id}/orders/{id}');
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').first()).toContainText('GET /users/{id}/orders/{id}');

    // Same method, same shape, other parameter name.
    await path.fill('/orders/{id}');
    await expect(path).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByText('Differs from another endpoint of the same method only in its parameter names')).toBeVisible();
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').first()).toContainText('Endpoint path differs from another endpoint of the same method only in its parameter names: GET /orders/{id}');

    await path.fill('/orders/{orderId}');
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').first()).toContainText('Two endpoints are GET /orders/{orderId}');

    expect(putBodies(api.requests)).toHaveLength(0);
  });

  test('blocks save on invalid CEL and clears the error once it is fixed', async ({ page }) => {
    const api = await open(page);
    await endpointRow(page, 'GET /orders/{orderId}').click();
    const allow = page.getByRole('textbox', { name: 'Allow expression' });
    await allow.fill('token.role ==');
    await expect(page.locator('versola-cel-editor').first()).toHaveAttribute('has-error', '');
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').filter({ hasText: /^Invalid allow expression in GET \/orders\/\{orderId\}: / })).toBeVisible();
    expect(putBodies(api.requests)).toHaveLength(0);

    await allow.fill("token.role == 'admin'");
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    expect((await findRequest(api.requests, 'PUT', '/configuration/resources')).body).toMatchObject({
      createEndpoints: [expect.objectContaining({ id: 2, allow: "token.role == 'admin'" })],
    });
  });

  test('blocks save on an invalid step-up condition, a bad max age and incomplete inject rules', async ({ page }) => {
    const api = await open(page);
    await endpointRow(page, 'POST /orders/{orderId}/refund').click();
    await page.getByRole('textbox', { name: 'Step-up condition' }).fill('(unterminated');
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').filter({ hasText: /^Invalid step-up condition in POST/ })).toBeVisible();
    await page.getByRole('textbox', { name: 'Step-up condition' }).fill('true');

    await page.getByLabel('Max auth age (seconds)').fill('1.5');
    await expect(page.getByText('Whole seconds, 0 or more.')).toBeVisible();
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'Max auth age must be whole seconds in POST /orders/{orderId}/refund' })).toBeVisible();
    await page.getByLabel('Max auth age (seconds)').fill('300');

    await page.getByRole('button', { name: 'Add rule' }).click();
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'Inject name is required in POST /orders/{orderId}/refund' })).toBeVisible();
    await page.getByRole('region', { name: 'Inject' }).getByLabel('Name').last().fill('X-Extra');
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').filter({ hasText: /^Inject expression is required in POST .* for X-Extra/ })).toBeVisible();
    await page.getByRole('textbox', { name: 'Inject expression' }).last().fill('(unterminated');
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').filter({ hasText: /^Invalid inject expression in POST .* for X-Extra/ })).toBeVisible();

    expect(putBodies(api.requests)).toHaveLength(0);
  });

  test('a rule is removed with its X, and a stored ACR the vocabulary lacks stays visible', async ({ page }) => {
    const api = await open(page, { ...shopState, resources: { [shopTenant]: [{
      ...ordersApi,
      endpoints: [{ id: 5, method: 'POST', path: '/orders/{orderId}/refund', fetchUserInfo: false, inject: [{ target: 'header', name: 'X-A', expression: 'token.sub' }], stepUpCondition: 'true', stepUpAcr: 'legacy' }],
    }] } });
    await expect(page.getByRole('checkbox', { name: /legacy/ })).toBeChecked();
    await expect(page.getByText('(not registered)')).toBeVisible();
    await page.getByRole('button', { name: 'Remove inject rule 1' }).click();
    await expect(page.getByText('No inject rules yet.')).toBeVisible();
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    expect((await findRequest(api.requests, 'PUT', '/configuration/resources')).body).toMatchObject({
      createEndpoints: [expect.objectContaining({ id: 5, inject: [], stepUpAcr: 'legacy' })],
    });
  });

  test('filters the list by text, /path and METHOD /path', async ({ page }) => {
    await open(page);
    const buttons = page.getByRole('list', { name: 'Endpoints' }).getByRole('button');
    await page.getByLabel('Filter endpoints').fill('POST /orders/{orderId}/refund');
    await expect(buttons).toHaveCount(1);
    await page.getByLabel('Filter endpoints').fill('/orders/{orderid}');
    await expect(buttons).toHaveCount(4);
    await page.getByLabel('Filter endpoints').fill('delete');
    await expect(buttons).toHaveCount(1);
    await page.getByLabel('Filter endpoints').fill('nothing-like-this');
    await expect(page.getByText('No endpoints match.')).toBeVisible();
  });

  test('the Allow (CEL) info lists the variables, operators and macros', async ({ page }) => {
    await open(page);
    await page.getByRole('button', { name: 'Allow expression info' }).click();
    const tooltip = page.getByRole('tooltip');
    await expect(tooltip).toContainText('Root variables');
    await expect(tooltip).toContainText('request.path.params');
    await expect(tooltip).toContainText('Operators');
    await expect(tooltip).toContainText('Macros');
    await expect(tooltip).toContainText('has(x.y)');
    await page.getByRole('heading', { level: 1 }).click();
    await expect(tooltip).toHaveCount(0);
  });

  test('required permissions are read-only here and say where they are linked', async ({ page }) => {
    await open(page);
    await endpointRow(page, 'PUT /orders/{orderId}/address').click();
    await expect(page.getByRole('region', { name: 'Who may call' })).toContainText('orders:write');
    await expect(page.getByRole('region', { name: 'Who may call' })).toContainText('Linked from Permissions');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Who may call' })).toContainText('Link it from Permissions once the endpoint is saved');
  });

  test('Cancel asks when there are changes; central\'s answer to a save stays on the page', async ({ page }) => {
    await open(page);
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'orders-api' })).toBeVisible();

    await card(page, 'Endpoints').getByRole('button', { name: 'Edit endpoints' }).click();
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog(page)).toContainText('Discard changes?');
    await dialog(page).getByRole('button', { name: 'Keep editing' }).click();

    await page.route('**/configuration/resources', route => route.request().method() === 'PUT'
      ? route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ message: 'Endpoint GET / is ambiguous' }) })
      : route.fallback());
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert')).toContainText('Endpoint GET / is ambiguous');
    await expect(page.getByRole('heading', { level: 1, name: 'Edit endpoints' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save endpoints' })).toBeEnabled();
  });

  test('a resource with no endpoints starts empty and adds the first one', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('loyalty-api', 'endpoints'), state: shopState });
    await expect(page.getByText('No endpoints yet.', { exact: true })).toBeVisible();
    await expect(page.getByText('This resource has no endpoints yet. Add one to start.')).toBeVisible();
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await page.getByLabel('Relative path').fill('/points');
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    expect((await findRequest(api.requests, 'PUT', '/configuration/resources')).body).toMatchObject({
      resourceId: 'loyalty-api', deleteEndpoints: [], createEndpoints: [expect.objectContaining({ method: 'GET', path: '/points', allow: null })],
    });
    await expect(page.locator('tr[data-endpoint="GET /points"]')).toBeVisible();
  });

  test('fits 375 without sideways scroll', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await open(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  });
});

test.describe('secret', () => {
  const quiet = { ...ordersApi, secretRotation: false };
  const state = { ...shopState, resources: { [shopTenant]: [quiet, catalogApi] } };

  test('rotate shows the new secret once; Activate new secret confirms and ends the rotation', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('orders-api'), state });
    await expect(page.getByText('A new secret was issued')).toHaveCount(0);
    await card(page, 'Secret').getByRole('button', { name: 'Rotate secret' }).click();

    expect((await findRequest(api.requests, 'POST', '/configuration/resources/rotate-secret')).searchParams).toEqual({ resourceId: 'orders-api' });
    await expect(page.getByText('Secret rotated: orders-api')).toBeVisible();
    await expect(page.locator('.secret-value')).toHaveText('rotated-orders-api');
    await expect(page.getByText('A new secret was issued')).toBeVisible();
    await expect(page.locator('versola-record-header')).toContainText('Secret rotation');
    await expect(card(page, 'Secret')).toContainText('Waiting for activation');
    await expect(card(page, 'Secret').getByRole('button', { name: 'Rotate secret' })).toHaveCount(0);

    await page.getByRole('button', { name: "I've saved it" }).click();
    await expect(page.locator('.secret-value')).toHaveCount(0);

    await page.getByRole('region', { name: 'Secret' }).getByRole('button', { name: 'Activate new secret' }).click();
    await expect(dialog(page)).toContainText('Do this only after the upstream accepts the new secret');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    expect(api.requests.some(request => request.method === 'DELETE' && request.pathname.endsWith('previous-secret'))).toBe(false);

    await page.getByRole('region', { name: 'Secret' }).getByRole('button', { name: 'Activate new secret' }).click();
    await dialog(page).getByRole('button', { name: 'Activate', exact: true }).click();
    expect((await findRequest(api.requests, 'DELETE', '/configuration/resources/previous-secret')).searchParams).toEqual({ resourceId: 'orders-api' });
    await expect(page.getByText('A new secret was issued')).toHaveCount(0);
    await expect(card(page, 'Secret')).not.toContainText('Waiting for activation');
    await expect(card(page, 'Secret').getByRole('button', { name: 'Rotate secret' })).toBeVisible();
  });

  test('the banner can activate too, and copying says so', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    const api = await loadAdminApp(page, { path: recordPath('orders-api'), state });
    await card(page, 'Secret').getByRole('button', { name: 'Rotate secret' }).click();
    await page.getByRole('button', { name: 'Copy secret' }).click();
    await expect(page.getByText('Secret copied to clipboard.')).toBeVisible();

    await page.locator('.banner.warn').getByRole('button', { name: 'Activate new secret' }).click();
    await dialog(page).getByRole('button', { name: 'Activate', exact: true }).click();
    await findRequest(api.requests, 'DELETE', '/configuration/resources/previous-secret');
  });

  test('leaving the page with an unsaved secret asks first', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('orders-api'), state });
    await card(page, 'Secret').getByRole('button', { name: 'Rotate secret' }).click();
    await expect(page.locator('.secret-value')).toBeVisible();

    const crumb = page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Resources' });
    await crumb.click();
    await expect(dialog(page)).toContainText('Leave without saving the secret?');
    await dialog(page).getByRole('button', { name: 'Stay' }).click();
    await expect(page.locator('.secret-value')).toBeVisible();

    await crumb.click();
    await dialog(page).getByRole('button', { name: 'Leave' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Resources' })).toBeVisible();
  });

  test('a failed rotation says so and offers no secret', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('orders-api'), state });
    await page.route('**/rotate-secret*', route => route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message: 'A rotation is already in progress' }) }));
    await card(page, 'Secret').getByRole('button', { name: 'Rotate secret' }).click();
    await expect(page.getByRole('alert')).toContainText('A rotation is already in progress');
    await expect(page.locator('.secret-value')).toHaveCount(0);
  });
});

test.describe('create', () => {
  const open = (page: Page, state: object = shopState) => loadAdminApp(page, { path: `${listPath}&new=1`, state });

  test('a public resource is one form; it lands on the new record', async ({ page }) => {
    const api = await open(page);
    await expect(page.getByRole('heading', { level: 1, name: 'Create resource' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Public' })).toHaveAttribute('aria-pressed', 'true');

    await page.getByLabel('Resource ID').fill('invoices-api');
    await page.getByLabel('Absolute resource URI').fill('https://invoices.example.com');
    await page.getByRole('checkbox', { name: /storefront-web/ }).check();
    await page.getByRole('button', { name: 'Create resource', exact: true }).click();

    expect((await findRequest(api.requests, 'POST', '/configuration/resources')).body).toEqual({
      tenantId: shopTenant, resourceId: 'invoices-api', resource: 'https://invoices.example.com',
      audience: ['storefront-web'], endpoints: [], internal: false,
    });
    await expect(page.getByRole('heading', { level: 1, name: 'invoices-api' })).toBeVisible();
    await expect(page).toHaveURL(/id=invoices-api/);
    await expect(page.locator('.secret-value')).toHaveCount(0);
    await expect(card(page, 'Endpoints')).toContainText('No endpoints yet.');
    await expect(card(page, 'Audience')).toContainText('storefront-web');
    expect(api.requests.filter(request => request.method === 'GET' && request.pathname === '/configuration/resources')).toHaveLength(1);
  });

  test('an internal resource shows its secret once on the new record', async ({ page }) => {
    const api = await open(page);
    await page.getByLabel('Resource ID').fill('invoices-api');
    await page.getByLabel('Absolute resource URI').fill('https://invoices.example.com');
    await page.getByRole('button', { name: /^Internal/ }).click();
    await expect(page.getByRole('button', { name: /^Internal/ })).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('button', { name: 'Create resource', exact: true }).click();

    expect((await findRequest(api.requests, 'POST', '/configuration/resources')).body).toMatchObject({ internal: true, audience: [] });
    await expect(page.getByText('Resource created: invoices-api')).toBeVisible();
    await expect(page.locator('.secret-value')).toHaveText('generated-invoices-api');
    await expect(page.locator('versola-record-header')).toContainText('Internal');
    await expect(card(page, 'Secret')).toContainText('Set. It is shown once');
    await page.getByRole('button', { name: "I've saved it" }).click();
    await expect(page.locator('.secret-value')).toHaveCount(0);
  });

  test('shows what is wrong under each field and sends nothing', async ({ page }) => {
    const api = await open(page);
    const id = page.getByLabel('Resource ID');
    const uri = page.getByLabel('Absolute resource URI');

    await page.getByRole('button', { name: 'Create resource', exact: true }).click();
    await expect(page.getByText('A resource ID is required.')).toBeVisible();
    await expect(page.getByText('A resource URI is required.')).toBeVisible();

    await id.fill('Users_api');
    await expect(id).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByText('Resource ID must start with a lowercase Latin letter and contain only lowercase Latin letters, numbers, and hyphens')).toBeVisible();
    await id.fill('edge');
    await expect(page.getByText('Resource ID "edge" is reserved')).toBeVisible();
    await id.fill('orders-api');
    await expect(page.getByText('A resource with this ID already exists.')).toBeVisible();

    await uri.fill('https://alpha.example/api');
    await expect(page.getByText('Resource URI path must be empty')).toBeVisible();
    await uri.fill('resource://foo');
    await expect(page.getByText('Resource URI scheme resource:// is reserved')).toBeVisible();
    await uri.fill('alpha.example');
    await expect(page.getByText('Resource URI must be absolute')).toBeVisible();

    await page.getByRole('button', { name: 'Create resource', exact: true }).click();
    expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/resources')).toBe(false);
  });

  test('keeps the form and shows central\'s answer when create fails', async ({ page }) => {
    await open(page);
    await page.route('**/configuration/resources', route => route.request().method() === 'POST'
      ? route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message: 'Resource already exists in another tenant' }) })
      : route.fallback());
    await page.getByLabel('Resource ID').fill('invoices-api');
    await page.getByLabel('Absolute resource URI').fill('https://invoices.example.com');
    await page.getByRole('button', { name: 'Create resource', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Resource already exists in another tenant');
    await expect(page.getByLabel('Resource ID')).toHaveValue('invoices-api');
  });

  test('Cancel asks only when something was typed', async ({ page }) => {
    await open(page);
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Resources' })).toBeVisible();

    await page.getByRole('button', { name: '+ Create Resource' }).click();
    await page.getByLabel('Resource ID').fill('half-done');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog(page)).toContainText('Discard changes?');
    await dialog(page).getByRole('button', { name: 'Keep editing' }).click();
    await expect(page.getByLabel('Resource ID')).toHaveValue('half-done');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await dialog(page).getByRole('button', { name: 'Discard' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Resources' })).toBeVisible();
  });

  test('fits 375 without sideways scroll', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await open(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  });
});

test.describe('delete', () => {
  test('the danger card confirms, names the permissions that detach, deletes and returns to the list', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('orders-api'), state: shopState });
    await page.getByRole('button', { name: 'Delete resource orders-api' }).click();
    await expect(dialog(page)).toContainText('Delete resource orders-api?');
    await expect(dialog(page)).toContainText('/resources/orders-api/*');
    await expect(dialog(page)).toContainText('are linked to its endpoints and become detached');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'orders-api' })).toBeVisible();

    await page.getByRole('button', { name: 'Delete resource orders-api' }).click();
    await dialog(page).getByRole('button', { name: 'Delete', exact: true }).click();
    expect((await findRequest(api.requests, 'DELETE', '/configuration/resources')).searchParams).toEqual({ resourceId: 'orders-api' });
    await expect(page.getByRole('heading', { level: 1, name: 'Resources' })).toBeVisible();
    await expect(page.getByText('Deleted resource orders-api.')).toBeVisible();
    await expect(resourceRow(page, 'orders-api')).toHaveCount(0);
  });

  test('a resource nothing is linked to says so', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('loyalty-api'), state: shopState });
    await page.getByRole('button', { name: 'Delete resource loyalty-api' }).click();
    await expect(dialog(page)).toContainText('No permission is linked to its endpoints.');
  });

  test('a failed delete stays on the record and says why', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('catalog-api'), state: shopState });
    await page.route('**/configuration/resources?*', route => route.request().method() === 'DELETE'
      ? route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ message: 'Database unavailable' }) })
      : route.fallback());
    await page.getByRole('button', { name: 'Delete resource catalog-api' }).click();
    await dialog(page).getByRole('button', { name: 'Delete', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Database unavailable');
    await expect(page.getByRole('heading', { level: 1, name: 'catalog-api' })).toBeVisible();
  });
});
