import { expect, test } from '@playwright/test';
import { expectSheetWidth } from '../fixtures';
import { scan } from './axe';
import { loadPartnerApp, sidePanel, sidePanelDialog } from './fixtures';
import { PARTNER_NOW, asLevel, centralDown, notAMember, suspendedPartner } from './partner-fixtures';

/**
 * The partner console's shell (#549 decision 5): its own entry, the brand line, the nav gated by
 * the staff level, the No access page, the suspended banner, central unavailable on a write, and
 * the sheet below 720px.
 */
test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('boots from partner.html with the brand line, no tenant selector, and the nav of an admin', async ({ page }) => {
  const api = await loadPartnerApp(page);
  expect(api.requests[0]).toMatchObject({ method: 'GET', pathname: '/me' });
  const brand = page.locator('versola-partner-navigation .partner');
  await expect(brand.locator('.partner-name')).toHaveText('Pilot Pay');
  await expect(brand.locator('.partner-kind')).toHaveText('Partner console');
  await expect(page.locator('tenant-selector')).toHaveCount(0);
  await expect(page.locator('versola-admin')).toHaveCount(0);
  const nav = page.getByRole('navigation', { name: 'Sections' });
  await expect(nav.getByRole('button')).toHaveText(['Overview', 'Clients', 'Resources', 'Edges', 'Sessions & revocation', 'Audit', 'Team', 'Settings']);
  await expect(nav.getByRole('button', { name: 'Overview' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('link', { name: 'Log out' })).toHaveAttribute('href', '/partner/logout');
  expect(await scan(page)).toEqual([]);

  await nav.getByRole('button', { name: 'Clients' }).click();
  await expect(page).toHaveURL(/partner\.html\?view=clients/);
  await expect(page.getByRole('heading', { level: 1, name: 'Clients' })).toBeVisible();
});

test('a developer sees no Organisation group and gets No access on its views', async ({ page }) => {
  await loadPartnerApp(page, { state: asLevel('developer') });
  const nav = page.getByRole('navigation', { name: 'Sections' });
  await expect(nav.getByRole('button')).toHaveText(['Overview', 'Clients', 'Resources', 'Edges', 'Sessions & revocation', 'Audit']);
  await expect(page.getByRole('button', { name: '+ Create client' })).toBeVisible();
  await page.goto('/partner.html?view=team');
  await expect(page.getByRole('heading', { level: 1, name: 'No access' })).toBeVisible();
  await expect(page.getByText('This section is for admins of your organisation.')).toBeVisible();
  expect(await scan(page)).toEqual([]);
  await page.goto('/partner.html?view=settings');
  await expect(page.getByRole('heading', { level: 1, name: 'No access' })).toBeVisible();
});

test('a viewer sees everything read-only', async ({ page }) => {
  await loadPartnerApp(page, { path: '/partner.html?view=clients&id=pilot-pay-web', state: asLevel('viewer') });
  await expect(page.locator('versola-partner-navigation')).toContainText('Aya Sato');
  await expect(page.locator('versola-partner-navigation')).toContainText('viewer');
  await expect(page.getByRole('button', { name: '+ Create client' })).toHaveCount(0);
  const panel = sidePanel(page, 'Pilot Pay Shop');
  await expect(panel.getByRole('region', { name: 'General' })).toBeVisible();
  await expect(panel.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(panel.getByRole('region', { name: 'Delete client' })).toHaveCount(0);
  expect(await scan(page)).toEqual([]);
  await page.goto('/partner.html?view=overview');
  await expect(page.getByRole('button', { name: '+ Create client' })).toHaveCount(0);
});

test('a staff member who is not on the team gets the No access page with the masked identifier', async ({ page }) => {
  await loadPartnerApp(page, { state: notAMember });
  await expect(page.getByRole('heading', { name: "You are signed in, but not on Pilot Pay's team" })).toBeVisible();
  await expect(page.getByText('+7 7•• ••• 11')).toBeVisible();
  await expect(page.getByText('Nothing about you was sent anywhere')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Log out' })).toHaveAttribute('href', '/partner/logout');
  await expect(page.getByRole('link', { name: 'Try again' })).toHaveAttribute('href', '/partner/login');
  await expect(page.getByRole('navigation', { name: 'Sections' })).toHaveCount(0);
  expect(await scan(page)).toEqual([]);
});

test('an edge that cannot answer /me shows the load error with Retry', async ({ page }) => {
  await loadPartnerApp(page, { state: { me: null } });
  await expect(page.getByRole('alert')).toContainText('Could not load the partner console');
  await expect(page.getByRole('alert')).toContainText('The edge failed unexpectedly.');
  await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
  expect(await scan(page)).toEqual([]);
});

test('a suspended partner sees the banner on every screen, nothing from central, and no way to write', async ({ page }) => {
  await loadPartnerApp(page, { state: suspendedPartner });
  await expect(page.getByRole('alert')).toContainText('Acme Retail has suspended you');
  await expect(page.getByText('Refused: suspended')).toBeVisible();
  await expect(page.getByText('renewals are refused while you are suspended')).toBeVisible();
  // Central refuses every management read of a suspended partner, so those cards say so.
  await expect(page.getByRole('region', { name: 'Clients' })).toContainText('Could not be read from central.');
  await expect(page.getByRole('button', { name: '+ Create client' })).toHaveCount(0);
  expect(await scan(page)).toEqual([]);

  await page.getByRole('navigation', { name: 'Sections' }).getByRole('button', { name: 'Edges' }).click();
  await expect(page.getByRole('alert').first()).toContainText('Acme Retail has suspended you');
  await expect(page.getByRole('heading', { name: 'Could not load edges' })).toBeVisible();
  await expect(page.getByText('nothing can be read here until it resumes you')).toBeVisible();
  await expect(page.getByRole('button', { name: '+ Add edge' })).toHaveCount(0);

  await page.goto('/partner.html?view=settings');
  await expect(page.getByRole('heading', { name: 'Could not load the settings' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Request change' })).toHaveCount(0);
  // The team is the edge's own, so it still answers.
  await page.goto('/partner.html?view=team');
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open member/ })).toHaveCount(4);
});

test('central unavailable: who you are still works, everything central answers is a 503 the screens say so', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: '/partner.html?view=overview', state: centralDown });
  // /me and the team are answered by the edge itself.
  await expect(page.getByText('Central unavailable')).toBeVisible();
  await expect(page.getByText('Your edges serve from their last snapshot')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Clients' })).toBeVisible();
  expect(await scan(page)).toEqual([]);

  await page.goto('/partner.html?view=clients');
  await expect(page.getByRole('heading', { name: 'Could not load clients' })).toBeVisible();
  await expect(page.getByText('Central is unavailable right now, so this cannot be read.')).toBeVisible();
  await page.goto('/partner.html?view=edges');
  await expect(page.getByRole('heading', { name: 'Could not load edges' })).toBeVisible();
  expect(api.requests.some(request => request.pathname === '/me')).toBe(true);

});

test('a write refused with 503 says so inline and changes nothing', async ({ page }) => {
  // Central goes away after the page was read: the save is refused and the editor says why.
  const api = await loadPartnerApp(page, { path: '/partner.html?view=clients&id=pilot-pay-web&edit=general' });
  await page.route('**/partner/api/clients/pilot-pay-web', route => route.request().method() === 'PUT'
    ? route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"central_unavailable","message":"central is unreachable"}' })
    : route.fallback());
  const editor = sidePanel(page, 'Edit general');
  await editor.getByLabel('Privacy policy URL').fill('https://pilotpay.example/privacy-v2');
  await editor.getByRole('button', { name: 'Save general' }).click();
  await expect(editor.getByRole('alert')).toContainText('Central is unavailable right now, so nothing was saved.');
  expect(api.requests.filter(request => request.method === 'PUT')).toHaveLength(0);
  expect(await scan(page)).toEqual([]);
});

test('an unknown view falls back to Overview; the record route survives a reload', async ({ page }) => {
  await loadPartnerApp(page, { path: '/partner.html?view=nothing' });
  await expect(page.getByRole('heading', { level: 1, name: 'Overview' })).toBeVisible();
  await page.goto('/partner.html?view=edges&id=edge-pilot-kz-1');
  await expect(sidePanelDialog(page, 'edge-pilot-kz-1')).toBeVisible();
  await page.reload();
  await expect(sidePanelDialog(page, 'edge-pilot-kz-1')).toBeVisible();
});

test('below 720px the nav is a drawer and a record is a full-screen sheet', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadPartnerApp(page, { path: '/partner.html?view=clients&id=pilot-pay-checkout' });
  const dialog = sidePanelDialog(page, 'Pilot Pay Checkout');
  await expect(dialog).toBeVisible();
  await expectSheetWidth(page, (await dialog.boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect(await scan(page)).toEqual([]);

  await page.getByRole('button', { name: 'Close panel' }).click();
  await expect(dialog).toHaveCount(0);
  await page.getByRole('button', { name: 'Open navigation menu' }).click();
  await expect(page.getByRole('button', { name: 'Close navigation menu' })).toBeFocused();
  await page.getByRole('navigation', { name: 'Sections' }).getByRole('button', { name: 'Audit' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Audit' })).toBeVisible();
  await expect(page.locator('versola-partner-navigation')).not.toHaveAttribute('open', '');
});
