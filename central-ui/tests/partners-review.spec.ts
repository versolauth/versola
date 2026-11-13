import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp, sidePanel } from './fixtures';
import { PARTNER_NOW, partnerApprovals, partnerState, partnerTenant, partnersPath, pilotPay } from './partners-fixtures';

/**
 * What the review of the mock-stage consoles found (#963): overrides for every row the form renders,
 * a decided request that keeps what was asked for apart from what was registered, a partner of
 * another tenant named and opened from the global Edges view, and the rules of a partner's device
 * client (DPoP on, the refresh lifetime refused inline instead of clamped).
 */
test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

// A change that asks for a scope outside the limits and renames the client: the name always needs the operator.
const renaming = {
  id: 'apr-2001', partnerId: 'pilot-pay', kind: 'client_change' as const, targetId: 'pilot-pay-web',
  requested: { clientName: 'Pilot Pay Shop 2', scope: ['openid', 'profile', 'email', 'offline_access'] },
  current: { clientName: 'Pilot Pay Shop', scope: ['openid', 'profile', 'email'] },
  outsideLimits: ['scope'],
  requestedBy: 'u_2b91d4e0', requestedAt: '2026-11-06T09:00:00Z', status: 'waiting' as const,
};

test('an override typed for the name is sent even though only the scope is outside the limits', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${partnersPath}&tab=approvals&id=apr-2001`, state: { ...partnerState, partnerApprovals: [renaming, ...partnerApprovals] } });
  const panel = sidePanel(page, 'Change to Pilot Pay Shop 2');
  await panel.getByRole('button', { name: 'Approve with overrides…' }).click();
  const overrides = panel.getByRole('group', { name: 'Overrides' });
  await expect(overrides.getByLabel('Display name')).toHaveValue('Pilot Pay Shop 2');
  await overrides.getByLabel('Display name').fill('Pilot Pay Store');
  await panel.getByRole('button', { name: 'Approve with overrides', exact: true }).click();
  expect((await findRequest(api.requests, 'POST', '/configuration/partners/approvals/approve')).body).toMatchObject({ id: 'apr-2001', overrides: { clientName: 'Pilot Pay Store' } });
});

test('a decided request keeps what the partner asked for and shows what was registered instead', async ({ page }) => {
  await loadAdminApp(page, { path: `${partnersPath}&tab=approvals&id=apr-2001`, state: { ...partnerState, partnerApprovals: [renaming, ...partnerApprovals] } });
  const panel = sidePanel(page, 'Change to Pilot Pay Shop 2');
  await panel.getByRole('button', { name: 'Approve with overrides…' }).click();
  await panel.getByRole('group', { name: 'Overrides' }).getByLabel('Display name').fill('Pilot Pay Store');
  await panel.getByRole('button', { name: 'Approve with overrides', exact: true }).click();
  const decision = panel.getByRole('region', { name: 'Decision' });
  await expect(decision).toContainText('Approved');
  await expect(decision.getByText('Registered instead')).toBeVisible();
  await expect(decision).toContainText('Display name: Pilot Pay Store');
  // The diff is still the request as it was made.
  await expect(panel.getByRole('group', { name: 'Requested against registered' }).locator('.diff-row[data-field="clientName"] .requested')).toContainText('Pilot Pay Shop 2');
});

test('a partner of another tenant is named on the Edges view and opens in its own tenant', async ({ page }) => {
  const far = { ...pilotPay, id: 'far-air', displayName: 'Far Air', tenantId: 'tenant-bravo', groupId: 'far-air' };
  const farEdge = { id: 'edge-far-1', hasOldKey: false, requireDpopNonce: true, groupId: 'far-air', groupKind: 'partner' as const, partnerId: 'far-air', publicUrl: 'https://login.far.example' };
  await loadAdminApp(page, { path: `/?view=edges&tenant=${partnerTenant}&id=edge-far-1`, state: { ...partnerState, edges: [farEdge], partners: [far], partnerApprovals: [], clients: {} } });
  const group = sidePanel(page, 'edge-far-1').getByRole('region', { name: 'Group and certificate' });
  await expect(group.getByRole('button', { name: 'Far Air' })).toBeVisible();
  await expect(group).not.toContainText('far-air · partner Far Air far-air');
  await page.locator('versola-data-table').getByRole('button', { name: 'Open partner Far Air' }).click();
  await expect(page).toHaveURL(/view=partners/);
  await expect(page).toHaveURL(/id=far-air/);
  await expect(sidePanel(page, 'Far Air').getByRole('dialog')).toBeVisible();
});

// ── a partner's device client ──

const deviceState = {
  clients: { [partnerTenant]: [] },
  partners: [{ ...pilotPay, limits: { ...pilotPay.limits, scopes: ['openid', 'profile', 'offline_access'], accessTokenTtlMax: 600 } }],
  scopes: { [partnerTenant]: [
    { scope: 'openid', description: { en: 'OpenID' }, claims: [] }, { scope: 'profile', description: { en: 'Profile' }, claims: [] },
    { scope: 'offline_access', description: { en: 'Offline access' }, claims: [] },
  ] },
};

async function startDeviceClient(page: Page) {
  await loadAdminApp(page, { path: `/?view=clients&tenant=${partnerTenant}`, state: deviceState });
  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('group', { name: 'Who owns the client' }).getByRole('button', { name: 'Partner' }).click();
  await page.getByRole('combobox', { name: 'Partner' }).click();
  await page.getByRole('option', { name: /Pilot Pay/ }).click();
  await page.getByRole('button', { name: /Mobile or desktop app/ }).click();
}

test('a partner\'s device client keeps DPoP on: the device holds its tokens', async ({ page }) => {
  await startDeviceClient(page);
  const plan = page.locator('versola-client-kind-step .plan');
  await expect(plan.locator('.plan-line.on').filter({ hasText: 'DPoP-bound access tokens' })).toBeVisible();
  await expect(plan).toContainText('the tokens are issued to the device');
  await expect(plan).toContainText('only sender-constraint');
  // The partner's cap is under the hour DPoP-bound tokens live at least, and the plan says so.
  await expect(plan).toContainText('raise Pilot Pay\'s access token cap');
});

test('with a cap of an hour the device plan carries no warning', async ({ page }) => {
  await loadAdminApp(page, { path: `/?view=clients&tenant=${partnerTenant}`, state: { ...deviceState, partners: [{ ...pilotPay, limits: { ...pilotPay.limits, scopes: ['openid', 'profile', 'offline_access'] } }] } });
  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('group', { name: 'Who owns the client' }).getByRole('button', { name: 'Partner' }).click();
  await page.getByRole('combobox', { name: 'Partner' }).click();
  await page.getByRole('option', { name: /Pilot Pay/ }).click();
  await page.getByRole('button', { name: /Mobile or desktop app/ }).click();
  const plan = page.locator('versola-client-kind-step .plan');
  await expect(plan.locator('.plan-line.on').filter({ hasText: 'DPoP-bound access tokens' })).toBeVisible();
  await expect(plan).not.toContainText('raise Pilot Pay');
});
