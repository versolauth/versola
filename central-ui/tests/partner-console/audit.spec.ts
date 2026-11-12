import { expect, test } from '@playwright/test';
import { scan } from './axe';
import { loadPartnerApp } from './fixtures';
import { PARTNER_NOW, asLevel } from './partner-fixtures';

/**
 * Audit: central's trail about the partner, as the edge relays it: the audit kind as the event, `<type>:<id>`
 * as the target, a `sub` as the actor (named from the team when an admin can read it), filtered by `actor`
 * (a `sub`), `kind` and `q`, paged by cursor, exported as CSV.
 */
const auditPath = '/partner.html?view=audit';

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('lists the events newest first in words, names the team by sub, marks the operator, and pages by cursor', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: auditPath });
  const table = page.getByRole('table', { name: 'Audit events' });
  await expect(table.getByRole('columnheader')).toHaveText(['When', 'Who', 'Event', 'Target', 'Outcome', 'From']);
  await expect(table.locator('tbody tr')).toHaveCount(8);
  const first = table.locator('tbody tr').first();
  await expect(first).toContainText('Marat Lee');
  await expect(first).toContainText('created a revocation');
  await expect(first).toContainText('revocation');
  await expect(table.locator('tbody tr').filter({ hasText: 'created a client' }).first()).toContainText('pilot-pay-checkout');
  await expect(table.locator('tbody tr').filter({ hasText: 'changed the partner' })).toContainText('operator');
  await expect(table.locator('tbody tr').filter({ hasText: 'deleted a client' })).toContainText('refused');
  await expect(page.getByText('as central\'s audit trail has it')).toBeVisible();
  expect(await scan(page)).toEqual([]);

  await page.getByRole('button', { name: 'Older events' }).click();
  const requests = api.requests.filter(request => request.pathname === '/audit');
  expect(requests[requests.length - 1].searchParams).toMatchObject({ cursor: 'ev-4' });
  await expect(table.locator('tbody tr')).toHaveCount(12);
  await expect(table.locator('tbody tr').last()).toContainText('created the partner');
  await expect(page.getByRole('button', { name: 'Older events' })).toHaveCount(0);
});

test('filters by a team member\'s sub, by an audit kind and by text, each as a query to the edge', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: auditPath });
  const table = page.getByRole('table', { name: 'Audit events' });
  const last = () => api.requests.filter(request => request.pathname === '/audit').at(-1)!.searchParams;

  await page.getByLabel('Who did it').selectOption({ label: 'Marat Lee' });
  await expect(table.locator('tbody tr')).toHaveCount(4);
  expect(last()).toMatchObject({ actor: 'u_2b91d4e0' });
  await page.getByLabel('Who did it').selectOption('');

  // The kinds on offer are the ones seen so far, in words; the query carries the kind itself.
  await page.getByLabel('Kind of event').selectOption({ label: 'created a client' });
  await expect(table.locator('tbody tr')).toHaveCount(2);
  expect(last()).toMatchObject({ kind: 'admin.partner-client.create' });
  await page.getByLabel('Kind of event').selectOption('');

  await page.getByLabel('Search audit').fill('checkout');
  await expect(table.locator('tbody tr')).toHaveCount(1);
  await expect(table.locator('tbody tr')).toContainText('pilot-pay-checkout');
  expect(last()).toMatchObject({ q: 'checkout' });
  await page.getByLabel('Search audit').fill('nothing-like-this');
  await expect(page.getByRole('heading', { name: 'No events match' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Export CSV' })).toBeDisabled();
});

test('a developer cannot read the team: the trail shows the subs and offers no Who filter', async ({ page }) => {
  await loadPartnerApp(page, { path: auditPath, state: asLevel('developer') });
  const table = page.getByRole('table', { name: 'Audit events' });
  await expect(table.locator('tbody tr').first()).toContainText('u_2b91d4e0');
  await expect(page.getByLabel('Who did it')).toHaveCount(0);
  expect(await scan(page)).toEqual([]);
});

test('exports what is loaded as CSV named after the partner', async ({ page }) => {
  await loadPartnerApp(page, { path: auditPath });
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export CSV' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe('pilot-pay-audit.csv');
  const text = await new Promise<string>((resolve, reject) => {
    void file.createReadStream().then(stream => {
      let data = '';
      stream.on('data', chunk => data += String(chunk));
      stream.on('end', () => resolve(data));
      stream.on('error', reject);
    });
  });
  const lines = text.split('\n');
  expect(lines[0]).toBe('at,actor,sub,action,target,outcome,from');
  expect(lines).toHaveLength(9);
  expect(lines[1]).toContain('"admin.partner-revocation.create"');
  expect(lines[1]).toContain('"Marat Lee"');
});

test('an edge that cannot read its log shows the error card; below 720px the rows stack', async ({ page }) => {
  await loadPartnerApp(page, { path: auditPath, state: { audit: [] } });
  await expect(page.getByRole('heading', { name: 'No events match' })).toBeVisible();

  await page.route('**/partner/api/audit*', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"central_unavailable","message":"central is unreachable"}' }));
  await page.goto(auditPath);
  await expect(page.getByRole('heading', { name: 'Could not load the audit log' })).toBeVisible();
  await expect(page.getByText('central is unreachable')).toBeVisible();
  await page.unroute('**/partner/api/audit*');

  await page.setViewportSize({ width: 375, height: 812 });
  await loadPartnerApp(page, { path: auditPath });
  await expect(page.getByRole('table', { name: 'Audit events' }).locator('tbody tr')).toHaveCount(8);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect(await scan(page)).toEqual([]);
});
