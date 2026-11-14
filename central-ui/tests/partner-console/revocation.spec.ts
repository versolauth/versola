import { expect } from '@playwright/test';
import { findRequest } from '../fixtures';
import { scan } from './axe';
import { loadPartnerApp, test } from './fixtures';
import { PARTNER_NOW, asLevel, centralDown, partnerSeedState } from './partner-fixtures';

/**
 * Sessions & revocation: a partner ends the sessions held by the edge that serves its console. The tokens live in those
 * sessions, so the operator's auth is never contacted and central is not needed. Admins only.
 */
const revocationPath = '/partner.html?view=revocation';

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('ends a user\'s sessions on this edge after a confirm, without touching auth', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: revocationPath });
  await expect(page.getByRole('heading', { level: 1, name: 'Sessions & revocation' })).toBeVisible();
  const scope = page.getByRole('group', { name: 'What to end' });
  await expect(scope.getByRole('button')).toHaveText(['A user', 'A session', 'A refresh family']);
  await expect(page.getByText('On the edge that serves this console, edge-pilot-eu-1')).toBeVisible();
  // The edge ends sessions of its own store only: nothing to choose from, no client, no edge.
  await expect(page.getByRole('group', { name: 'Clients *' })).toHaveCount(0);
  await expect(page.getByRole('group', { name: 'Edges *' })).toHaveCount(0);
  const what = page.getByRole('region', { name: 'What it does' });
  await expect(what).toContainText('not contacted');
  await expect(what).toContainText('until it expires');
  await expect(what).toContainText('your other edges');
  const recent = page.getByRole('region', { name: 'Recently ended' });
  await expect(recent.getByRole('columnheader')).toHaveText(['When', 'By', 'What', 'Ended']);
  await expect(recent.getByRole('row').filter({ hasText: 'Marat Lee' })).toContainText('3 sessions');
  await expect(recent.getByRole('row').filter({ hasText: 'Dana Kim' })).toContainText('1 session');
  expect(await scan(page)).toEqual([]);

  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('A value is required.');
  expect(api.requests.some(request => request.method === 'POST')).toBe(false);
  await page.getByLabel('User (sub) *').fill('u_2b91d4e0');
  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'End sessions' });
  await expect(dialog).toContainText('End the sessions of user u_2b91d4e0 on this edge?');
  await expect(dialog).toContainText("The operator's auth is not contacted");
  await dialog.getByRole('button', { name: 'End sessions' }).click();

  expect((await findRequest(api.requests, 'POST', '/revocations')).body).toEqual({ by: 'user', value: 'u_2b91d4e0' });
  await expect(page.getByRole('status').filter({ hasText: 'Ended 3 sessions' })).toBeVisible();
  await expect(recent.getByRole('row').filter({ hasText: 'u_2b91d4e0' }).first()).toContainText('3 sessions');
  await expect(page.getByLabel('User (sub) *')).toHaveValue('');
});

test('a session and a refresh family take an id; there is no way to end a client', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: revocationPath });
  const scope = page.getByRole('group', { name: 'What to end' });
  await scope.getByRole('button', { name: 'A refresh family' }).click();
  await expect(page.getByText('One refresh-token family, as the fam_id claim names it.')).toBeVisible();
  await page.getByLabel('Family (fam_id) *').fill('fam_8c1d');
  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  await page.getByRole('dialog', { name: 'End sessions' }).getByRole('button', { name: 'End sessions' }).click();
  expect((await findRequest(api.requests, 'POST', '/revocations')).body).toEqual({ by: 'fam', value: 'fam_8c1d' });
  await expect(page.getByRole('status').filter({ hasText: 'Ended 1 session' })).toBeVisible();

  await scope.getByRole('button', { name: 'A session' }).click();
  await expect(page.getByLabel('Session (sid) *')).toBeVisible();
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

test('central being unavailable changes nothing here: the edge ends its own sessions', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: revocationPath, state: centralDown });
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.getByLabel('User (sub) *').fill('u_1');
  await page.getByRole('button', { name: 'End sessions', exact: true }).click();
  await page.getByRole('dialog', { name: 'End sessions' }).getByRole('button', { name: 'End sessions' }).click();
  await findRequest(api.requests, 'POST', '/revocations');
  await expect(page.getByRole('status').filter({ hasText: 'Ended 3 sessions' })).toBeVisible();
  expect(await scan(page)).toEqual([]);
});

test('with none ended the list says so', async ({ page }) => {
  await loadPartnerApp(page, { path: revocationPath, state: { revocations: [] } });
  await expect(page.getByRole('region', { name: 'Recently ended' })).toContainText('Nothing ended yet.');
});

test('a recent row an older edge recorded, without a kind, still lists', async ({ page }) => {
  const [first] = partnerSeedState.revocations;
  await loadPartnerApp(page, { path: revocationPath, state: { revocations: [{ ...first, by: '' as never, clients: undefined as never, edges: undefined as never }] } });
  const recent = page.getByRole('region', { name: 'Recently ended' });
  await expect(recent.getByRole('row')).toHaveCount(2);
  await expect(recent).toContainText(first.value);
});
