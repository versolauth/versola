import { expect } from '@playwright/test';
import { findRequest } from '../fixtures';
import { scan } from './axe';
import { loadPartnerApp, sidePanel, sidePanelDialog, test } from './fixtures';
import { PARTNER_NOW, asLevel, partnerSeedState } from './partner-fixtures';

/**
 * Clients, the partner's: the table, the record with the rows the operator fixed and the fields
 * that wait for it, the editors with the limits inline, withdraw, rotate, delete, and the wizard
 * with the operator's choices already made (#549 decision 7).
 */
const clientsPath = '/partner.html?view=clients';

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('lists the clients with their approval, filters them, and opens a record', async ({ page }) => {
  await loadPartnerApp(page, { path: clientsPath });
  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Client', 'Kind', 'Approval', 'Redirect URIs', 'Scopes', 'Actions']);
  await expect(table.getByRole('button', { name: /^Open client/ })).toHaveCount(3);
  await expect(table.getByRole('row').filter({ hasText: 'pilot-pay-checkout' })).toContainText('Awaiting approval');
  await expect(table.getByRole('row').filter({ hasText: 'pilot-pay-ios' })).toContainText('Native');
  expect(await scan(page)).toEqual([]);

  await page.getByRole('button', { name: 'Awaiting approval', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open client/ })).toHaveText([/Pilot Pay Checkout/]);
  await page.getByRole('button', { name: 'All', exact: true }).click();
  await page.getByLabel('Search clients').fill('ios');
  await expect(table.getByRole('button', { name: /^Open client/ })).toHaveText([/Pilot Pay iOS/]);
  await page.getByLabel('Search clients').fill('nothing');
  await expect(page.getByRole('heading', { name: 'No clients match' })).toBeVisible();
  await page.getByLabel('Search clients').fill('');

  await page.getByRole('button', { name: 'Open client Pilot Pay Shop pilot-pay-web' }).click();
  await expect(sidePanelDialog(page, 'Pilot Pay Shop')).toBeVisible();
  await expect(page).toHaveURL(/id=pilot-pay-web/);
  await page.keyboard.press('Escape');
  await expect(sidePanel(page, 'Pilot Pay Shop')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Open client Pilot Pay Shop pilot-pay-web' })).toBeFocused();
});

test('an active client: Integrate, the rows the operator fixed, the caps inline, Served by', async ({ page }) => {
  await loadPartnerApp(page, { path: `${clientsPath}&id=pilot-pay-web` });
  const panel = sidePanel(page, 'Pilot Pay Shop');
  await expect(panel.getByRole('region', { name: 'Integrate' })).toContainText('https://login.pilotpay.example/login/pilot-pay-web');
  await expect(panel.getByRole('region', { name: 'Integrate' })).toContainText('Sign in with Acme Retail');
  const signIn = panel.getByRole('region', { name: 'Sign-in' });
  await expect(signIn).toContainText('Authorization code + PKCE, PAR required');
  await expect(signIn).toContainText('Always shown, names Pilot Pay');
  await expect(signIn.getByText('Operator', { exact: true })).toHaveCount(2);
  const tokens = panel.getByRole('region', { name: 'Credentials & tokens' });
  await expect(tokens).toContainText('tls_client_auth with your group certificate urn:versola:edge-group:pilot-pay');
  await expect(tokens).toContainText('access 5 min · refresh 14 days');
  await expect(tokens).toContainText('(caps access ≤ 1 day · refresh ≤ 30 days)');
  await expect(tokens.getByRole('button', { name: 'Rotate keys' })).toHaveCount(0);
  await expect(panel.getByRole('region', { name: 'Served by' })).toContainText('the edges of group pilot-pay');
  await expect(panel.getByRole('button', { name: 'Edit general' })).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Edit sign-in' })).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Edit credentials & tokens' })).toBeVisible();
  expect(await scan(page)).toEqual([]);

  await panel.getByRole('button', { name: 'Open page' }).click();
  await expect(page).toHaveURL(/layout=page/);
  await expect(page.getByRole('heading', { level: 1, name: 'Pilot Pay Shop' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Integrate' })).toBeVisible();
  expect(await scan(page)).toEqual([]);
});

test('a pending client marks what waits for the operator and can be withdrawn', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${clientsPath}&id=pilot-pay-checkout` });
  const panel = sidePanel(page, 'Pilot Pay Checkout');
  await expect(panel.getByRole('status').first()).toContainText('Waiting for Acme Retail since Nov 5, 2026');
  await expect(panel.getByRole('status').first()).toContainText('Display name, Logo, Redirect URIs need the operator.');
  await expect(panel.getByRole('region', { name: 'General' }).getByText('needs operator')).toHaveCount(2);
  await expect(panel.getByRole('region', { name: 'Sign-in' })).toContainText('outside your domains');
  await expect(panel.getByRole('region', { name: 'Integrate' })).toHaveCount(0);
  await expect(panel.getByRole('button', { name: 'Sign in with this client' })).toBeDisabled();
  expect(await scan(page)).toEqual([]);

  await panel.getByRole('button', { name: 'Withdraw request' }).click();
  await expect(page.getByRole('dialog', { name: 'Withdraw the request' })).toContainText('The client is removed');
  await page.getByRole('dialog', { name: 'Withdraw the request' }).getByRole('button', { name: 'Withdraw' }).click();
  await findRequest(api.requests, 'POST', '/clients/pilot-pay-checkout/withdraw');
  await expect(panel).toHaveCount(0);
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open client/ })).toHaveCount(2);
});

test('a rejected client shows the operator\'s note', async ({ page }) => {
  const rejected = { ...partnerSeedState.clients[2], approval: { status: 'rejected' as const, waitingFields: [], note: 'The name does not match your organisation.' } };
  await loadPartnerApp(page, { path: `${clientsPath}&id=pilot-pay-checkout`, state: { clients: [partnerSeedState.clients[0], rejected] } });
  const panel = sidePanel(page, 'Pilot Pay Checkout');
  await expect(panel.getByRole('status').first()).toContainText('Rejected by Acme Retail');
  await expect(panel.getByRole('status').first()).toContainText('The name does not match your organisation.');
  await expect(panel.getByRole('button', { name: 'Withdraw request' })).toHaveCount(0);
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'pilot-pay-checkout' })).toContainText('Rejected');
  expect(await scan(page)).toEqual([]);
});

test('editing the name sends it to the operator, the links apply at once', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${clientsPath}&id=pilot-pay-web` });
  await sidePanel(page, 'Pilot Pay Shop').getByRole('button', { name: 'Edit general' }).click();
  const editor = sidePanel(page, 'Edit general');
  await expect(page).toHaveURL(/edit=general/);
  await expect(editor.getByText('A new name or logo goes to Acme Retail for approval; the links apply at once.')).toBeVisible();
  expect(await scan(page)).toEqual([]);

  await editor.getByLabel('Display name *').fill('');
  await editor.getByRole('button', { name: 'Save general' }).click();
  await expect(editor.getByRole('alert')).toHaveText('A display name is required.');
  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);

  await editor.getByLabel('Display name *').fill('Pilot Pay Store');
  await editor.getByLabel('Terms URL').fill('https://pilotpay.example/terms-2026');
  await editor.getByRole('button', { name: 'Save general' }).click();
  const request = await findRequest(api.requests, 'PUT', '/clients/pilot-pay-web');
  expect(request.body).toMatchObject({ clientName: 'Pilot Pay Store', tosUri: 'https://pilotpay.example/terms-2026', scope: ['openid', 'profile', 'email'] });
  const panel = sidePanel(page, 'Pilot Pay Shop');
  await expect(panel.getByRole('status').first()).toContainText('Saved. Display name waits for Acme Retail; the rest applies on your edges\' next sync.');
  await expect(panel.getByRole('region', { name: 'General' })).toContainText('https://pilotpay.example/terms-2026');
  await expect(panel.getByRole('region', { name: 'General' }).getByText('needs operator')).toHaveCount(1);
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'pilot-pay-web' })).toContainText('Awaiting approval');
});

test('a redirect URI outside the domains is marked and waits for the operator', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${clientsPath}&id=pilot-pay-ios&edit=signin` });
  const editor = sidePanel(page, 'Edit sign-in');
  await expect(editor.getByText('https under pilotpay.example, pilotpay.app; a URI elsewhere waits for the operator.')).toBeVisible();
  await editor.getByLabel('Redirect URIs *').fill('https://app.pilotpay.example/callback\nhttps://pilot.partner-cdn.net/callback');
  await editor.getByRole('checkbox', { name: 'email' }).check();
  await editor.getByRole('button', { name: 'Save sign-in' }).click();
  const request = await findRequest(api.requests, 'PUT', '/clients/pilot-pay-ios');
  expect(request.body).toMatchObject({ redirectUris: ['https://app.pilotpay.example/callback', 'https://pilot.partner-cdn.net/callback'], scope: ['openid', 'profile', 'phone', 'email'] });
  const panel = sidePanel(page, 'Pilot Pay iOS');
  await expect(panel.getByRole('status').first()).toContainText('Redirect URIs waits for Acme Retail');
  await expect(panel.getByRole('region', { name: 'Sign-in' })).toContainText('email');
  await expect(panel.getByRole('region', { name: 'Sign-in' })).toContainText('needs operator');
});

test('token lifetimes are capped by the operator in the editor itself', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${clientsPath}&id=pilot-pay-web&edit=tokens` });
  const editor = sidePanel(page, 'Edit token lifetimes');
  await expect(editor.getByText('Within the operator\'s caps: access ≤ 1 day · refresh ≤ 30 days.')).toBeVisible();
  await expect(editor.getByText('Client authentication: tls_client_auth')).toBeVisible();
  await editor.getByLabel('Access token, minutes *').fill('1500');
  await editor.getByLabel('Refresh token, days *').fill('45');
  await editor.getByRole('button', { name: 'Save token lifetimes' }).click();
  await expect(editor.getByRole('alert')).toHaveText(['At most 1 day: the operator\'s cap.', 'At most 30 days: the operator\'s cap.']);
  expect(await scan(page)).toEqual([]);
  await editor.getByLabel('Access token, minutes *').fill('10');
  await editor.getByLabel('Refresh token, days *').fill('30');
  await editor.getByRole('button', { name: 'Save token lifetimes' }).click();
  expect((await findRequest(api.requests, 'PUT', '/clients/pilot-pay-web')).body).toMatchObject({ accessTokenTtl: 600, refreshTokenTtl: 30 * 86_400 });
  await expect(sidePanel(page, 'Pilot Pay Shop').getByRole('region', { name: 'Credentials & tokens' })).toContainText('access 10 min · refresh 30 days');
});

test('a partner client has nothing to rotate; deleting a client goes through the confirm dialog', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${clientsPath}&id=pilot-pay-ios`, state: { clients: [partnerSeedState.clients[0], partnerSeedState.clients[1]] } });
  const panel = sidePanel(page, 'Pilot Pay iOS');
  await expect(panel.getByRole('region', { name: 'Credentials & tokens' })).toContainText('tls_client_auth with your group certificate');
  // The edge answers 409 not_supported to a rotation, so the console offers none.
  await expect(panel.getByRole('button', { name: 'Rotate keys' })).toHaveCount(0);

  await panel.getByRole('button', { name: 'Delete client pilot-pay-ios' }).click();
  await expect(page.getByRole('dialog', { name: 'Delete client' })).toContainText('Its users are signed out and the operator is notified.');
  await page.getByRole('dialog', { name: 'Delete client' }).getByRole('button', { name: 'Delete', exact: true }).click();
  await findRequest(api.requests, 'DELETE', '/clients/pilot-pay-ios');
  await expect(panel).toHaveCount(0);
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open client/ })).toHaveCount(1);
});

test('the wizard makes the operator\'s choices, keeps the limits inline, and ends on the pending record', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${clientsPath}&new=1` });
  await expect(page.getByRole('heading', { level: 1, name: 'Create client' })).toBeVisible();
  await expect(page.getByRole('button', { name: /Service/ })).toHaveCount(0);
  await expect(page.getByText('A service app with no user is not available to a partner')).toBeVisible();
  await expect(page.getByText('Flow: authorization code + PKCE, PAR required')).toBeVisible();
  await expect(page.getByText('Served by the edges of group pilot-pay')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue to basics' })).toBeDisabled();
  expect(await scan(page)).toEqual([]);
  await page.getByRole('button', { name: /^Web app/ }).click();
  await page.getByRole('button', { name: 'Continue to basics' }).click();

  // Step 2: the defaults from Settings, the scopes the operator allowed, the caps as hints.
  await expect(page.getByLabel('Back-channel logout URI')).toHaveValue('https://login.pilotpay.example/logout/backchannel');
  await expect(page.getByLabel('Access token, minutes *')).toHaveValue('5');
  await expect(page.getByRole('group', { name: 'Scopes' }).getByRole('checkbox')).toHaveCount(4);
  await expect(page.getByRole('checkbox', { name: /^openid/ })).toBeDisabled();
  await expect(page.getByText('Cap: 1 day.')).toBeVisible();
  // The operator's limit allows two methods, but a partner's client authenticates with the group certificate and nothing else is offered.
  await expect(page.getByRole('group', { name: 'Client authentication' })).toHaveCount(0);
  await expect(page.getByText('private_key_jwt')).toHaveCount(0);
  await expect(page.getByText('Client authentication: tls_client_auth with your group certificate')).toBeVisible();
  await page.getByRole('button', { name: 'Continue to review' }).click();
  await expect(page.getByRole('alert').first()).toHaveText('A client ID is required.');
  await expect(page.getByLabel('Client ID *')).toBeFocused();
  expect(await scan(page)).toEqual([]);

  await page.getByLabel('Client ID *').fill('pilot-pay-portal');
  await page.getByLabel('Display name *').fill('Pilot Pay Portal');
  await page.getByLabel('Redirect URIs *').fill('https://portal.pilotpay.example/complete');
  await page.getByLabel('Redirect URIs *').press('Enter');
  await expect(page.getByText('https://portal.pilotpay.example/complete')).toBeVisible();
  await page.getByLabel('Redirect URIs *').fill('https://portal.pilot-cdn.net/complete');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByText('One URI is outside your redirect domains')).toBeVisible();
  await page.getByRole('button', { name: 'Remove https://portal.pilot-cdn.net/complete' }).click();
  await page.getByRole('checkbox', { name: 'profile' }).check();
  await page.getByRole('checkbox', { name: 'email' }).check();
  const sketch = page.getByRole('region', { name: 'What the user will see' });
  await expect(sketch).toContainText('Pilot Pay Portal wants to sign you in');
  await expect(sketch).toContainText('Your email address');
  await expect(page.getByRole('region', { name: 'What happens on submit' })).toContainText('Display name');
  await page.getByRole('button', { name: 'Continue to review' }).click();

  // Step 3: what the operator reviews is marked.
  await expect(page.getByText('Review and submit')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Application' })).toContainText('pilot-pay-portal');
  await expect(page.getByRole('region', { name: 'Application' }).getByText('operator')).toHaveCount(1);
  await expect(page.getByRole('region', { name: 'Sign-in' })).toContainText('access 5 min · refresh 14 days');
  await expect(page.getByRole('region', { name: 'Sign-in' })).toContainText('group certificate');
  expect(await scan(page)).toEqual([]);
  await page.getByRole('button', { name: 'Submit for approval' }).click();

  const request = await findRequest(api.requests, 'POST', '/clients');
  expect(request.body).toEqual({
    id: 'pilot-pay-portal', kind: 'web', clientName: 'Pilot Pay Portal', logoUri: null, policyUri: null, tosUri: null,
    redirectUris: ['https://portal.pilotpay.example/complete'], scope: ['openid', 'profile', 'email'], accessTokenTtl: 300, refreshTokenTtl: 14 * 86_400,
    backChannelLogoutUri: 'https://login.pilotpay.example/logout/backchannel',
  });
  await expect(page).toHaveURL(/id=pilot-pay-portal/);
  await expect(page).not.toHaveURL(/new=1/);
  const panel = sidePanel(page, 'Pilot Pay Portal');
  await expect(panel.getByRole('status').first()).toContainText('Waiting for Acme Retail since Nov 7, 2026');
  await expect(panel.getByRole('status').first()).toContainText('Display name needs the operator.');
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open client/ })).toHaveCount(4);
});

test('the wizard refuses a duplicate ID and a lifetime above the cap before submitting', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${clientsPath}&new=1` });
  await page.getByRole('button', { name: /^Mobile or desktop app/ }).click();
  await page.getByRole('button', { name: 'Continue to basics' }).click();
  await page.getByLabel('Client ID *').fill('pilot-pay-web');
  await page.getByLabel('Display name *').fill('Again');
  await page.getByLabel('Redirect URIs *').fill('https://app.pilotpay.example/x');
  await page.getByLabel('Redirect URIs *').press('Enter');
  await page.getByLabel('Access token, minutes *').fill('1500');
  await page.getByRole('button', { name: 'Continue to review' }).click();
  await expect(page.getByRole('alert')).toHaveText(['You already have a client with this ID.', 'At most 1 day: the operator\'s cap.']);
  expect(api.requests.some(request => request.method === 'POST')).toBe(false);
  await page.getByRole('button', { name: 'Back' }).click();
  await page.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Clients' })).toBeVisible();
});

test('at the operator\'s cap the Create button is disabled; a developer may still create', async ({ page }) => {
  const me = { ...partnerSeedState.me!, partner: { ...partnerSeedState.me!.partner, limits: { ...partnerSeedState.me!.partner.limits, maxClients: 3 } } };
  await loadPartnerApp(page, { path: clientsPath, state: { me } });
  const create = page.getByRole('button', { name: '+ Create client' });
  await expect(create).toBeDisabled();
  await expect(create).toHaveAttribute('title', 'The operator allows you 3 clients');

  await loadPartnerApp(page, { path: clientsPath, state: asLevel('developer') });
  await expect(page.getByRole('button', { name: '+ Create client' })).toBeEnabled();
  await page.goto(`${clientsPath}&id=pilot-pay-web`);
  await expect(sidePanel(page, 'Pilot Pay Shop').getByRole('button', { name: 'Edit general' })).toBeVisible();
});

test('the empty state invites the first client', async ({ page }) => {
  await loadPartnerApp(page, { path: clientsPath, state: { clients: [] } });
  await expect(page.getByRole('heading', { name: 'No clients yet' })).toBeVisible();
  await page.getByRole('button', { name: '+ Create client' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Create client' })).toBeVisible();
  expect(await scan(page)).toEqual([]);
});

test('below 720px the record is a sheet and the wizard stacks', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadPartnerApp(page, { path: `${clientsPath}&new=1` });
  await page.getByRole('button', { name: /^Web app/ }).click();
  await page.getByRole('button', { name: 'Continue to basics' }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect(await scan(page)).toEqual([]);
});

test('the wizard opens inside a tight cap and with no defaults set, as for a fresh partner on the real edge', async ({ page }) => {
  const me = partnerSeedState.me!;
  await loadPartnerApp(page, {
    path: `${clientsPath}&new=1`,
    state: { me: { ...me, partner: { ...me.partner, limits: { ...me.partner.limits, accessTokenTtlMax: 600, refreshTokenTtlMax: 86_400 } } }, settings: { ...partnerSeedState.settings, defaults: {} } },
  });
  await page.getByRole('button', { name: /^Web app/ }).click();
  await page.getByRole('button', { name: 'Continue to basics' }).click();
  await expect(page.getByLabel('Access token, minutes *')).toHaveValue('5');
  await expect(page.getByLabel('Refresh token, days *')).toHaveValue('1');
  await expect(page.getByLabel('Back-channel logout URI')).toHaveValue('');
  await expect(page.getByRole('alert')).toHaveCount(0);
});
