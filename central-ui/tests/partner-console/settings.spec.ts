import { expect } from '@playwright/test';
import { findRequest } from '../fixtures';
import { scan } from './axe';
import { loadPartnerApp, test } from './fixtures';
import { PARTNER_NOW, asLevel, suspendedPartner } from './partner-fixtures';

/**
 * Settings: the organisation as users see it (a change goes to the operator), contacts, the
 * defaults for new clients, the agreement read-only, and the operator's endpoints.
 */
const settingsPath = '/partner.html?view=settings';

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('shows the organisation, the contacts, the defaults, the agreement and the endpoints', async ({ page }) => {
  await loadPartnerApp(page, { path: settingsPath });
  const organisation = page.getByRole('region', { name: 'Organisation' });
  await expect(organisation).toContainText('Pilot Pay');
  await expect(organisation.getByText('Operator approves')).toHaveCount(2);
  await expect(organisation).toContainText('pilot-pay');
  await expect(organisation.getByRole('button', { name: 'Request change' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Contacts' })).toContainText('security@pilotpay.example');
  await expect(page.getByRole('region', { name: 'Defaults for new clients' })).toContainText('access 5 min · refresh 14 days');
  const agreement = page.getByRole('region', { name: 'Your agreement with Acme Retail' });
  await expect(agreement).toContainText('active');
  await expect(agreement).toContainText('Nov 27, 2026');
  await expect(agreement).toContainText('10 clients · 3 edges · access ≤ 1 day · refresh ≤ 30 days');
  await expect(agreement).toContainText('group certificate or private_key_jwt');
  await expect(agreement.getByRole('button')).toHaveCount(0);
  const endpoints = page.getByRole('region', { name: 'Operator endpoints' });
  await expect(endpoints).toContainText('id-mtls.acme.example:443');
  await expect(endpoints).toContainText('central-mtls.acme.example:443');
  await expect(endpoints).toContainText('central.acme.example:443');
  expect(await scan(page)).toEqual([]);
});

test('a change of name or logo goes to the operator and shows as waiting', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: settingsPath });
  await page.getByRole('button', { name: 'Request change' }).click();
  await expect(page).toHaveURL(/edit=profile/);
  await expect(page.getByRole('heading', { level: 1, name: 'Request a change of name or logo' })).toBeVisible();
  await expect(page.getByText('Acme Retail approves the name and the logo')).toBeVisible();
  await expect(page.getByLabel('Display name *')).toHaveValue('Pilot Pay');
  expect(await scan(page)).toEqual([]);

  await page.getByLabel('Display name *').fill('');
  await page.getByRole('button', { name: 'Send for approval' }).click();
  await expect(page.getByRole('alert')).toHaveText('A display name is required.');
  await page.getByLabel('Display name *').fill('Pilot Pay Group');
  await page.getByLabel('Logo URL').fill('https://pay.pilotpay.example/brand/pilot-pay-group.svg');
  await page.getByRole('button', { name: 'Send for approval' }).click();

  expect((await findRequest(api.requests, 'PUT', '/profile')).body).toEqual({ displayName: 'Pilot Pay Group', logoUri: 'https://pay.pilotpay.example/brand/pilot-pay-group.svg' });
  await expect(page.getByRole('status').filter({ hasText: 'Sent to Acme Retail for approval' })).toBeVisible();
  const organisation = page.getByRole('region', { name: 'Organisation' });
  await expect(organisation).toContainText('Pilot Pay → Pilot Pay Group');
  await expect(organisation.getByText('waiting for the operator')).toHaveCount(2);
  await expect(organisation).toContainText('Requested');
  await expect(organisation).toContainText('Nov 7, 2026; users keep seeing the current name until the operator decides');
  await page.getByRole('button', { name: 'Dismiss' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Sent to' })).toHaveCount(0);
});

test('contacts save at once; the defaults are capped by the operator', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${settingsPath}&edit=contacts` });
  await expect(page.getByRole('heading', { level: 1, name: 'Edit contacts' })).toBeVisible();
  await page.getByLabel('Security contact *').fill('nobody');
  await page.getByRole('button', { name: 'Save contacts' }).click();
  await expect(page.getByRole('alert')).toHaveText('A contact email is required.');
  await page.getByLabel('Security contact *').fill('incident@pilotpay.example');
  await page.getByLabel('Phone').fill('');
  await page.getByRole('button', { name: 'Save contacts' }).click();
  expect((await findRequest(api.requests, 'PUT', '/settings')).body).toEqual({
    contact: { email: 'incident@pilotpay.example', phone: null },
    defaults: { backChannelLogoutUri: 'https://login.pilotpay.example/logout/backchannel', accessTokenTtl: 300, refreshTokenTtl: 14 * 86_400 },
  });
  await expect(page.getByRole('status').filter({ hasText: 'Saved.' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Contacts' })).toContainText('incident@pilotpay.example');
  await expect(page.getByRole('region', { name: 'Contacts' })).toContainText('None');

  await page.getByRole('button', { name: 'Edit defaults for new clients' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Edit defaults for new clients' })).toBeVisible();
  await page.getByLabel('Access token, minutes *').fill('1500');
  await page.getByRole('button', { name: 'Save defaults' }).click();
  await expect(page.getByRole('alert')).toHaveText('Between 1 minute and 1 day, the operator\'s cap and central\'s limit.');
  expect(await scan(page)).toEqual([]);
  await page.getByLabel('Access token, minutes *').fill('10');
  await page.getByLabel('Refresh token, days *').fill('30');
  await page.getByRole('button', { name: 'Save defaults' }).click();
  const saved = await findRequest(api.requests, 'PUT', '/settings');
  expect(saved.body).toMatchObject({ defaults: { accessTokenTtl: 600, refreshTokenTtl: 30 * 86_400 } });
  await expect(page.getByRole('region', { name: 'Defaults for new clients' })).toContainText('access 10 min · refresh 30 days');
});

test('cancelling an editor with a changed draft asks first', async ({ page }) => {
  await loadPartnerApp(page, { path: `${settingsPath}&edit=contacts` });
  await page.getByLabel('Phone').fill('+7 727 300 1199');
  await page.getByRole('button', { name: 'Cancel' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: /Keep editing|Stay/ }).click();
  await expect(page.getByLabel('Phone')).toHaveValue('+7 727 300 1199');
  await page.getByRole('button', { name: 'Cancel' }).click();
  await dialog.getByRole('button', { name: /Discard/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Contacts' })).toContainText('+7 727 300 1100');
});

test('a suspended admin cannot read the settings (central refuses) and changes nothing; a viewer has no Settings', async ({ page }) => {
  await loadPartnerApp(page, { path: settingsPath, state: suspendedPartner });
  await expect(page.getByRole('heading', { name: 'Could not load the settings' })).toBeVisible();
  await expect(page.getByText('nothing can be read here until it resumes you')).toBeVisible();
  await expect(page.getByRole('button', { name: /^Edit|^Request change/ })).toHaveCount(0);
  expect(await scan(page)).toEqual([]);

  await loadPartnerApp(page, { path: settingsPath, state: asLevel('viewer') });
  await expect(page.getByRole('heading', { level: 1, name: 'No access' })).toBeVisible();
});

test('the settings that cannot be read show the error card with Retry', async ({ page }) => {
  await loadPartnerApp(page, { path: settingsPath });
  await page.route('**/partner/api/settings', route => route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"settings store unavailable"}' }));
  await page.goto(settingsPath);
  await expect(page.getByRole('heading', { name: 'Could not load the settings' })).toBeVisible();
  await expect(page.getByText('settings store unavailable')).toBeVisible();
  await page.unroute('**/partner/api/settings');
  await page.getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByRole('region', { name: 'Contacts' })).toContainText('security@pilotpay.example');
});
