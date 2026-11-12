import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { consentForm, consentPreviews } from './consent-preview-fixtures';
import { expectSheetWidth, findRequest, loadAdminApp, sidePanel, sidePanelDialog } from './fixtures';
import { PARTNER_NOW, partnerState, partnersPath } from './partners-fixtures';

/**
 * The approvals queue (#549 decision 7): waiting first, filters, the record with the diff and its
 * marks, the consent preview (central's form when it has one, a sketch otherwise), approve, approve
 * with overrides, reject with a note; a decided record; read-only; the sheet below 720px.
 */
const approvalsPath = `${partnersPath}&tab=approvals`;

const scan = async (page: Parameters<typeof loadAdminApp>[0]) =>
  (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`);

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('the queue lists waiting requests first, filters by status and partner', async ({ page }) => {
  await loadAdminApp(page, { path: approvalsPath, state: partnerState });
  await expect(page.getByRole('tab', { name: 'Approvals' })).toHaveAttribute('aria-selected', 'true');
  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Request', 'Partner', 'Requested', 'Kind', 'Status', 'Actions']);
  await expect(table.getByRole('button', { name: /^Open request/ })).toHaveText([/New client “Pilot Pay Checkout”/, /Change to Pilot Pay Shop/]);
  await expect(table.getByRole('row').filter({ hasText: 'pilot-pay-checkout' })).toContainText('u_7f3a9c2e');
  await expect(table.getByRole('row').filter({ hasText: 'pilot-pay-checkout' })).toContainText('Waiting since Nov 5, 2026');
  expect(await scan(page)).toEqual([]);

  await page.getByRole('button', { name: 'Decided', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open request/ })).toHaveText([/New client “Pilot Pay Shop”/]);
  await expect(table.getByRole('row').filter({ hasText: 'apr-0998' }).or(table.getByRole('row').filter({ hasText: 'Pilot Pay Shop' }))).toContainText('Approved');
  await page.getByRole('button', { name: 'All', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open request/ })).toHaveCount(3);
  await page.getByRole('combobox', { name: 'Partner' }).click();
  await page.getByRole('option', { name: /Nomad Travel/ }).click();
  await expect(page.getByRole('heading', { name: 'No requests match' })).toBeVisible();

  // The Partners tab is a click away and keeps the route clean.
  await page.getByRole('tab', { name: 'Partners' }).click();
  await expect(page).not.toHaveURL(/tab=/);
  await expect(page.getByRole('button', { name: /^Open partner Pilot Pay/ })).toBeVisible();
});

test('a request opens with the diff, the marks and a consent sketch when central has no consent form', async ({ page }) => {
  await loadAdminApp(page, { path: approvalsPath, state: partnerState });
  await page.getByRole('button', { name: /^Open request New client “Pilot Pay Checkout”/ }).click();
  const panel = sidePanel(page, 'New client “Pilot Pay Checkout”');
  await expect(sidePanelDialog(page, 'New client “Pilot Pay Checkout”')).toBeVisible();
  await expect(page).toHaveURL(/tab=approvals/);
  await expect(page).toHaveURL(/id=apr-1001/);
  await expect(panel.getByText('3 items need you: display name, logo, redirect uris')).toBeVisible();

  const diff = panel.getByRole('group', { name: 'Requested against registered' });
  const rowOf = (field: string) => diff.locator(`.diff-row[data-field="${field}"] .requested`);
  await expect(rowOf('clientName')).toContainText('needs operator');
  await expect(rowOf('logoUri')).toContainText('needs operator');
  await expect(rowOf('redirectUris')).toContainText('https://checkout.pilot-partner.net/complete');
  await expect(rowOf('redirectUris')).toContainText('needs operator');
  await expect(rowOf('scope')).toContainText('within limits');
  await expect(rowOf('accessTokenTtl')).toContainText('5 min');
  await expect(rowOf('refreshTokenTtl')).toContainText('14 days');
  await expect(panel.getByText('Pilot Pay Checkout wants to sign you in')).toBeVisible();
  await expect(panel.getByText('Central has no consent form to render, so the screen is described instead of drawn.')).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Approve as requested' })).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Approve with overrides…' })).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Reject' })).toBeVisible();
  expect(await scan(page)).toEqual([]);

  await page.keyboard.press('Escape');
  await expect(panel).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Open request New client “Pilot Pay Checkout”/ })).toBeFocused();
});

test('renders central\'s consent form in the preview when it has one', async ({ page }) => {
  await loadAdminApp(page, {
    path: `${approvalsPath}&id=apr-1001`,
    state: {
      ...partnerState,
      themes: [{ id: 'default', css: '', tenantId: null, tokens: { base: 'versola' } }],
      themePresets: [{ id: 'versola', title: { en: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } }],
      forms: [consentForm],
      formPreviews: [consentPreviews],
    },
  });
  await expect(page.locator('versola-client-consent-preview iframe')).toHaveCount(1);
  expect(await scan(page)).toEqual([]);
});

test('a change request shows registered against requested, and a decided one shows who decided', async ({ page }) => {
  await loadAdminApp(page, { path: `${approvalsPath}&id=apr-1002`, state: partnerState });
  const panel = sidePanel(page, 'Change to Pilot Pay Shop');
  const diff = panel.getByRole('group', { name: 'Requested against registered' });
  await expect(diff).toContainText('offline_access');
  await expect(diff.locator('.current')).toContainText('openid');
  await expect(diff.locator('.requested')).toContainText('needs operator');
  // Only the scopes changed, which users do see on the consent screen.
  await expect(panel.getByRole('region', { name: 'How users will see it' })).toBeVisible();

  await page.goto(`${approvalsPath}&id=apr-0998`);
  const decided = sidePanel(page, 'New client “Pilot Pay Shop”');
  await expect(decided.getByRole('region', { name: 'Decision' })).toContainText('b.argin');
  await expect(decided.getByRole('region', { name: 'Decision' })).toContainText('Name matches the partner.');
  await expect(decided.getByRole('button', { name: 'Approve as requested' })).toHaveCount(0);
  expect(await scan(page)).toEqual([]);
});

test('approves as requested, and the client becomes approved on the partner record', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${approvalsPath}&id=apr-1001`, state: partnerState });
  const panel = sidePanel(page, 'New client “Pilot Pay Checkout”');
  await panel.getByLabel('Note to the partner').fill('Fine.');
  await panel.getByRole('button', { name: 'Approve as requested' }).click();
  const request = await findRequest(api.requests, 'POST', '/configuration/partners/approvals/approve');
  expect(request.body).toEqual({ id: 'apr-1001', note: 'Fine.' });
  await expect(panel.getByRole('region', { name: 'Decision' })).toContainText('Approved');
  await expect(panel.getByRole('button', { name: 'Approve as requested' })).toHaveCount(0);
  await page.getByRole('tab', { name: 'Partners' }).click();
  await page.getByRole('button', { name: /^Open partner Pilot Pay/ }).click();
  const clients = sidePanel(page, 'Pilot Pay').getByRole('region', { name: 'Clients' });
  await expect(clients.locator('.edge-row', { hasText: 'pilot-pay-checkout' })).toContainText('Active');
});

test('approves with overrides: the rows that need the operator become fields, the overrides are sent', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${approvalsPath}&id=apr-1001`, state: partnerState });
  const panel = sidePanel(page, 'New client “Pilot Pay Checkout”');
  await panel.getByRole('button', { name: 'Approve with overrides…' }).click();
  const overrides = panel.getByRole('group', { name: 'Overrides' });
  await expect(overrides.getByLabel('Display name')).toHaveValue('Pilot Pay Checkout');
  await expect(overrides.getByLabel('Redirect URIs')).toHaveValue('https://checkout.pilotpay.example/complete\nhttps://checkout.pilot-partner.net/complete');
  expect(await scan(page)).toEqual([]);
  await overrides.getByLabel('Redirect URIs').fill('https://checkout.pilotpay.example/complete');
  await panel.getByLabel('Note to the partner').fill('Dropped the URI outside your domains.');
  await panel.getByRole('button', { name: 'Approve with overrides', exact: true }).click();
  const request = await findRequest(api.requests, 'POST', '/configuration/partners/approvals/approve');
  expect(request.body).toEqual({ id: 'apr-1001', note: 'Dropped the URI outside your domains.', overrides: { redirectUris: ['https://checkout.pilotpay.example/complete'] } });
  await expect(panel.getByRole('region', { name: 'Decision' })).toContainText('Approved');
});

test('rejects only with a note', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${approvalsPath}&id=apr-1002`, state: partnerState });
  const panel = sidePanel(page, 'Change to Pilot Pay Shop');
  await panel.getByRole('button', { name: 'Reject' }).click();
  await expect(panel.getByText('A note is required when rejecting: the partner reads it.')).toBeVisible();
  expect(api.requests.some(request => request.pathname.endsWith('/reject'))).toBe(false);
  await panel.getByLabel('Note to the partner').fill('offline_access is not in your limits.');
  await panel.getByRole('button', { name: 'Reject' }).click();
  expect((await findRequest(api.requests, 'POST', '/configuration/partners/approvals/reject')).body).toEqual({ id: 'apr-1002', note: 'offline_access is not in your limits.' });
  await expect(panel.getByRole('region', { name: 'Decision' })).toContainText('Rejected');
  await expect(panel.getByRole('region', { name: 'Decision' })).toContainText('offline_access is not in your limits.');
});

test('read-only with partners:read: the record says deciding needs partners:manage', async ({ page }) => {
  await loadAdminApp(page, { path: `${approvalsPath}&id=apr-1001`, state: { ...partnerState, myPermissions: { resources: { central: { permissions: ['partners:read', 'oauth:read'] } }, isProd: false } } });
  const panel = sidePanel(page, 'New client “Pilot Pay Checkout”');
  await expect(panel.getByText('Read-only: deciding needs partners:manage')).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Approve as requested' })).toHaveCount(0);
});

test('an empty queue says so', async ({ page }) => {
  await loadAdminApp(page, { path: approvalsPath, state: { ...partnerState, partnerApprovals: [] } });
  await expect(page.getByRole('heading', { name: 'No requests' })).toBeVisible();
});

test('below 720px the record is a full-screen sheet with the diff stacked', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${approvalsPath}&id=apr-1001`, state: partnerState });
  const dialog = sidePanel(page, 'New client “Pilot Pay Checkout”');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expectSheetWidth(page, (await dialog.getByRole('dialog').boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});
