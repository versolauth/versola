import { expect, test } from '@playwright/test';
import { findRequest } from '../fixtures';
import { scan } from './axe';
import { loadPartnerApp } from './fixtures';
import { PARTNER_NOW, asLevel, centralDown, partnerSeedState } from './partner-fixtures';

/**
 * Sessions & revocation: a partner ends sessions on its own edges only. The tokens live in those
 * sessions, so the operator's auth is never contacted and central is not needed. Admins only.
 */
const revocationPath = '/partner.html?view=revocation';

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('ends a user\'s sessions on chosen edges and clients after a confirm, without touching auth', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: revocationPath });
  await expect(page.getByRole('heading', { level: 1, name: 'Sessions & revocation' })).toBeVisible();
  const scope = page.getByRole('group', { name: 'What to end' });
  await expect(scope.getByRole('button')).toHaveText(['A user', 'A session', 'A client', 'A refresh family']);
  const clients = page.getByRole('group', { name: 'Clients *' });
  await expect(clients.getByRole('checkbox')).toHaveCount(2);
  await expect(clients).not.toContainText('pilot-pay-checkout');
  const edges = page.getByRole('group', { name: 'Edges *' });
  await expect(edges.getByRole('checkbox')).toHaveCount(2);
  await expect(edges).not.toContainText('edge-pilot-kz-1');
  const what = page.getByRole('region', { name: 'What it does' });
  await expect(what).toContainText('not contacted');
  await expect(what).not.toContainText('back-channel');
  await expect(page.getByRole('checkbox', { name: /refresh tokens/ })).toHaveCount(0);
  const recent = page.getByRole('region', { name: 'Recently ended' });
  await expect(recent.getByRole('row').filter({ hasText: 'Marat Lee' })).toContainText('3 sessions');
  await expect(recent.getByRole('row').filter({ hasText: 'Dana Kim' })).toContainText('1284 sessions');
  await expect(recent.getByRole('row').filter({ hasText: 'Dana Kim' })).toContainText('edge-pilot-eu-2');
  expect(await scan(page)).toEqual([]);

  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('A value is required.');
  expect(api.requests.some(request => request.method === 'POST')).toBe(false);
  await page.getByLabel('User (sub) *').fill('u_2b91d4e0');
  await clients.getByRole('checkbox', { name: /pilot-pay-ios/ }).uncheck();
  await edges.getByRole('checkbox', { name: /edge-pilot-eu-2/ }).uncheck();
  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'End sessions' });
  await expect(dialog).toContainText('End the sessions of user u_2b91d4e0 on pilot-pay-web on edge-pilot-eu-1?');
  await expect(dialog).toContainText("The operator's auth is not contacted");
  await dialog.getByRole('button', { name: 'End sessions' }).click();

  expect((await findRequest(api.requests, 'POST', '/revocations')).body).toEqual({ by: 'user', value: 'u_2b91d4e0', clients: ['pilot-pay-web'], edges: ['edge-pilot-eu-1'] });
  expect(api.requests.every(request => request.pathname !== '/me' || request.method === 'GET')).toBe(true);
  await expect(page.getByRole('status').filter({ hasText: 'Ended 3 sessions' })).toBeVisible();
  await expect(recent.getByRole('row').filter({ hasText: 'u_2b91d4e0' }).first()).toContainText('3 sessions');
  await expect(page.getByLabel('User (sub) *')).toHaveValue('');
});

test('a client scope needs no value; a session and a refresh family take an id', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: revocationPath });
  const scope = page.getByRole('group', { name: 'What to end' });
  await scope.getByRole('button', { name: 'A client' }).click();
  await expect(page.getByLabel('User (sub) *')).toHaveCount(0);
  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  await page.getByRole('dialog', { name: 'End sessions' }).getByRole('button', { name: 'End sessions' }).click();
  expect((await findRequest(api.requests, 'POST', '/revocations')).body).toMatchObject({ by: 'client', value: 'pilot-pay-web,pilot-pay-ios', clients: ['pilot-pay-web', 'pilot-pay-ios'], edges: ['edge-pilot-eu-1', 'edge-pilot-eu-2'] });
  await expect(page.getByRole('status').filter({ hasText: 'Ended 1284 sessions' })).toBeVisible();

  await scope.getByRole('button', { name: 'A refresh family' }).click();
  await expect(page.getByText('One refresh-token family, as the fam_id claim names it.')).toBeVisible();
  await page.getByLabel('Family (fam_id) *').fill('fam_8c1d');
  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  await page.getByRole('dialog', { name: 'End sessions' }).getByRole('button', { name: 'End sessions' }).click();
  expect((await findRequest(api.requests, 'POST', '/revocations')).body).toMatchObject({ by: 'fam', value: 'fam_8c1d' });
  await expect(page.getByRole('status').filter({ hasText: 'Ended 1 session' })).toBeVisible();

  await scope.getByRole('button', { name: 'A session' }).click();
  await expect(page.getByLabel('Session (sid) *')).toBeVisible();
});

test('at least one client and one edge must stay chosen', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: revocationPath });
  await page.getByLabel('User (sub) *').fill('u_1');
  for (const name of [/edge-pilot-eu-1/, /edge-pilot-eu-2/]) await page.getByRole('group', { name: 'Edges *' }).getByRole('checkbox', { name }).uncheck();
  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Pick at least one edge.');
  expect(api.requests.some(request => request.method === 'POST')).toBe(false);
});

test('a developer sees the recent list and cannot end sessions', async ({ page }) => {
  await loadPartnerApp(page, { path: revocationPath, state: asLevel('developer') });
  await expect(page.getByText('Admins end sessions')).toBeVisible();
  await expect(page.getByText('Your level is developer')).toBeVisible();
  await expect(page.getByRole('button', { name: 'End sessions', exact: true })).toBeDisabled();
  await expect(page.getByLabel('User (sub) *')).toBeDisabled();
  await expect(page.getByRole('region', { name: 'Recently ended' }).getByRole('row')).toHaveCount(3);
  expect(await scan(page)).toEqual([]);
});

test('with central unavailable the form cannot read the clients and edges it chooses from, and says so', async ({ page }) => {
  await loadPartnerApp(page, { path: revocationPath, state: centralDown });
  await expect(page.getByRole('alert')).toContainText('Your clients and edges could not be read, so there is nothing to choose from.');
  await expect(page.getByRole('alert')).toContainText('Central is unavailable right now');
  await expect(page.getByRole('group', { name: 'Clients *' })).toContainText('No approved clients yet.');
  expect(await scan(page)).toEqual([]);
});

test('with no enrolled edge there is nothing to end sessions on; with none ended the list says so', async ({ page }) => {
  await loadPartnerApp(page, { path: revocationPath, state: { revocations: [], edges: partnerSeedState.edges.filter(edge => edge.status === 'waiting') } });
  await expect(page.getByRole('region', { name: 'Recently ended' })).toContainText('Nothing ended yet.');
  await expect(page.getByRole('group', { name: 'Edges *' })).toContainText('No enrolled edge yet.');
  await page.getByLabel('User (sub) *').fill('u_1');
  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('No enrolled edge to end sessions on.');
  expect(await scan(page)).toEqual([]);
});

test('a recent row the edge sent without a kind or clients still lists, as the real edge sends them', async ({ page }) => {
  const [first] = partnerSeedState.revocations;
  await loadPartnerApp(page, { path: revocationPath, state: { revocations: [{ ...first, by: '' as never, clients: undefined as never, edges: undefined as never }] } });
  const recent = page.getByRole('region', { name: 'Recently ended' });
  await expect(recent.getByRole('row')).toHaveCount(2);
  await expect(recent).toContainText(first.value);
});
