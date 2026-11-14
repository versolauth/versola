import { expect } from '@playwright/test';
import { scan } from './axe';
import { loadPartnerApp, test } from './fixtures';
import { PARTNER_NOW } from './partner-fixtures';

/** Audit: the partner's own log, filtered by actor and kind, paged by cursor, exported as CSV. */
const auditPath = '/partner.html?view=audit';

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('lists the events newest first, marks the operator and security, and pages by cursor', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: auditPath });
  const table = page.getByRole('table', { name: 'Audit events' });
  await expect(table.getByRole('columnheader')).toHaveText(['When', 'Who', 'Event', 'Target', 'Outcome', 'From']);
  await expect(table.locator('tbody tr')).toHaveCount(8);
  await expect(table.locator('tbody tr').first()).toContainText('Ended 3 sessions by user u_2b91d4e0');
  await expect(table.locator('tbody tr').filter({ hasText: 'set the review date' })).toContainText('operator');
  await expect(table.locator('tbody tr').filter({ hasText: 'not a member' })).toContainText('security');
  await expect(table.locator('tbody tr').filter({ hasText: 'not a member' })).toContainText('refused');
  await expect(page.getByText('Retention follows the operator\'s audit policy.')).toBeVisible();
  expect(await scan(page)).toEqual([]);

  await page.getByRole('button', { name: 'Older events' }).click();
  const older = api.requests.filter(request => request.pathname === '/audit');
  expect(older[older.length - 1].searchParams).toMatchObject({ cursor: 'ev-4' });
  await expect(table.locator('tbody tr')).toHaveCount(12);
  await expect(table.locator('tbody tr').last()).toContainText('enrolled');
  await expect(page.getByRole('button', { name: 'Older events' })).toHaveCount(0);
});

test('filters by actor, by kind and by text, each as a query to the edge', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: auditPath });
  const table = page.getByRole('table', { name: 'Audit events' });
  await page.getByRole('group', { name: 'Filter by actor' }).getByRole('button', { name: 'Operator' }).click();
  await expect(table.locator('tbody tr')).toHaveCount(4);
  expect(api.requests[api.requests.length - 1].searchParams).toMatchObject({ actor: 'operator' });
  await page.getByRole('group', { name: 'Filter by actor' }).getByRole('button', { name: 'Edges' }).click();
  await expect(table.locator('tbody tr')).toHaveCount(2);
  await page.getByRole('group', { name: 'Filter by actor' }).getByRole('button', { name: 'All' }).click();

  await page.getByLabel('Kind of event').selectOption('security');
  await expect(table.locator('tbody tr')).toHaveCount(1);
  expect(api.requests[api.requests.length - 1].searchParams).toMatchObject({ kind: 'security' });
  await page.getByLabel('Kind of event').selectOption('');

  await page.getByLabel('Search audit').fill('checkout');
  await expect(table.locator('tbody tr')).toHaveCount(1);
  await expect(table.locator('tbody tr')).toContainText('pilot-pay-checkout');
  expect(api.requests[api.requests.length - 1].searchParams).toMatchObject({ q: 'checkout' });
  await page.getByLabel('Search audit').fill('nothing-like-this');
  await expect(page.getByRole('heading', { name: 'No events match' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Export CSV' })).toBeDisabled();
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
  expect(lines[1]).toContain('"Ended 3 sessions by user u_2b91d4e0"');
});

test('the CSV export makes text of anything a spreadsheet would run as a formula', async ({ page }) => {
  const event = (id: string, display: string, target: string) => ({ id, kind: 'clients.change', at: '2026-11-07T10:00:00Z', actor: { sub: 'u_1', display }, action: 'changed', target, outcome: 'ok' as const });
  await loadPartnerApp(page, { path: auditPath, state: { audit: [event('ev-a', '=cmd|calc', '=HYPERLINK("https://evil.example","x")'), event('ev-b', '＠Eve', '+1-555'), event('ev-c', 'Dana', 'client pilot-pay-web')] } });
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export CSV' }).click();
  const stream = await (await download).createReadStream();
  let text = '';
  for await (const chunk of stream) text += String(chunk);
  const lines = text.split('\n');
  expect(lines[1]).toContain(`"'=cmd|calc"`);
  expect(lines[1]).toContain(`"'=HYPERLINK(""https://evil.example"",""x"")"`);
  expect(lines[2]).toContain(`"'＠Eve"`);
  expect(lines[2]).toContain(`"'+1-555"`);
  expect(lines[3]).toContain('"Dana"');
  expect(lines[3]).toContain('"client pilot-pay-web"');
});

test('an edge that cannot read its log shows the error card; below 720px the rows stack', async ({ page }) => {
  await loadPartnerApp(page, { path: auditPath, state: { audit: [] } });
  await expect(page.getByRole('heading', { name: 'No events match' })).toBeVisible();

  await page.route('**/partner/api/audit*', route => route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"log store unavailable"}' }));
  await page.goto(auditPath);
  await expect(page.getByRole('heading', { name: 'Could not load the audit log' })).toBeVisible();
  await expect(page.getByText('log store unavailable')).toBeVisible();
  await page.unroute('**/partner/api/audit*');

  await page.setViewportSize({ width: 375, height: 812 });
  await loadPartnerApp(page, { path: auditPath });
  await expect(page.getByRole('table', { name: 'Audit events' }).locator('tbody tr')).toHaveCount(8);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect(await scan(page)).toEqual([]);
});
