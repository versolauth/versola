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
  await expect(page.getByRole('alert')).toContainText('edge database unavailable');
  await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
  expect(await scan(page)).toEqual([]);
});

test('a suspended partner sees the banner on every screen, refused renewals, and no way to write', async ({ page }) => {
  await loadPartnerApp(page, { state: suspendedPartner });
  await expect(page.getByRole('alert')).toContainText('Acme Retail has suspended you');
  await expect(page.getByText('Refused: suspended')).toBeVisible();
  await expect(page.getByText('renewals are refused while you are suspended')).toBeVisible();
  await expect(page.getByRole('button', { name: '+ Create client' })).toHaveCount(0);
  expect(await scan(page)).toEqual([]);

  await page.getByRole('navigation', { name: 'Sections' }).getByRole('button', { name: 'Edges' }).click();
  await expect(page.getByRole('alert')).toContainText('Acme Retail has suspended you');
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'edge-pilot-eu-1' })).toContainText('Renewal refused');
  await expect(page.getByRole('button', { name: '+ Add edge' })).toHaveCount(0);

  await page.goto('/partner.html?view=clients&id=pilot-pay-web');
  await expect(sidePanel(page, 'Pilot Pay Shop').getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await page.goto('/partner.html?view=settings');
  await expect(page.getByRole('region', { name: 'Your agreement with Acme Retail' })).toContainText('suspended');
  await expect(page.getByRole('button', { name: 'Request change' })).toHaveCount(0);
});

test('central unavailable: reads keep working from the snapshot, a write says so inline', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: '/partner.html?view=clients&id=pilot-pay-web&edit=general', state: centralDown });
  const editor = sidePanel(page, 'Edit general');
  await editor.getByLabel('Privacy policy URL').fill('https://pilotpay.example/privacy-v2');
  await editor.getByRole('button', { name: 'Save general' }).click();
  await expect(editor.getByRole('alert')).toContainText('Central is unavailable right now, so nothing was saved.');
  expect(api.requests.filter(request => request.method === 'PUT')).toHaveLength(1);
  expect(await scan(page)).toEqual([]);

  await page.goto('/partner.html?view=overview');
  await expect(page.getByText('Central unavailable')).toBeVisible();
  await expect(page.getByText('Your edges serve from their last snapshot')).toBeVisible();
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
