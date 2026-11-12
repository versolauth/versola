import { expect, test } from '@playwright/test';
import { expectSheetWidth, findRequest } from '../fixtures';
import { scan } from './axe';
import { loadPartnerApp, sidePanel, sidePanelDialog } from './fixtures';
import { PARTNER_NOW, asLevel } from './partner-fixtures';

/**
 * Resources, the partner's own APIs: the list, the record with its endpoints and secret, Add
 * resource with the secret shown once, Edit endpoints on the shared editor with the sample-token
 * box beside it, rotate, delete.
 */
const resourcesPath = '/partner.html?view=resources';

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('lists the resources, opens one with its endpoints, the secret and the limits from the operator', async ({ page }) => {
  await loadPartnerApp(page, { path: resourcesPath });
  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Resource', 'Audience', 'Endpoints', 'Secret', 'Actions']);
  await expect(table.getByRole('row').filter({ hasText: 'pilot-wallet' })).toContainText('Previous secret kept');
  await expect(table.getByRole('row').filter({ hasText: 'pilot-orders' })).toContainText('current');
  expect(await scan(page)).toEqual([]);

  await page.getByRole('button', { name: /^Open resource pilot-orders/ }).click();
  const panel = sidePanel(page, 'pilot-orders');
  await expect(sidePanelDialog(page, 'pilot-orders')).toBeVisible();
  await expect(panel.getByRole('region', { name: 'General' })).toContainText('https://login.pilotpay.example/resources/pilot-orders/…');
  await expect(panel.getByRole('region', { name: 'General' })).toContainText('pilot-pay-ios');
  await expect(panel.getByRole('region', { name: 'Secret' })).toContainText('Current');
  await expect(panel.getByRole('region', { name: 'Endpoints' }).locator('tr[data-endpoint]')).toHaveCount(4);
  await expect(panel.getByRole('region', { name: 'Endpoints' })).toContainText("'profile' in token.scope");
  await expect(panel.getByRole('region', { name: 'Limits from the operator' })).toContainText('partner-default');
  await expect(panel.getByRole('region', { name: 'Limits from the operator' })).toContainText('sensitive');
  await expect(panel.getByRole('button', { name: 'Edit endpoints' })).toBeVisible();
  expect(await scan(page)).toEqual([]);

  await page.getByLabel('Search resources').fill('wallet');
  await expect(table.getByRole('button', { name: /^Open resource/ })).toHaveText([/pilot-wallet/]);
});

test('adds a resource over the partner\'s clients only and shows the secret once', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: resourcesPath });
  await page.getByRole('button', { name: '+ Add resource' }).click();
  const editor = sidePanel(page, 'Add resource');
  await expect(page).toHaveURL(/new=1/);
  await expect(editor.getByRole('group', { name: 'Audience' }).getByRole('checkbox')).toHaveCount(3);
  await expect(editor.getByRole('group', { name: 'Audience' })).toContainText('not approved yet');
  await expect(editor.getByText('The secret your edge authenticates to the upstream with is generated now and shown once on the record.')).toBeVisible();
  expect(await scan(page)).toEqual([]);

  await editor.getByRole('button', { name: 'Add resource' }).click();
  await expect(editor.getByRole('alert')).toHaveCount(2);
  await editor.getByLabel('Resource ID *').fill('pilot-loyalty');
  await expect(editor.getByText('https://login.pilotpay.example/resources/pilot-loyalty/')).toBeVisible();
  await editor.getByLabel('Upstream URI *').fill('http://loyalty.pilotpay.internal:8080');
  await editor.getByRole('checkbox', { name: /pilot-pay-web/ }).check();
  await editor.getByRole('button', { name: 'Add resource' }).click();

  const request = await findRequest(api.requests, 'POST', '/resources');
  expect(request.body).toEqual({ resourceId: 'pilot-loyalty', resource: 'http://loyalty.pilotpay.internal:8080', audience: ['pilot-pay-web'], endpoints: [] });
  const panel = sidePanel(page, 'pilot-loyalty');
  await expect(panel.getByRole('status').first()).toContainText('Secret created. Save it now: it is not shown again.');
  await expect(panel.locator('.secret-value')).toHaveText('generated-pilot-loyalty');
  await expect(panel.getByRole('region', { name: 'Endpoints' })).toContainText('No endpoints yet.');
  expect(await scan(page)).toEqual([]);
  await panel.getByRole('button', { name: "I've saved it" }).click();
  await expect(panel.locator('.secret-value')).toHaveCount(0);
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open resource/ })).toHaveCount(3);
});

test('rotates the secret and deletes the resource through the confirm dialogs', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${resourcesPath}&id=pilot-orders` });
  const panel = sidePanel(page, 'pilot-orders');
  await panel.getByRole('button', { name: 'Rotate secret' }).click();
  await expect(page.getByRole('dialog', { name: 'Rotate secret' })).toContainText('the previous one keeps working until you retire it');
  await page.getByRole('dialog', { name: 'Rotate secret' }).getByRole('button', { name: 'Rotate' }).click();
  await findRequest(api.requests, 'POST', '/resources/pilot-orders/rotate-secret');
  await expect(panel.getByRole('status').first()).toContainText('Secret rotated.');
  await expect(panel.locator('.secret-value')).toHaveText('rotated-pilot-orders');
  await expect(panel.getByRole('region', { name: 'Secret' })).toContainText('Previous secret kept');

  await panel.getByRole('button', { name: 'Delete resource pilot-orders' }).click();
  await page.getByRole('dialog', { name: 'Delete resource' }).getByRole('button', { name: 'Delete', exact: true }).click();
  await findRequest(api.requests, 'DELETE', '/resources/pilot-orders');
  await expect(panel).toHaveCount(0);
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open resource/ })).toHaveText([/pilot-wallet/]);
});

test('edits the general card: the audience over the partner\'s clients, the upstream validated', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${resourcesPath}&id=pilot-wallet&edit=general` });
  const editor = sidePanel(page, 'Edit general');
  await expect(editor.getByLabel('Resource ID *')).toHaveCount(0);
  await editor.getByLabel('Upstream URI *').fill('resource://wallet');
  await editor.getByRole('button', { name: 'Save general' }).click();
  await expect(editor.getByRole('alert')).toContainText('reserved');
  await editor.getByLabel('Upstream URI *').fill('http://wallet-v2.pilotpay.internal:8080');
  await editor.getByRole('checkbox', { name: /pilot-pay-web/ }).check();
  await editor.getByRole('button', { name: 'Save general' }).click();
  expect((await findRequest(api.requests, 'PUT', '/resources/pilot-wallet')).body).toEqual({ resourceId: 'pilot-wallet', resource: 'http://wallet-v2.pilotpay.internal:8080', audience: ['pilot-pay-ios', 'pilot-pay-web'] });
  await expect(sidePanel(page, 'pilot-wallet').getByRole('region', { name: 'General' })).toContainText('wallet-v2');
});

test('Edit endpoints is the shared editor with the sample-token box beside it', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${resourcesPath}&id=pilot-orders&edit=endpoints` });
  await expect(page.getByRole('heading', { level: 1, name: 'Edit endpoints' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Endpoints' }).getByRole('button')).toHaveCount(4);
  const test_ = page.getByRole('region', { name: 'Test with a sample token' });
  await expect(test_.getByLabel('Endpoint')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Applies' })).toContainText('next sync');
  expect(await scan(page)).toEqual([]);

  // The sample as given: profile scope, amount 12000, acr sensitive.
  await test_.getByRole('button', { name: 'Evaluate' }).click();
  await expect(test_.getByRole('status')).toHaveText('Allowed: GET /orders · step-up not required');
  await test_.getByLabel('Endpoint').selectOption({ label: 'POST /orders' });
  await test_.getByRole('button', { name: 'Evaluate' }).click();
  await expect(test_.getByRole('status')).toHaveText('Allowed: POST /orders · step-up not required');
  await test_.getByLabel('Sample: token, user, request').fill(JSON.stringify({ token: { sub: 'u_1', scope: ['openid', 'profile'], amr: ['otp'], acr: 'otp' }, user: { email_verified: true }, request: { body: { amount: 60000 } } }));
  await test_.getByRole('button', { name: 'Evaluate' }).click();
  await expect(test_.getByRole('status')).toHaveText('Step-up required: POST /orders asks for acr sensitive, the token has otp');
  await test_.getByLabel('Endpoint').selectOption({ label: 'DELETE /orders/{id}' });
  await test_.getByRole('button', { name: 'Evaluate' }).click();
  await expect(test_.getByRole('status')).toHaveText('Refused: DELETE /orders/{id} · allow is false for this token');
  await test_.getByLabel('Sample: token, user, request').fill('{not json');
  await test_.getByRole('button', { name: 'Evaluate' }).click();
  await expect(test_.getByRole('status')).toHaveText('The sample is not valid JSON.');

  // A change saves through the partner API and lands on the record.
  await page.getByRole('list', { name: 'Endpoints' }).getByRole('button', { name: /^GET \/orders\/\{id\}/ }).click();
  await page.getByRole('textbox', { name: 'Allow expression' }).fill("'profile' in token.scope && token.sub != ''");
  await page.getByRole('button', { name: 'Save endpoints' }).click();
  const request = await findRequest(api.requests, 'PUT', '/resources/pilot-orders');
  const endpoints = (request.body as { endpoints: Array<{ id: unknown; path: string; allow?: string }> }).endpoints;
  expect(endpoints).toHaveLength(4);
  expect(endpoints.find(endpoint => endpoint.id === 403)).toMatchObject({ path: '/orders/{id}', allow: "'profile' in token.scope && token.sub != ''" });
  await expect(sidePanel(page, 'pilot-orders').getByRole('region', { name: 'Endpoints' })).toContainText("token.sub != ''");
});

test('a viewer sees the record without the editors or the secret actions', async ({ page }) => {
  await loadPartnerApp(page, { path: `${resourcesPath}&id=pilot-orders`, state: asLevel('viewer') });
  await expect(page.getByRole('button', { name: '+ Add resource' })).toHaveCount(0);
  const panel = sidePanel(page, 'pilot-orders');
  await expect(panel.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(panel.getByRole('button', { name: 'Rotate secret' })).toHaveCount(0);
  await expect(panel.getByRole('region', { name: 'Delete resource' })).toHaveCount(0);
  expect(await scan(page)).toEqual([]);
});

test('the empty state, and the record as a sheet below 720px', async ({ page }) => {
  await loadPartnerApp(page, { path: resourcesPath, state: { resources: [] } });
  await expect(page.getByRole('heading', { name: 'No resources yet' })).toBeVisible();
  await page.getByRole('button', { name: '+ Add resource' }).click();
  await expect(sidePanelDialog(page, 'Add resource')).toBeVisible();

  await page.setViewportSize({ width: 375, height: 812 });
  await loadPartnerApp(page, { path: `${resourcesPath}&id=pilot-orders` });
  const dialog = sidePanelDialog(page, 'pilot-orders');
  await expect(dialog).toBeVisible();
  await expectSheetWidth(page, (await dialog.boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect(await scan(page)).toEqual([]);
});
