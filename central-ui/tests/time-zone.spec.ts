import { expect, test, type Page } from '@playwright/test';
import { loadAdminApp } from './fixtures';
import { defaultMyPermissions, type MockConfigHarness, type MockConfigState } from './mocks';
import { dana, danaId, quiet } from './users-fixtures';
import { ACTIVITY_NOW, activityAudit } from './user-activity-fixtures';

// The browser is in Berlin (UTC+1 in November); the administrator's own zone, where they differ, is Almaty (UTC+5).
test.use({ timezoneId: 'Europe/Berlin', locale: 'en-US', viewport: { width: 1440, height: 1000 } });

const activityPath = `/?view=users&tenant=tenant-alpha&id=${danaId}&tab=activity`;
/** What edge names in `/permissions/me`: the `zoneinfo` of the signed-in administrator's profile (set on the Security page). */
const zoneOf = (timeZone: string | undefined): Partial<MockConfigState> => ({ myPermissions: { ...defaultMyPermissions, timeZone } });

async function openActivity(page: Page, state: Partial<MockConfigState> = {}) {
  await page.clock.install({ time: new Date(ACTIVITY_NOW) });
  const api = await loadAdminApp(page, {
    path: activityPath,
    state: { users: [dana, quiet], userAudit: { [danaId]: activityAudit() }, ...state },
  });
  await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
  return api;
}

const timeline = (page: Page) => page.getByRole('region', { name: 'Timeline' });
const zoneGroup = (page: Page) => timeline(page).getByRole('group', { name: 'Time zone' });
const reuseRow = (page: Page) => timeline(page).locator('tr.event-row').filter({ hasText: 'Refresh token reused' });
const reportRequests = (api: MockConfigHarness) => api.requests.filter(request => request.pathname === '/users/audit');

test.describe('the Activity tab with a profile zone', () => {
  test('reads in the profile zone by default on a browser in another zone, and names it in the header', async ({ page }) => {
    await openActivity(page, zoneOf('Asia/Almaty'));
    await expect(zoneGroup(page).getByRole('button', { name: 'My zone' })).toHaveAttribute('aria-pressed', 'true');
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (Asia/Almaty, UTC+5)' })).toBeVisible();
    await expect(reuseRow(page)).toContainText('02:14:07');
    await expect(page.getByText('Showing Oct 28, 14:50 – now (UTC+5)')).toBeVisible();
    await expect(page.getByText('Times are shown in your time zone (Asia/Almaty), set on My Security.')).toBeVisible();
    // UTC is on hover, with the zone's own offset
    await expect(reuseRow(page).locator('time')).toHaveAttribute('title', /Nov 3, 2026, 21:14:07 UTC/);
  });

  test('still switches to Local and UTC, remembers it, and goes back to the profile zone', async ({ page }) => {
    await openActivity(page, zoneOf('Asia/Almaty'));
    await zoneGroup(page).getByRole('button', { name: 'Local' }).click();
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (Europe/Berlin, UTC+1)' })).toBeVisible();
    await expect(reuseRow(page)).toContainText('22:14:07');
    await zoneGroup(page).getByRole('button', { name: 'UTC' }).click();
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (UTC)' })).toBeVisible();
    await expect(reuseRow(page)).toContainText('21:14:07');

    await page.reload();
    await expect(zoneGroup(page).getByRole('button', { name: 'UTC' })).toHaveAttribute('aria-pressed', 'true');
    await zoneGroup(page).getByRole('button', { name: 'My zone' }).click();
    await expect(reuseRow(page)).toContainText('02:14:07');
    expect(await page.evaluate(() => localStorage.getItem('versola-time-zone'))).toBe('zone');
  });

  test('reads the custom range in the profile zone and sends it as UTC', async ({ page }) => {
    const api = await openActivity(page, zoneOf('Asia/Almaty'));
    await page.getByRole('button', { name: 'Custom range' }).click();
    const dialog = page.getByRole('dialog', { name: 'Custom range' });
    await expect(dialog.getByLabel('From time (24-hour)')).toHaveValue('14:50');
    await expect(dialog).toContainText('Asia/Almaty (UTC+5).');
    await dialog.getByLabel('From date').fill('1 Nov 2026');
    await dialog.getByLabel('From time (24-hour)').fill('00:00');
    await dialog.getByLabel('To date').fill('4 Nov 2026');
    await dialog.getByLabel('To time (24-hour)').fill('12:00');
    await dialog.getByRole('button', { name: 'Apply' }).click();
    expect(reportRequests(api).at(-1)!.searchParams).toMatchObject({ from: '2026-10-31T19:00:00Z', to: '2026-11-04T07:00:00Z' });
  });

  test('offers no second button for a profile zone that is the browser zone', async ({ page }) => {
    await openActivity(page, zoneOf('Europe/Berlin'));
    await expect(zoneGroup(page).getByRole('button')).toHaveText(['Local', 'UTC']);
    await expect(zoneGroup(page).getByRole('button', { name: 'Local' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('falls back to the browser zone when no zone is saved, or the saved one is not a zone', async ({ page }) => {
    await openActivity(page);
    await expect(zoneGroup(page).getByRole('button')).toHaveText(['Local', 'UTC']);
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (Europe/Berlin, UTC+1)' })).toBeVisible();
    await expect(page.getByText("Times are shown in your browser's time zone (Europe/Berlin).")).toBeVisible();
  });

  test('falls back to the browser zone when the saved zone is not a name this browser knows', async ({ page }) => {
    await openActivity(page, zoneOf('Mars/Olympus'));
    await expect(zoneGroup(page).getByRole('button')).toHaveText(['Local', 'UTC']);
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (Europe/Berlin, UTC+1)' })).toBeVisible();
  });

});

test.describe('the other times on a user record', () => {
  const session = { ...dana.sessions[0], createdAt: '2026-11-04T09:50:00Z', expiresAt: '2026-11-11T09:50:00Z', idleExpiresAt: '2026-11-04T10:50:00Z' };
  const state = { users: [{ ...dana, sessions: [session] }, quiet] };

  async function openDevices(page: Page, more: Partial<MockConfigState>) {
    await page.clock.install({ time: new Date(ACTIVITY_NOW) });
    await loadAdminApp(page, { path: `/?view=users&tenant=tenant-alpha&id=${danaId}&tab=devices`, state: { ...state, ...more } });
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
  }

  test('read in the profile zone, in the same text on every browser', async ({ page }) => {
    await openDevices(page, zoneOf('Asia/Almaty'));
    await expect(page.locator('[data-life="signed-in"] dd')).toHaveText('Nov 4, 14:50');
  });

  test('read in the browser zone without a profile zone', async ({ page }) => {
    await openDevices(page, {});
    await expect(page.locator('[data-life="signed-in"] dd')).toHaveText('Nov 4, 10:50');
  });
});
