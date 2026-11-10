import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { expectSheetWidth, findRequest, loadAdminApp, sidePanel, sidePanelDialog } from './fixtures';
import { PARTNER_NOW, beeMarket, nomadTravel, partnerState, partnersPath, pilotPay } from './partners-fixtures';

/**
 * Partners (#549, #963) on the table + side panel pattern: the list with status, group, clients,
 * edges and the review date; the record with its groups; the editors for the organisation, the
 * limits and the suspension; resume; the Add partner wizard end to end with the one-time token and
 * bundle page; the read-only console; the sheet below 720px; axe on every screen.
 */
const readOnly = { resources: { central: { permissions: ['partners:read', 'oauth:read', 'edges:read', 'resources:read'] } }, isProd: false };

const scan = async (page: Parameters<typeof loadAdminApp>[0]) =>
  (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`);

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('lists partners with status, group, clients, edges and review date; filters and searches', async ({ page }) => {
  await loadAdminApp(page, { path: partnersPath, state: partnerState });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Partner', 'Status', 'Edge group', 'Clients', 'Edges', 'Review date', 'Actions']);
  const pilot = table.getByRole('row').filter({ hasText: 'Pilot Pay' });
  await expect(pilot).toContainText('Active');
  await expect(pilot).toContainText('pilot-pay-checkout');
  await expect(pilot).toContainText('1 waiting approval');
  await expect(pilot).toContainText('edge-pilot-eu-1');
  await expect(pilot).toContainText('Nov 27, 2026 · due');
  // No counters: the console client is not listed among the partner's clients, and the chips name values.
  await expect(pilot).not.toContainText('pilot-pay-console');
  const nomad = table.getByRole('row').filter({ hasText: 'Nomad Travel' });
  await expect(nomad).toContainText('Suspended');
  await expect(nomad).toContainText('overdue');
  await expect(table.getByRole('row').filter({ hasText: 'Bee Market' })).toContainText('Onboarding');

  await page.getByRole('button', { name: 'Suspended', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open partner/ })).toHaveText([/Nomad Travel/]);
  await page.getByRole('button', { name: 'Awaiting approval', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open partner/ })).toHaveText([/Pilot Pay/]);
  await page.getByRole('button', { name: 'All', exact: true }).click();
  await page.getByLabel('Search partners').fill('bee');
  await expect(table.getByRole('button', { name: /^Open partner/ })).toHaveText([/Bee Market/]);

  expect(await scan(page)).toEqual([]);
});

test('opens a partner: organisation, limits, edges, clients, waiting approvals, activity, Fixed; deep link and page layout', async ({ page }) => {
  await loadAdminApp(page, { path: partnersPath, state: partnerState });

  await page.getByRole('button', { name: /^Open partner Pilot Pay/ }).click();
  const panel = sidePanel(page, 'Pilot Pay');
  await expect(panel.getByRole('dialog')).toBeVisible();
  await expect(page).toHaveURL(/id=pilot-pay/);
  await expect(panel.getByText('Client pilot-pay-checkout is waiting for your approval')).toBeVisible();
  await expect(panel.getByText('A change to client pilot-pay-web is waiting for your approval')).toBeVisible();
  await expect(panel.getByRole('region', { name: 'Organisation' })).toContainText('security@pilotpay.example');
  await expect(panel.getByRole('region', { name: 'Organisation' })).toContainText('Managed by the partner on its edge');
  const limits = panel.getByRole('region', { name: 'Limits' });
  await expect(limits).toContainText('Up to 10');
  await expect(limits).toContainText('access ≤ 1 day · refresh ≤ 30 days');
  await expect(limits).toContainText('Every partner client');
  const group = panel.getByRole('region', { name: 'Edge group pilot-pay' });
  await expect(group).toContainText('edge-pilot-eu-1');
  await expect(group).toContainText('Renews by itself');
  await expect(group).toContainText('Waiting for enrollment');
  const clients = panel.getByRole('region', { name: 'Clients' });
  await expect(clients).toContainText('pilot-pay-checkout');
  await expect(clients).toContainText('Awaiting approval');
  await expect(clients).toContainText('pilot-pay-console');
  await expect(panel.getByRole('region', { name: 'Waiting approvals' })).toContainText('New client pilot-pay-checkout');
  const activity = panel.getByRole('region', { name: 'Activity' });
  await expect(activity).toContainText('u_7f3a9c2e');
  await expect(activity).toContainText('submitted client pilot-pay-checkout for approval');
  await expect(activity).toContainText('renewed its certificate');
  await expect(panel.getByRole('region', { name: 'Fixed' })).toContainText('Created with the partner');
  expect(await scan(page)).toEqual([]);

  await page.keyboard.press('Escape');
  await expect(panel).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Open partner Pilot Pay/ })).toBeFocused();

  await page.goto(`${partnersPath}&id=pilot-pay&layout=page`);
  await expect(page.getByRole('heading', { level: 1, name: 'Pilot Pay' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Partners');
  await expect(page.getByRole('region', { name: 'Activity' })).toBeVisible();
  expect(await scan(page)).toEqual([]);
});

test('a suspended partner shows the banner, refused renewals and Resume; an onboarding one the sign-in hint', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${partnersPath}&id=nomad-travel`, state: partnerState });
  const panel = sidePanel(page, 'Nomad Travel');
  await expect(panel.getByText('Suspended: every call of this partner is refused')).toBeVisible();
  await expect(panel.getByRole('region', { name: 'Edge group nomad-travel' })).toContainText('Renewal refused');
  await expect(panel.getByRole('region', { name: 'Suspend partner' })).toHaveCount(0);
  await panel.getByRole('button', { name: 'Resume partner' }).click();
  await page.getByRole('dialog', { name: 'Resume partner' }).getByRole('button', { name: 'Resume' }).click();
  expect((await findRequest(api.requests, 'POST', '/configuration/partners/resume')).body).toEqual({ id: 'nomad-travel' });
  await expect(panel.locator('.chip', { hasText: 'Active' }).first()).toBeVisible();

  await page.goto(`${partnersPath}&id=bee-market`);
  await expect(sidePanel(page, 'Bee Market').getByText('Onboarding: no edge has enrolled yet')).toBeVisible();
  await expect(sidePanel(page, 'Bee Market').getByText('expect no approval requests before')).toBeVisible();
});

test('suspends a partner with a note from the danger card', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${partnersPath}&id=pilot-pay`, state: partnerState });
  await sidePanel(page, 'Pilot Pay').getByRole('button', { name: 'Suspend partner pilot-pay' }).click();
  const editor = sidePanel(page, 'Suspend partner');
  await expect(page).toHaveURL(/edit=suspend/);
  await expect(editor).toContainText('its edges cannot renew their certificates');
  expect(await scan(page)).toEqual([]);

  await editor.getByRole('button', { name: 'Suspend partner', exact: true }).click();
  await expect(editor.getByText('A note is required: the partner sees it.')).toBeVisible();
  expect(api.requests.some(request => request.pathname === '/configuration/partners/suspend')).toBe(false);
  await editor.getByLabel('Note *').fill('Unpaid invoices since October.');
  await editor.getByRole('button', { name: 'Suspend partner', exact: true }).click();
  expect((await findRequest(api.requests, 'POST', '/configuration/partners/suspend')).body).toEqual({ id: 'pilot-pay', note: 'Unpaid invoices since October.' });
  await expect(sidePanel(page, 'Pilot Pay').getByText('Suspended: every call of this partner is refused')).toBeVisible();
});

test('edits the limits in the panel: openid stays, domains are validated, caps are sent in seconds', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${partnersPath}&id=pilot-pay`, state: partnerState });
  await sidePanel(page, 'Pilot Pay').getByRole('button', { name: 'Edit limits' }).click();
  const editor = sidePanel(page, 'Edit limits');
  await expect(sidePanelDialog(page, 'Edit limits')).toBeVisible();
  await expect(editor.getByRole('group', { name: 'Scopes' }).getByRole('checkbox', { name: /openid/ })).toBeDisabled();
  expect(await scan(page)).toEqual([]);

  await editor.getByRole('group', { name: 'Scopes' }).getByRole('checkbox', { name: 'phone' }).uncheck();
  await editor.getByRole('group', { name: 'Scopes' }).getByRole('checkbox', { name: 'offline_access' }).check();
  await editor.getByLabel('Redirect domains *').fill('https://bad');
  await editor.getByLabel('Access token, minutes *').fill('15');
  await editor.getByRole('button', { name: 'Save limits' }).click();
  await expect(editor.getByText('One domain per line, such as pilotpay.example. No scheme, no path.')).toBeVisible();
  await editor.getByLabel('Redirect domains *').fill('pilotpay.example\npilotpay.app\npilot.kz');
  await editor.getByRole('button', { name: 'Save limits' }).click();

  const request = await findRequest(api.requests, 'PUT', '/configuration/partners/limits');
  expect(request.body).toEqual({
    id: 'pilot-pay',
    limits: {
      scopes: ['openid', 'profile', 'email', 'offline_access'],
      redirectDomains: ['pilotpay.example', 'pilotpay.app', 'pilot.kz'],
      maxClients: 10, maxEdges: 3, accessTokenTtlMax: 900, refreshTokenTtlMax: 30 * 86_400,
      clientAuthMethods: ['tls_client_auth', 'private_key_jwt'], rateLimitPolicy: 'partner-default',
    },
  });
  const limits = sidePanel(page, 'Pilot Pay').getByRole('region', { name: 'Limits' });
  await expect(limits).toContainText('pilot.kz');
  await expect(limits).toContainText('access ≤ 15 min');
});

test('edits the organisation and sends the review date as an instant', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${partnersPath}&id=pilot-pay&edit=organisation`, state: partnerState });
  const editor = sidePanel(page, 'Edit organisation');
  await editor.getByLabel('Display name *').fill('');
  await editor.getByRole('button', { name: 'Save organisation' }).click();
  await expect(editor.getByText('A display name is required: users see it on the consent screen.')).toBeVisible();
  await editor.getByLabel('Display name *').fill('Pilot Pay Ltd');
  await editor.getByLabel('Review date').fill('2027-03-14');
  await editor.getByRole('button', { name: 'Save organisation' }).click();
  const request = await findRequest(api.requests, 'PUT', '/configuration/partners');
  expect(request.body).toMatchObject({ id: 'pilot-pay', displayName: 'Pilot Pay Ltd', reviewAt: '2027-03-14T00:00:00.000Z' });
  await expect(sidePanel(page, 'Pilot Pay Ltd').getByRole('region', { name: 'Organisation' })).toContainText('Mar 14, 2027');
});

test('the Add partner wizard creates everything in one request and lands on the one-time token and bundle', async ({ page }) => {
  const api = await loadAdminApp(page, { path: partnersPath, state: partnerState });
  await page.getByRole('button', { name: '+ Add partner' }).click();
  await expect(page).toHaveURL(/new=1/);
  await expect(page.getByRole('heading', { level: 1, name: 'Add partner' })).toBeVisible();
  expect(await scan(page)).toEqual([]);

  // Step 1 refuses an ID that is not a slug, or that exists.
  await page.getByRole('button', { name: 'Continue to limits' }).click();
  await expect(page.getByText('A partner ID is required.')).toBeVisible();
  await page.getByLabel('Partner ID *').fill('pilot.pay');
  await expect(page.getByText('A slug: lowercase letters, digits and dashes, no dots or colons.')).toBeVisible();
  await page.getByLabel('Partner ID *').fill('pilot-pay');
  await expect(page.getByText('A partner with this ID already exists.')).toBeVisible();
  await page.getByLabel('Partner ID *').fill('nomad-air');
  await page.getByLabel('Display name *').fill('Nomad Air');
  await page.getByLabel('Contact email *').fill('dev@nomadair.example');
  await page.getByLabel('Review date').fill('2027-05-01');
  await page.getByRole('button', { name: 'Continue to limits' }).click();

  // Step 2: openid is fixed on, at least one domain, the caps.
  await expect(page.getByRole('group', { name: 'Allowed scopes' }).getByRole('checkbox', { name: /openid/ })).toBeDisabled();
  await page.getByRole('button', { name: 'Continue to edge & console' }).click();
  await expect(page.getByText('At least one domain: a partner client cannot redirect anywhere otherwise.')).toBeVisible();
  await page.getByRole('group', { name: 'Allowed scopes' }).getByRole('checkbox', { name: 'profile' }).check();
  await page.getByLabel('Redirect domains *').fill('nomadair.example');
  await page.getByLabel('Clients *').fill('4');
  await page.getByLabel('Refresh token, days *').fill('14');
  await page.getByRole('group', { name: 'Client authentication *' }).getByRole('checkbox', { name: /private_key_jwt/ }).check();
  await page.getByLabel('Rate-limit policy for the group').selectOption('partner-high');
  expect(await scan(page)).toEqual([]);
  await page.getByRole('button', { name: 'Continue to edge & console' }).click();

  // Step 3: group and console client fixed by the ID; the console URL must be under a domain.
  await expect(page.getByLabel('Group ID')).toHaveValue('nomad-air');
  await expect(page.getByLabel('Client ID')).toHaveValue('nomad-air-console');
  await page.getByLabel('Partner console URL *').fill('https://login.elsewhere.net');
  await page.getByLabel('Edge ID *').fill('eu-1');
  await page.getByLabel('Public URL *').fill('https://login.nomadair.example');
  await page.getByRole('button', { name: 'Continue to review' }).click();
  await expect(page.getByText('An https URL under one of the redirect domains (nomadair.example).')).toBeVisible();
  await page.getByLabel('Partner console URL *').fill('https://login.nomadair.example');
  await expect(page.getByText('Registered as edge-eu-1')).toBeVisible();
  expect(await scan(page)).toEqual([]);
  await page.getByRole('button', { name: 'Continue to review' }).click();

  // Step 4: everything that will be created, then the request.
  await expect(page.getByRole('region', { name: 'Limits' })).toContainText('4 clients · 3 edges · access ≤ 10 min · refresh ≤ 14 days');
  await expect(page.getByRole('region', { name: 'Edge & console' })).toContainText('https://login.nomadair.example/complete');
  await expect(page.getByRole('region', { name: 'What the partner gets' })).toContainText('never');
  expect(await scan(page)).toEqual([]);
  await page.getByRole('button', { name: 'Add partner', exact: true }).click();

  const request = await findRequest(api.requests, 'POST', '/configuration/partners');
  expect(request.body).toEqual({
    partner: {
      id: 'nomad-air', tenantId: 'tenant-alpha', displayName: 'Nomad Air', logoUri: null,
      contact: { email: 'dev@nomadair.example', phone: null },
      limits: { scopes: ['openid', 'profile'], redirectDomains: ['nomadair.example'], maxClients: 4, maxEdges: 3, accessTokenTtlMax: 600, refreshTokenTtlMax: 14 * 86_400, clientAuthMethods: ['tls_client_auth', 'private_key_jwt'], rateLimitPolicy: 'partner-high' },
      reviewAt: '2027-05-01T00:00:00.000Z',
    },
    consoleClient: { redirectUri: 'https://login.nomadair.example/complete' },
    firstEdge: { id: 'edge-eu-1', publicUrl: 'https://login.nomadair.example' },
  });

  const record = sidePanel(page, 'Nomad Air');
  await expect(page).toHaveURL(/id=nomad-air/);
  await expect(record.getByText('Partner created. Save the enrollment token and the bootstrap bundle now.')).toBeVisible();
  await expect(record.locator('.secret-value')).toHaveCount(2);
  await expect(record.locator('.secret-value').nth(1)).toContainText('"edgeId": "edge-eu-1"');
  await expect(record.getByRole('button', { name: 'Copy token' })).toBeVisible();
  await expect(record.getByText('Onboarding: no edge has enrolled yet')).toBeVisible();
  await expect(page.locator('versola-data-table')).toContainText('Nomad Air');
  expect(await scan(page)).toEqual([]);

  // Leaving the record asks first, since the bundle is shown once.
  await page.keyboard.press('Escape');
  const confirm = page.getByRole('dialog', { name: 'Leave without saving the bundle?' });
  await confirm.getByRole('button', { name: 'Stay' }).click();
  await expect(record.getByText('Partner created. Save the enrollment token and the bootstrap bundle now.')).toBeVisible();
  await record.getByRole('button', { name: "I've saved them" }).click();
  await expect(record.getByText('Partner created. Save the enrollment token and the bootstrap bundle now.')).toHaveCount(0);
});

test('read-only with partners:read: no Add partner, no Edit, no suspend, no resume', async ({ page }) => {
  await loadAdminApp(page, { path: partnersPath, state: { ...partnerState, myPermissions: readOnly } });
  await expect(page.getByRole('button', { name: '+ Add partner' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Actions for pilot-pay' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy partner ID']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const panel = sidePanel(page, 'Pilot Pay');
  await expect(panel.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(panel.getByRole('region', { name: 'Suspend partner' })).toHaveCount(0);
  await page.goto(`${partnersPath}&id=nomad-travel`);
  await expect(sidePanel(page, 'Nomad Travel').getByRole('button', { name: 'Resume partner' })).toHaveCount(0);
});

test('without a tenant the view asks for one; with none in the tenant it shows the empty state', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=partners&tenant=tenant-bravo', state: partnerState });
  await expect(page.getByRole('heading', { name: 'No partners yet' })).toBeVisible();
  await expect(page.getByRole('button', { name: '+ Add partner' })).toBeVisible();
  expect(await scan(page)).toEqual([]);
});

test('below 720px the panel is a full-screen sheet and the page never scrolls sideways', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${partnersPath}&id=pilot-pay`, state: partnerState });
  const dialog = sidePanel(page, 'Pilot Pay');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expectSheetWidth(page, (await dialog.getByRole('dialog').boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test('the nav item is in the Tenant section after Resources and hidden without partners:read', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=clients&tenant=tenant-alpha', state: partnerState });
  const nav = page.getByRole('navigation', { name: 'Sections' });
  await expect(nav.getByRole('button')).toHaveText(['Security', 'Clients', 'Resources', 'Partners', 'Scopes', 'Permissions', 'Roles', 'OTP templates', 'Authorization details',
    'Tenants', 'Edges', 'Users', 'Keys & metadata', 'System settings', 'Delivery', 'Forms', 'Themes']);
  await loadAdminApp(page, { path: '/?view=partners&tenant=tenant-alpha', state: { ...partnerState, myPermissions: { resources: { central: { permissions: ['oauth:read'] } }, isProd: false } } });
  await expect(page.getByRole('navigation', { name: 'Sections' }).getByRole('button', { name: 'Partners' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Access Denied' })).toBeVisible();
});

// The fixtures stay what the screenshots and the approvals spec expect.
test('fixtures: three partners in three states', () => {
  expect([pilotPay.status, nomadTravel.status, beeMarket.status]).toEqual(['active', 'suspended', 'onboarding']);
});
