import { expect, test, type Page } from '@playwright/test';
import { loadAdminApp } from './fixtures';
import type { MockConfigHarness, MockConfigState, RequestLog, UserAuditMock } from './mocks';
import { dana, danaId, quiet } from './users-fixtures';
import {
  ACTIVITY_NOW,
  activityAudit,
  emptyActivityAudit,
  pagedActivityAudit,
  partialActivityAudit,
  reuseEventId,
  reuseTraceId,
} from './user-activity-fixtures';

// The fixtures are written for Almaty (UTC+5) at 14:50 on 4 November 2026: the page's clock and zone are pinned to it.
test.use({ timezoneId: 'Asia/Almaty', locale: 'en-US', viewport: { width: 1440, height: 1000 } });

const base = `/?view=users&tenant=tenant-alpha&id=${danaId}&tab=activity`;
const stateWith = (audit: UserAuditMock, more: Partial<MockConfigState> = {}): Partial<MockConfigState> => ({
  users: [dana, quiet],
  userAudit: { [danaId]: audit },
  ...more,
});
const reportRequests = (api: MockConfigHarness): RequestLog[] => api.requests.filter(request => request.pathname === '/users/audit');
/** The query of the latest report request. */
const lastQuery = (api: MockConfigHarness) => reportRequests(api).at(-1)!.searchParams;

async function openActivity(page: Page, audit: UserAuditMock = activityAudit(), path = base, more: Partial<MockConfigState> = {}) {
  await page.clock.install({ time: new Date(ACTIVITY_NOW) });
  // `install` starts the clock at that time and lets it run, so a first load that takes a second reads 14:50:01: the page's `new Date()` is
  // pinned instead, which is what "Updated 14:50:00" and the period's end depend on.
  await page.clock.setFixedTime(new Date(ACTIVITY_NOW));
  const api = await loadAdminApp(page, { path, state: stateWith(audit, more) });
  await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
  return api;
}

const timeline = (page: Page) => page.getByRole('region', { name: 'Timeline' });
const rows = (page: Page) => timeline(page).locator('tr.event-row');
const rowFor = (page: Page, title: string) => rows(page).filter({ hasText: title });
const panel = (page: Page) => page.getByRole('dialog', { name: 'Refresh token reused' });
/** The panel's content: slotted into the shared side panel, so it is reached from the host and not from the dialog. */
const panelBody = (page: Page) => page.locator('versola-activity-event-panel');
const chips = (page: Page) => page.getByRole('group', { name: 'Active filters' });
const pager = (page: Page) => ({
  newer: timeline(page).getByRole('button', { name: 'Newer' }),
  older: timeline(page).getByRole('button', { name: 'Older' }),
});

test.describe('first load', () => {
  test('shows the user, the period, the summary, the addresses and the timeline of the last 7 days', async ({ page }) => {
    const api = await openActivity(page);

    await expect(page.getByRole('tablist', { name: 'User sections' }).getByRole('tab')).toHaveText(['Profile', 'Roles', 'Devices', 'Activity']);
    await expect(page.getByRole('tab', { name: 'Activity' })).toHaveAttribute('aria-selected', 'true');

    const period = page.getByRole('group', { name: 'Period' });
    await expect(period.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText('Showing Oct 28, 14:50 – now (UTC+5)')).toBeVisible();

    const summary = page.getByRole('group', { name: 'Summary of this period' });
    await expect(summary.getByRole('button', { name: /^Sign-ins 38 34 succeeded/ })).toBeVisible();
    await expect(summary.getByRole('button', { name: /^Failed sign-ins 4 last Nov 4, 02:12:41/ })).toBeVisible();
    await expect(summary.getByRole('button', { name: /^Credential changes 2 password, passkey/ })).toBeVisible();
    await expect(summary.getByRole('button', { name: /^Admin actions on this user 1 password reset/ })).toBeVisible();

    const attention = page.getByRole('region', { name: 'Needs attention' });
    await expect(attention.getByRole('button')).toHaveCount(2);
    await expect(attention.getByRole('button').first()).toContainText('Password reset by an admin');
    await expect(attention.getByRole('button').nth(1)).toContainText('Refresh token reused');
    await expect(attention.locator('.result.danger')).toHaveText('High risk');

    const addresses = page.getByRole('region', { name: 'Addresses' });
    await expect(addresses.getByRole('row', { name: /203\.0\.113\.44/ })).toContainText('Netherlands · NL');
    await expect(addresses.getByRole('row', { name: /198\.51\.100\.7 / })).toContainText('Kazakhstan · KZ');
    await expect(addresses.getByRole('row', { name: /10\.0\.4\.12/ })).not.toContainText('·');
    await expect(page.getByRole('region', { name: 'Browsers and apps' })).toContainText('Firefox 132 · Windows');
    await expect(page.getByRole('region', { name: 'Browsers and apps' })).toContainText('ExampleShop 2.3 · iOS');

    await expect(timeline(page).getByRole('columnheader', { name: 'Time (Asia/Almaty, UTC+5)' })).toBeVisible();
    await expect(timeline(page).getByRole('rowgroup').getByRole('rowheader')).toHaveText(['Nov 4, 2026', 'Nov 3, 2026']);
    await expect(rows(page)).toHaveCount(10);
    const reuse = rowFor(page, 'Refresh token reused');
    await expect(reuse).toContainText('02:14:07');
    await expect(reuse).toContainText('High risk');
    await expect(reuse).toContainText('203.0.113.44');
    await expect(reuse).toContainText('NL');
    await expect(reuse).toContainText('mobile-app');
    await expect(reuse.locator('time')).toHaveAttribute('title', 'Nov 4, 2026, 02:14:07 (UTC+5)\nNov 3, 2026, 21:14:07 UTC');
    await expect(timeline(page)).toContainText('Updated 14:50:00');
    await expect(timeline(page)).toContainText('Events 1–10');
    await expect(timeline(page)).toContainText('Beginning of history');
    await expect(page.getByText('IP Geolocation by')).toBeVisible();

    const requests = reportRequests(api);
    expect(requests).toHaveLength(1);
    expect(requests[0]).toMatchObject({ method: 'GET', searchParams: { id: danaId, from: '2026-10-28T09:50:00Z', to: '2026-11-04T09:50:00Z', limit: '100' } });
    expect(requests[0].searchParams.cursor).toBeUndefined();
    expect(page.url()).toContain('range=7d');
  });

  test('draws no flag and no country for an address the API gave none for, and says nothing about geolocation without one', async ({ page }) => {
    const audit = activityAudit();
    for (const event of audit.events) delete event.country;
    for (const entry of (audit.summary as { ips: { country?: string }[] }).ips) delete entry.country;
    await openActivity(page, audit);
    await expect(rows(page)).toHaveCount(10);
    await expect(timeline(page).locator('.flag')).toHaveCount(0);
    await expect(page.getByText('IP Geolocation by')).toHaveCount(0);
  });

  test('has no export button or menu', async ({ page }) => {
    await openActivity(page);
    await expect(page.getByRole('button', { name: /export/i })).toHaveCount(0);
    await expect(page.getByRole('menu')).toHaveCount(0);
  });

  test('copies a link that holds the view', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await openActivity(page);
    await timeline(page).getByLabel('Result', { exact: true }).selectOption('failure');
    await page.getByRole('button', { name: 'Copy link to this view' }).click();
    await expect(page.getByText('Link copied')).toBeAttached();
    const copied = new URL(await page.evaluate(() => navigator.clipboard.readText()));
    expect(copied.searchParams.get('tab')).toBe('activity');
    expect(copied.searchParams.get('id')).toBe(danaId);
    expect(copied.searchParams.get('range')).toBe('7d');
    expect(copied.searchParams.get('result')).toBe('failure');
    expect(copied.searchParams.get('from')).toBe('2026-10-28T09:50:00Z');
  });
});

test.describe('the Activity tab', () => {
  test('is the last tab of the record, asks for nothing until it is opened, and puts its view in the URL', async ({ page }) => {
    await page.clock.install({ time: new Date(ACTIVITY_NOW) });
    const api = await loadAdminApp(page, { path: `/?view=users&tenant=tenant-alpha&id=${danaId}`, state: stateWith(activityAudit()) });
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
    await expect(page.getByRole('tablist', { name: 'User sections' }).getByRole('tab')).toHaveText(['Profile', 'Roles', 'Devices', 'Activity']);
    // Open the other tabs: the report is still not asked for.
    await page.getByRole('tab', { name: 'Devices' }).click();
    await expect(page.getByRole('region', { name: 'Signed-in devices' })).toBeVisible();
    await page.getByRole('tab', { name: 'Roles' }).click();
    await expect(page.getByRole('region', { name: `Roles in tenant-alpha` })).toBeVisible();
    expect(reportRequests(api)).toHaveLength(0);

    await page.getByRole('tab', { name: 'Activity' }).click();
    await expect(page.getByRole('region', { name: 'Timeline' })).toBeVisible();
    await expect(rows(page)).toHaveCount(10);
    expect(reportRequests(api)).toHaveLength(1);
    expect(page.url()).toContain('tab=activity');
    expect(page.url()).toContain('range=7d');
  });

  test('can be reached with the keyboard, and is only selected by Enter or Space (moving along the bar asks for nothing)', async ({ page }) => {
    await page.clock.install({ time: new Date(ACTIVITY_NOW) });
    const api = await loadAdminApp(page, { path: `/?view=users&tenant=tenant-alpha&id=${danaId}&tab=devices`, state: stateWith(activityAudit()) });
    await expect(page.getByRole('region', { name: 'Signed-in devices' })).toBeVisible();
    await page.getByRole('tab', { name: 'Devices' }).focus();
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('tab', { name: 'Activity' })).toBeFocused();
    expect(reportRequests(api)).toHaveLength(0);
    await page.keyboard.press('Enter');
    await expect(rows(page)).toHaveCount(10);
    expect(reportRequests(api)).toHaveLength(1);
  });

  test('drops its own view from the URL when another tab is chosen, and starts clean when opened again', async ({ page }) => {
    await openActivity(page);
    await timeline(page).getByLabel('Result', { exact: true }).selectOption('failure');
    expect(page.url()).toContain('result=failure');
    await page.getByRole('tab', { name: 'Profile' }).click();
    await expect(page.getByRole('region', { name: 'Identifiers' })).toBeVisible();
    for (const key of ['range=', 'from=', 'to=', 'result=', 'tab=']) expect(page.url()).not.toContain(key);
    await page.getByRole('tab', { name: 'Activity' }).click();
    await expect(rows(page)).toHaveCount(10);
    expect(page.url()).not.toContain('result=');
  });

  test('is not in the bar for an admin without audit:read, a direct link opens Profile, and no report is asked for', async ({ page }) => {
    const readOnly = { resources: { central: { permissions: ['users:read'] } }, isProd: false };
    await page.clock.install({ time: new Date(ACTIVITY_NOW) });
    const api = await loadAdminApp(page, { path: base, state: stateWith(activityAudit(), { myPermissions: readOnly }) });
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
    await expect(page.getByRole('tablist', { name: 'User sections' }).getByRole('tab')).toHaveText(['Profile', 'Roles', 'Devices']);
    await expect(page.getByRole('tab', { name: 'Profile' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('region', { name: 'Identifiers' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Timeline' })).toHaveCount(0);
    expect(reportRequests(api)).toHaveLength(0);
  });

  test('answers 403 from the report with a no-access message in the tab', async ({ page }) => {
    await openActivity(page, { events: [], status: 403, body: '' });
    await expect(page.getByRole('heading', { name: 'No access to Activity' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Activity' })).toBeVisible();
  });
});

test.describe('the period', () => {
  test('switches between 24 hours and 7 days, reloading page 1 and the summary with the new window', async ({ page }) => {
    const api = await openActivity(page);
    await page.getByRole('button', { name: '24 hours' }).click();
    await expect(page.getByRole('button', { name: '24 hours' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText('Showing Nov 3, 14:50 – now (UTC+5)')).toBeVisible();
    await expect(rowFor(page, 'Signed in')).toBeVisible();
    const last = lastQuery(api);
    expect(last).toMatchObject({ from: '2026-11-03T09:50:00Z', to: '2026-11-04T09:50:00Z' });
    expect(page.url()).toContain('range=24h');
  });

  test('asks for an explicit date and 24-hour time in the shown zone, and sends them as UTC', async ({ page }) => {
    const api = await openActivity(page);
    const custom = page.getByRole('button', { name: 'Custom range' });
    await custom.click();
    const dialog = page.getByRole('dialog', { name: 'Custom range' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('From date')).toBeFocused();
    await expect(dialog.getByLabel('From date')).toHaveValue('28 Oct 2026');
    await expect(dialog.getByLabel('From time (24-hour)')).toHaveValue('14:50');
    await expect(dialog).toContainText('Asia/Almaty (UTC+5). Events can be shown from Oct 28, 2026, 14:50 until now.');

    await dialog.getByLabel('From date').fill('1 Nov 2026');
    await dialog.getByLabel('From time (24-hour)').fill('00:00');
    await dialog.getByLabel('To date').fill('4 Nov 2026');
    await dialog.getByLabel('To time (24-hour)').fill('12:00');
    await dialog.getByRole('button', { name: 'Apply' }).click();

    await expect(dialog).toHaveCount(0);
    await expect(custom).toHaveAttribute('aria-pressed', 'true');
    await expect(custom).toBeFocused();
    await expect(page.getByText('Showing Nov 1, 00:00 – Nov 4, 12:00 (UTC+5)')).toBeVisible();
    const last = lastQuery(api);
    expect(last).toMatchObject({ from: '2026-10-31T19:00:00Z', to: '2026-11-04T07:00:00Z' });
    expect(page.url()).toContain('range=custom');
  });

  test('refuses a To that is not after From, inline, and keeps Apply off until it is fixed', async ({ page }) => {
    const api = await openActivity(page);
    await page.getByRole('button', { name: 'Custom range' }).click();
    const dialog = page.getByRole('dialog', { name: 'Custom range' });
    const requestsBefore = reportRequests(api).length;
    await dialog.getByLabel('From date').fill('4 Nov 2026');
    await dialog.getByLabel('From time (24-hour)').fill('00:00');
    await dialog.getByLabel('To date').fill('1 Nov 2026');
    await dialog.getByLabel('To time (24-hour)').fill('12:00');

    await expect(dialog.getByText('To must be after From.')).toBeVisible();
    await expect(dialog.getByLabel('To date')).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByLabel('To date')).toHaveAccessibleDescription('To must be after From.');
    await expect(dialog.getByLabel('From date')).toHaveAttribute('aria-invalid', 'false');
    await expect(dialog.getByRole('button', { name: 'Apply' })).toBeDisabled();

    await dialog.getByLabel('To date').fill('4 Nov 2026');
    await expect(dialog.getByText('To must be after From.')).toHaveCount(0);
    await expect(dialog.getByRole('button', { name: 'Apply' })).toBeEnabled();
    expect(reportRequests(api)).toHaveLength(requestsBefore);
  });

  test('refuses a field it cannot read, a From before retention and a To in the future', async ({ page }) => {
    await openActivity(page);
    await page.getByRole('button', { name: 'Custom range' }).click();
    const dialog = page.getByRole('dialog', { name: 'Custom range' });
    await dialog.getByLabel('From date').fill('last week');
    await expect(dialog.getByText('Enter the date like 1 Nov 2026 and the time like 14:30 (24-hour).')).toBeVisible();
    await dialog.getByLabel('From date').fill('1 Oct 2026');
    await expect(dialog.getByText(/From is before the first event that can be shown/)).toBeVisible();
    await dialog.getByLabel('From date').fill('1 Nov 2026');
    await dialog.getByLabel('To date').fill('9 Nov 2026');
    await expect(dialog.getByText('To is in the future.')).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Apply' })).toBeDisabled();
  });

  test('closes on Escape back to its button, and keeps focus inside while open', async ({ page }) => {
    await openActivity(page);
    const custom = page.getByRole('button', { name: 'Custom range' });
    await custom.click();
    const dialog = page.getByRole('dialog', { name: 'Custom range' });
    await expect(dialog.getByLabel('From date')).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(dialog.getByRole('button', { name: 'Apply' })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(dialog.getByLabel('From date')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(custom).toBeFocused();
  });

  test('goes back to the period that worked and says why when the server refuses the one asked for', async ({ page }) => {
    await openActivity(page);
    await page.route('**/users/audit**', route => route.fulfill({ status: 400, contentType: 'text/plain', body: 'audit_window_outside_retention: from is before the start of the retained window' }));
    await page.getByRole('button', { name: 'Custom range' }).click();
    const dialog = page.getByRole('dialog', { name: 'Custom range' });
    await dialog.getByLabel('From date').fill('1 Nov 2026');
    await dialog.getByRole('button', { name: 'Apply' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'That period was not accepted' })).toContainText('audit_window_outside_retention');
    await expect(page.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true');
    await expect(rows(page)).toHaveCount(10);
    expect(page.url()).toContain('range=7d');
  });
});

test.describe('time zone', () => {
  test('shows the local zone first and switches to UTC, remembering the choice', async ({ page }) => {
    await openActivity(page);
    const group = timeline(page).getByRole('group', { name: 'Time zone' });
    await expect(group.getByRole('button', { name: 'Local' })).toHaveAttribute('aria-pressed', 'true');
    await expect(rowFor(page, 'Refresh token reused')).toContainText('02:14:07');

    await group.getByRole('button', { name: 'UTC' }).click();
    await expect(group.getByRole('button', { name: 'UTC' })).toHaveAttribute('aria-pressed', 'true');
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (UTC)' })).toBeVisible();
    await expect(rowFor(page, 'Refresh token reused')).toContainText('21:14:07');
    await expect(timeline(page).getByRole('rowgroup').getByRole('rowheader')).toHaveText(['Nov 4, 2026', 'Nov 3, 2026']);
    await expect(rowFor(page, 'Passkey added')).toContainText('18:20:09');
    expect(await page.evaluate(() => localStorage.getItem('versola-time-zone'))).toBe('utc');
    await expect(page.getByText('Times are shown in UTC.')).toBeVisible();

    await page.reload();
    await expect(timeline(page).getByRole('group', { name: 'Time zone' }).getByRole('button', { name: 'UTC' })).toHaveAttribute('aria-pressed', 'true');
    await expect(rowFor(page, 'Refresh token reused')).toContainText('21:14:07');

    await timeline(page).getByRole('group', { name: 'Time zone' }).getByRole('button', { name: 'Local' }).click();
    expect(await page.evaluate(() => localStorage.getItem('versola-time-zone'))).toBe('local');
    await expect(rowFor(page, 'Refresh token reused')).toContainText('02:14:07');
  });

  test('reads the custom range fields in the zone that is shown', async ({ page }) => {
    const api = await openActivity(page);
    await timeline(page).getByRole('group', { name: 'Time zone' }).getByRole('button', { name: 'UTC' }).click();
    await page.getByRole('button', { name: 'Custom range' }).click();
    const dialog = page.getByRole('dialog', { name: 'Custom range' });
    await expect(dialog.getByLabel('From time (24-hour)')).toHaveValue('09:50');
    await expect(dialog).toContainText('UTC. Events can be shown from Oct 28, 2026, 09:50 until now.');
    await dialog.getByLabel('From date').fill('1 Nov 2026');
    await dialog.getByLabel('From time (24-hour)').fill('00:00');
    await dialog.getByRole('button', { name: 'Apply' }).click();
    const last = lastQuery(api);
    expect(last.from).toBe('2026-11-01T00:00:00Z');
  });
});

test.describe('a browser whose own zone is UTC', () => {
  test.use({ timezoneId: 'UTC' });

  test('names it once in the column header and the period', async ({ page }) => {
    await openActivity(page);
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (UTC)' })).toBeVisible();
    await expect(page.getByText('Showing Oct 28, 09:50 – now (UTC)')).toBeVisible();
    await expect(rowFor(page, 'Refresh token reused')).toContainText('21:14:07');
  });
});

test.describe('filters', () => {
  test('filter the loaded page without a request, show chips and clear them', async ({ page }) => {
    const api = await openActivity(page);
    const requests = reportRequests(api).length;

    await timeline(page).getByLabel('Result', { exact: true }).selectOption('failure');
    await expect(rows(page)).toHaveCount(4);
    await expect(chips(page)).toContainText('Result: Failed');

    await timeline(page).getByRole('searchbox', { name: 'Search events, clients and addresses' }).fill('wrong_code');
    await expect(rows(page)).toHaveCount(2);
    await expect(chips(page)).toContainText('Search: wrong_code');
    expect(page.url()).toContain('q=wrong_code');
    expect(page.url()).toContain('result=failure');

    await chips(page).getByRole('button', { name: 'Remove filter Search: wrong_code' }).click();
    await expect(rows(page)).toHaveCount(4);
    await expect(chips(page)).not.toContainText('Search');

    await chips(page).getByRole('button', { name: 'Clear all' }).click();
    await expect(rows(page)).toHaveCount(10);
    await expect(chips(page)).toHaveCount(0);
    expect(page.url()).not.toContain('result=');
    expect(reportRequests(api)).toHaveLength(requests);
  });

  test('filter by client and by category pill, and search the address and the country name', async ({ page }) => {
    await openActivity(page);
    const filters = timeline(page);
    await filters.getByLabel('Client', { exact: true }).selectOption('mobile-app');
    await expect(rows(page)).toHaveCount(3);
    await expect(chips(page)).toContainText('Client: mobile-app');
    await filters.getByLabel('Client', { exact: true }).selectOption('');

    await filters.getByRole('group', { name: 'Category' }).getByRole('button', { name: 'Tokens' }).click();
    await expect(rows(page)).toHaveCount(4);
    await filters.getByRole('group', { name: 'Category' }).getByRole('button', { name: 'Admin' }).click();
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page)).toContainText('Password reset by an admin');
    await filters.getByRole('group', { name: 'Category' }).getByRole('button', { name: 'All' }).click();

    await filters.getByRole('searchbox', { name: 'Search events, clients and addresses' }).fill('netherlands');
    await expect(rows(page)).toHaveCount(4);
    await filters.getByRole('searchbox', { name: 'Search events, clients and addresses' }).fill('203.0.113.44');
    await expect(rows(page)).toHaveCount(4);
  });

  test('say so when nothing on the page matches, with a way out', async ({ page }) => {
    await openActivity(page);
    await timeline(page).getByRole('searchbox', { name: 'Search events, clients and addresses' }).fill('zzz-nothing');
    await expect(timeline(page).getByRole('heading', { name: 'No events match these filters' })).toBeVisible();
    await timeline(page).getByRole('button', { name: 'Clear all' }).first().click();
    await expect(rows(page)).toHaveCount(10);
  });

  test('are set by a click on a summary card, which also says what it shows', async ({ page }) => {
    await openActivity(page);
    await page.getByRole('group', { name: 'Summary of this period' }).getByRole('button', { name: /^Failed sign-ins/ }).click();
    await expect(rows(page)).toHaveCount(2);
    await expect(chips(page)).toContainText('Result: Failed');
    await expect(timeline(page).getByRole('group', { name: 'Category' }).getByRole('button', { name: 'Sign-in' })).toHaveAttribute('aria-pressed', 'true');
    await expect(timeline(page).getByRole('heading', { name: 'Timeline' })).toBeFocused();

    await page.getByRole('group', { name: 'Summary of this period' }).getByRole('button', { name: /^Admin actions/ }).click();
    await expect(rows(page)).toHaveCount(1);
    await expect(chips(page)).toContainText('Clear all');
    await expect(chips(page)).not.toContainText('Result');
  });

  test('come back from a link', async ({ page }) => {
    await openActivity(page, activityAudit(), `${base}&range=7d&from=2026-10-28T09%3A50%3A00Z&to=2026-11-04T09%3A50%3A00Z&result=failure&q=wrong_code`);
    await expect(rows(page)).toHaveCount(2);
    await expect(timeline(page).getByRole('searchbox', { name: 'Search events, clients and addresses' })).toHaveValue('wrong_code');
    await expect(timeline(page).getByLabel('Result', { exact: true })).toHaveValue('failure');
  });
});

test.describe('the event panel', () => {
  test('opens from the keyboard, selects its row, covers the page instead of moving it, and returns focus on Escape', async ({ page }) => {
    await openActivity(page);
    const button = rowFor(page, 'Refresh token reused').getByRole('button', { name: /Refresh token reused/ });
    await button.focus();
    await page.keyboard.press('Enter');

    const dialog = panel(page);
    await expect(dialog).toBeVisible();
    await expect(rowFor(page, 'Refresh token reused')).toHaveAttribute('aria-selected', 'true');
    await expect(rowFor(page, 'Signed in')).toHaveAttribute('aria-selected', 'false');
    expect(page.url()).toContain(`event=${reuseEventId}`);
    // The page keeps its width: the shared panel's inset is not applied.
    expect(await page.evaluate(() => document.documentElement.style.getPropertyValue('--panel-inset'))).toBe('');
    await expect(page.getByText('Event · Tokens')).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(rowFor(page, 'Refresh token reused')).toHaveAttribute('aria-selected', 'false');
    await expect(button).toBeFocused();
    expect(page.url()).not.toContain('event=');
  });

  test('opens with Space as well, and with a click on the row', async ({ page }) => {
    await openActivity(page);
    await rowFor(page, 'Token refused').getByRole('button').focus();
    await page.keyboard.press('Space');
    await expect(page.getByRole('dialog', { name: 'Token refused' })).toBeVisible();
    await page.keyboard.press('Escape');
    await rowFor(page, 'Code check failed').first().locator('td.time').click();
    await expect(page.getByRole('dialog', { name: 'Code check failed' })).toBeVisible();
  });

  test('says what happened: when in the shown zone and UTC, result, type, client, address, browser, ids and details', async ({ page }) => {
    await openActivity(page);
    await rowFor(page, 'Refresh token reused').click();
    const dialog = panelBody(page);
    const what = dialog.getByRole('region', { name: 'What happened' });
    await expect(what).toContainText('Nov 4, 2026, 02:14:07 (UTC+5)');
    await expect(what).toContainText('Nov 3, 2026, 21:14:07 UTC');
    await expect(what).toContainText('Refused, the whole token family was revoked');
    await expect(what.locator('dd').filter({ hasText: 'token.reuse_detected' })).toBeVisible();
    await expect(what).toContainText('mobile-app');
    await expect(what).toContainText('203.0.113.44');
    await expect(what).toContainText('Netherlands · NL');
    await expect(what).toContainText('Firefox 132 · Windows');
    await expect(what).toContainText('s_8Qm2LpA9kT4');
    await expect(what).toContainText(reuseTraceId);
    await expect(dialog.getByText('High risk')).toBeVisible();
    await expect(dialog.getByText('Token family revoked')).toBeVisible();

    const details = dialog.getByRole('region', { name: 'Details' });
    await expect(details.locator('pre')).toContainText('"tokenType": "refresh_token"');
    await expect(details.locator('pre')).not.toContainText('Bearer');
    await expect(details.locator('pre')).toHaveAttribute('tabindex', '0');
  });

  test('shows the time in UTC once, when the zone shown is UTC', async ({ page }) => {
    await openActivity(page);
    await timeline(page).getByRole('group', { name: 'Time zone' }).getByRole('button', { name: 'UTC' }).click();
    await rowFor(page, 'Refresh token reused').click();
    const what = panelBody(page).getByRole('region', { name: 'What happened' });
    await expect(what).toContainText('Nov 3, 2026, 21:14:07 UTC');
    await expect(what).not.toContainText('UTC+5');
  });

  test('copies the event type, the session id and the request id', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await openActivity(page);
    await rowFor(page, 'Refresh token reused').click();
    const dialog = panelBody(page);
    const clipboard = () => page.evaluate(() => navigator.clipboard.readText());
    await dialog.getByRole('button', { name: 'Copy event type' }).click();
    expect(await clipboard()).toBe('token.reuse_detected');
    await dialog.getByRole('button', { name: 'Copy session ID' }).click();
    expect(await clipboard()).toBe('s_8Qm2LpA9kT4');
    await dialog.getByRole('button', { name: 'Copy request ID' }).click();
    expect(await clipboard()).toBe(reuseTraceId);
    await expect(dialog.getByText('request ID copied')).toBeAttached();
  });

  test('has one action, which shows the events of the same request', async ({ page }) => {
    const api = await openActivity(page);
    const requests = reportRequests(api).length;
    await rowFor(page, 'Refresh token reused').click();
    const dialog = panelBody(page);
    await expect(dialog.getByRole('button', { name: 'Show events of this request' })).toBeVisible();
    // Close is the other button of the panel; nothing else acts.
    await expect(dialog.getByRole('button')).toHaveCount(5);

    await dialog.getByRole('button', { name: 'Show events of this request' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(rows(page)).toHaveCount(2);
    await expect(rows(page).first()).toContainText('Refresh token reused');
    await expect(rows(page).nth(1)).toContainText('Token refused');
    await expect(chips(page)).toContainText('Request: 4bf92f35…');
    expect(page.url()).toContain(`trace=${reuseTraceId}`);
    expect(reportRequests(api)).toHaveLength(requests);
  });

  test('closes with its X, and opens from the needs-attention list too', async ({ page }) => {
    await openActivity(page);
    const attention = page.getByRole('region', { name: 'Needs attention' }).getByRole('button', { name: /Refresh token reused/ });
    await attention.click();
    const dialog = panel(page);
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Close panel' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(attention).toBeFocused();
  });

  test('opens the matching timeline event for an administrator action, and offers no request filter for it', async ({ page }) => {
    await openActivity(page);
    await page.getByRole('region', { name: 'Needs attention' }).getByRole('button', { name: /Password reset by an admin/ }).click();
    await expect(page.getByRole('dialog', { name: 'Password reset by an admin' })).toBeVisible();
    const dialog = panelBody(page);
    await expect(rowFor(page, 'Password reset by an admin')).toHaveAttribute('aria-selected', 'true');
    await expect(dialog.getByRole('region', { name: 'What happened' })).toContainText('m.omarov');
    await expect(dialog.getByRole('button', { name: 'Show events of this request' })).toHaveCount(0);
  });

  test('is restored from a link', async ({ page }) => {
    await openActivity(page, activityAudit(), `${base}&range=7d&from=2026-10-28T09%3A50%3A00Z&to=2026-11-04T09%3A50%3A00Z&event=${reuseEventId}`);
    await expect(panel(page)).toBeVisible();
    await expect(rowFor(page, 'Refresh token reused')).toHaveAttribute('aria-selected', 'true');
  });
});

test.describe('paging', () => {
  const paged = () => pagedActivityAudit(140);

  test('walks to older pages with the cursor the API gave and back to newer ones, never jumping to a page number', async ({ page }) => {
    const api = await openActivity(page, paged(), `${base}&limit=50`);
    await expect(rows(page)).toHaveCount(50);
    await expect(timeline(page)).toContainText('Events 1–50');
    await expect(pager(page).newer).toBeDisabled();
    await expect(pager(page).older).toBeEnabled();
    await expect(timeline(page).getByRole('combobox', { name: 'Events per page' })).toHaveValue('50');
    await expect(timeline(page).getByText(/^\d+$/)).toHaveCount(0);

    await pager(page).older.click();
    await expect(timeline(page)).toContainText('Events 51–100');
    await expect(pager(page).newer).toBeEnabled();
    await expect(timeline(page).getByRole('status')).toHaveText('Events 51–100.');
    const second = lastQuery(api);
    expect(second.cursor).toBeTruthy();
    expect(second).toMatchObject({ from: '2026-10-28T09:50:00Z', to: '2026-11-04T09:50:00Z', limit: '50' });
    expect(page.url()).toContain('cursors=');
    // The summary belongs to page 1: the cards stay.
    await expect(page.getByRole('group', { name: 'Summary of this period' })).toBeVisible();

    await pager(page).older.click();
    await expect(timeline(page)).toContainText('Events 101–150');
    await expect(timeline(page)).toContainText('Beginning of history');
    await expect(pager(page).older).toBeDisabled();
    await expect(timeline(page).getByRole('status')).toHaveText('Events 101–150. Beginning of history.');

    await pager(page).newer.click();
    await expect(timeline(page)).toContainText('Events 51–100');
    await expect(timeline(page)).not.toContainText('Beginning of history');
    await pager(page).newer.click();
    await expect(timeline(page)).toContainText('Events 1–50');
    await expect(pager(page).newer).toBeDisabled();
    expect(page.url()).not.toContain('cursors=');
    const third = lastQuery(api);
    expect(third.cursor).toBeUndefined();
  });

  test('keeps the cursors in the link, so Newer works after a reload', async ({ page }) => {
    await openActivity(page, paged(), `${base}&limit=50`);
    await pager(page).older.click();
    await pager(page).older.click();
    await expect(timeline(page)).toContainText('Events 101–150');
    await page.reload();
    await expect(timeline(page)).toContainText('Events 101–150');
    await pager(page).newer.click();
    await expect(timeline(page)).toContainText('Events 51–100');
  });

  test('scrolls to the top of the timeline when the page changes', async ({ page }) => {
    await openActivity(page, paged(), `${base}&limit=25`);
    await pager(page).older.scrollIntoViewIfNeeded();
    await pager(page).older.click();
    await expect(timeline(page)).toContainText('Events 26–50');
    const top = await timeline(page).evaluate(node => node.getBoundingClientRect().top);
    expect(top).toBeGreaterThanOrEqual(-1);
    expect(top).toBeLessThan(40);
  });

  test('goes back to page 1 when the size, the period or a refresh asks for it', async ({ page }) => {
    const api = await openActivity(page, paged(), `${base}&limit=50`);
    await pager(page).older.click();
    await expect(timeline(page)).toContainText('Events 51–100');
    await timeline(page).getByRole('combobox', { name: 'Events per page' }).selectOption('25');
    await expect(timeline(page)).toContainText('Events 1–25');
    expect(page.url()).toContain('limit=25');
    expect(page.url()).not.toContain('cursors=');

    await pager(page).older.click();
    await expect(timeline(page)).toContainText('Events 26–50');
    await timeline(page).getByRole('button', { name: 'Refresh timeline' }).click();
    await expect(timeline(page)).toContainText('Events 1–25');
    const refreshed = lastQuery(api);
    expect(refreshed.cursor).toBeUndefined();

    await pager(page).older.click();
    await page.getByRole('button', { name: '24 hours' }).click();
    await expect(timeline(page)).toContainText('Events 1–');
    expect(page.url()).not.toContain('cursors=');
  });

  test('offers the sizes 25, 50 and 100', async ({ page }) => {
    await openActivity(page);
    const sizes = timeline(page).getByRole('combobox', { name: 'Events per page' }).locator('option');
    await expect(sizes).toHaveText(['25 per page', '50 per page', '100 per page']);
    await expect(timeline(page).getByRole('combobox', { name: 'Events per page' })).toHaveValue('100');
  });

  test('refreshes by hand only: no request until the icon is used, and then one', async ({ page }) => {
    const api = await openActivity(page);
    await page.waitForTimeout(500);
    expect(reportRequests(api)).toHaveLength(1);
    await timeline(page).getByRole('button', { name: 'Refresh timeline' }).click();
    await expect(timeline(page)).toContainText('Updated 14:50');
    await expect.poll(() => reportRequests(api).length).toBe(2);
  });
});

test.describe('states', () => {
  test('shows skeletons and marks the timeline busy while loading', async ({ page }) => {
    await openActivity(page, activityAudit({ delayMs: 1500 }));
    await expect(timeline(page)).toHaveAttribute('aria-busy', 'true');
    await expect(timeline(page).locator('.skeleton-row')).toHaveCount(8);
    await expect(timeline(page)).toContainText('Updating…');
    await expect(timeline(page).getByRole('button', { name: 'Refresh timeline' })).toBeDisabled();
    await expect(rows(page)).toHaveCount(10);
    await expect(timeline(page)).toHaveAttribute('aria-busy', 'false');
    await expect(timeline(page).locator('.skeleton-row')).toHaveCount(0);
  });

  test('says there is no activity in the period, and offers 7 days from a shorter one', async ({ page }) => {
    const api = await openActivity(page, emptyActivityAudit(), `${base}&range=24h`);
    await expect(timeline(page).getByRole('heading', { name: 'No activity in this period' })).toBeVisible();
    await expect(page.getByRole('group', { name: 'Summary of this period' })).toHaveCount(0);
    await timeline(page).getByRole('button', { name: 'Show 7 days' }).click();
    await expect(page.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true');
    const last = lastQuery(api);
    expect(last.from).toBe('2026-10-28T09:50:00Z');
    // Already on 7 days: nothing longer to offer.
    await expect(timeline(page).getByRole('button', { name: 'Show 7 days' })).toHaveCount(0);
  });

  test('says that sign-in history is unavailable, keeps what came back and tries again', async ({ page }) => {
    const api = await openActivity(page, partialActivityAudit());
    const banner = page.getByRole('status').filter({ hasText: 'Sign-in history is unavailable right now' });
    await expect(banner).toContainText('only administrator actions are shown');

    const summary = page.getByRole('group', { name: 'Summary of this period' });
    for (const name of ['Sign-ins', 'Failed sign-ins', 'Credential changes']) {
      const card = summary.locator('.stat').filter({ hasText: name }).first();
      await expect(card).toContainText('unavailable');
      await expect(card).toContainText('—');
      await expect(card.getByRole('button')).toHaveCount(0);
    }
    await expect(summary.getByRole('button', { name: /^Admin actions on this user 1/ })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Addresses' })).toHaveCount(0);
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page)).toContainText('Password reset by an admin');

    const before = reportRequests(api).length;
    await banner.getByRole('button', { name: 'Try again' }).click();
    await expect.poll(() => reportRequests(api).length).toBe(before + 1);
  });

  test('shows an error with Retry, and loads on Retry', async ({ page }) => {
    const api = await openActivity(page, { events: [], status: 500, body: 'audit store unavailable' });
    const error = timeline(page).getByRole('alert');
    await expect(error).toContainText('Could not load activity');
    await expect(error).toContainText('audit store unavailable');
    await expect(rows(page)).toHaveCount(0);

    api.state.userAudit[danaId] = activityAudit();
    await error.getByRole('button', { name: 'Retry' }).click();
    await expect(rows(page)).toHaveCount(10);
    await expect(timeline(page).getByRole('alert')).toHaveCount(0);
    await expect(page.getByRole('group', { name: 'Summary of this period' })).toBeVisible();
  });

  test('says a busy report is busy', async ({ page }) => {
    await openActivity(page, { events: [], status: 503, body: '' });
    await expect(timeline(page).getByRole('alert')).toContainText('Too many reports are running');
  });
});

test.describe('layout', () => {
  for (const width of [1440, 1024, 375]) {
    test(`does not scroll the page sideways at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await openActivity(page);
      await expect(rows(page)).toHaveCount(10);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow).toBeLessThanOrEqual(0);
    });
  }

  test('keeps a long unbroken value inside its cell', async ({ page }) => {
    const audit = activityAudit();
    audit.events[0].reason = 'x'.repeat(200);
    audit.events[0].clientId = 'c'.repeat(120);
    await page.setViewportSize({ width: 1024, height: 900 });
    await openActivity(page, audit);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
