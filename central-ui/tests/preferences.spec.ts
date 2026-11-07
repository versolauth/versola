import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';
import { defaultMyPermissions, type MockConfigHarness, type MockConfigState } from './mocks';
import { dana, danaId, quiet } from './users-fixtures';
import { ACTIVITY_NOW, activityAudit } from './user-activity-fixtures';

// The browser is in Berlin (UTC+1 in November); the administrator's own zone, where they differ, is Almaty (UTC+5).
test.use({ timezoneId: 'Europe/Berlin', locale: 'en-US', viewport: { width: 1440, height: 1000 } });

const activityPath = `/?view=users&tenant=tenant-alpha&id=${danaId}&tab=activity`;
const withoutPreferences = {
  ...defaultMyPermissions,
  resources: { central: { permissions: defaultMyPermissions.resources.central.permissions.filter(permission => permission !== 'preferences:manage') } },
};

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

async function openPreferences(page: Page, state: Partial<MockConfigState> = {}) {
  const api = await loadAdminApp(page, { path: '/?view=preferences', state });
  await expect(page.getByRole('heading', { level: 1, name: 'Preferences' })).toBeVisible();
  return api;
}

const card = (page: Page) => page.getByRole('region', { name: 'Time zone' });

test.describe('the Preferences page', () => {
  test('says the browser zone is used while none is saved, and is reached from the sidebar footer', async ({ page }) => {
    await loadAdminApp(page, { path: '/?view=users&tenant=tenant-alpha' });
    await page.getByRole('button', { name: 'Preferences' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Preferences' })).toBeVisible();
    expect(page.url()).toContain('view=preferences');
    await expect(card(page)).toContainText("Not set. Times follow this browser's time zone, Europe/Berlin.");
  });

  test('saves a zone picked by typing a place, sends the IANA name, and shows it with its offset', async ({ page }) => {
    const api = await openPreferences(page);
    await card(page).getByRole('button', { name: 'Edit time zone' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Edit time zone' })).toBeVisible();

    const field = page.getByRole('combobox', { name: 'Time zone' });
    await field.fill('almaty');
    await page.getByRole('option', { name: 'Asia/Almaty (UTC+5)' }).click();
    await page.getByRole('button', { name: 'Save time zone' }).click();

    const request = await findRequest(api.requests, 'PATCH', '/users/preferences');
    expect(request.body).toEqual({ zoneinfo: 'Asia/Almaty' });
    await expect(page.getByRole('status')).toContainText('Time zone saved');
    await expect(card(page)).toContainText('Asia/Almaty');
    await expect(card(page)).toContainText('UTC+5 now');
    expect(api.state.myZoneinfo).toBe('Asia/Almaty');
  });

  test('shows a saved zone and clears it with the field emptied', async ({ page }) => {
    const api = await openPreferences(page, { myZoneinfo: 'Asia/Almaty' });
    await expect(card(page)).toContainText('Asia/Almaty');
    await card(page).getByRole('button', { name: 'Edit time zone' }).click();
    const field = page.getByRole('combobox', { name: 'Time zone' });
    await expect(field).toHaveValue('Asia/Almaty (UTC+5)');
    await page.getByRole('button', { name: /^Clear/ }).click();
    await page.getByRole('button', { name: 'Save time zone' }).click();

    const request = await findRequest(api.requests, 'PATCH', '/users/preferences');
    expect(request.body).toEqual({ zoneinfo: null });
    await expect(page.getByRole('status')).toContainText('Time zone removed');
    await expect(card(page)).toContainText("Not set. Times follow this browser's time zone");
    expect(api.state.myZoneinfo).toBeNull();
  });

  test('keeps the editor open with central\'s reason when the save is refused', async ({ page }) => {
    await openPreferences(page);
    await page.route('**/users/preferences', route => route.request().method() === 'PATCH'
      ? route.fulfill({ status: 400, contentType: 'text/plain', body: 'zoneinfo is not a name of the IANA time zone database' })
      : route.fallback());
    await card(page).getByRole('button', { name: 'Edit time zone' }).click();
    await page.getByRole('combobox', { name: 'Time zone' }).fill('berlin');
    await page.getByRole('option', { name: /^Europe\/Berlin/ }).click();
    await page.getByRole('button', { name: 'Save time zone' }).click();
    await expect(page.getByRole('alert')).toContainText('not a name of the IANA time zone database');
    await expect(page.getByRole('button', { name: 'Save time zone' })).toBeEnabled();
  });

  test('Cancel returns to the page and sends nothing', async ({ page }) => {
    const api = await openPreferences(page);
    await card(page).getByRole('button', { name: 'Edit time zone' }).click();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(card(page)).toBeVisible();
    expect(api.requests.filter(request => request.method === 'PATCH')).toHaveLength(0);
  });

  test('is not offered, and not asked for, to an administrator without preferences:manage', async ({ page }) => {
    const api = await loadAdminApp(page, { path: '/?view=users&tenant=tenant-alpha', state: { myPermissions: withoutPreferences } });
    await expect(page.getByRole('button', { name: 'Users' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Preferences' })).toHaveCount(0);
    expect(api.requests.filter(request => request.pathname === '/users/preferences')).toHaveLength(0);
  });
});

test.describe('the Activity tab with a profile zone', () => {
  test('reads in the profile zone by default on a browser in another zone, and names it in the header', async ({ page }) => {
    await openActivity(page, { myZoneinfo: 'Asia/Almaty' });
    await expect(zoneGroup(page).getByRole('button', { name: 'My zone' })).toHaveAttribute('aria-pressed', 'true');
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (Asia/Almaty, UTC+5)' })).toBeVisible();
    await expect(reuseRow(page)).toContainText('02:14:07');
    await expect(page.getByText('Showing Oct 28, 14:50 – now (UTC+5)')).toBeVisible();
    await expect(page.getByText('Times are shown in your time zone from Preferences (Asia/Almaty).')).toBeVisible();
    // UTC is on hover, with the zone's own offset
    await expect(reuseRow(page).locator('time')).toHaveAttribute('title', /Nov 3, 2026, 21:14:07 UTC/);
  });

  test('still switches to Local and UTC, remembers it, and goes back to the profile zone', async ({ page }) => {
    await openActivity(page, { myZoneinfo: 'Asia/Almaty' });
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
    const api = await openActivity(page, { myZoneinfo: 'Asia/Almaty' });
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
    await openActivity(page, { myZoneinfo: 'Europe/Berlin' });
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
    await openActivity(page, { myZoneinfo: 'Mars/Olympus' });
    await expect(zoneGroup(page).getByRole('button')).toHaveText(['Local', 'UTC']);
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (Europe/Berlin, UTC+1)' })).toBeVisible();
  });

  test('falls back to the browser zone when the preferences cannot be read', async ({ page }) => {
    await openActivity(page, { myZoneinfo: 'Asia/Almaty', myPreferencesStatus: 500 });
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (Europe/Berlin, UTC+1)' })).toBeVisible();
  });

  test('an administrator without preferences:manage is on the browser zone and is not asked', async ({ page }) => {
    const api = await openActivity(page, { myZoneinfo: 'Asia/Almaty', myPermissions: withoutPreferences });
    await expect(timeline(page).getByRole('columnheader', { name: 'Time (Europe/Berlin, UTC+1)' })).toBeVisible();
    expect(api.requests.filter(request => request.pathname === '/users/preferences')).toHaveLength(0);
  });

  test('a zone saved on Preferences replaces an earlier switch to Local in this browser', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('versola-time-zone', 'local'));
    await openPreferences(page);
    await card(page).getByRole('button', { name: 'Edit time zone' }).click();
    await page.getByRole('combobox', { name: 'Time zone' }).fill('almaty');
    await page.getByRole('option', { name: 'Asia/Almaty (UTC+5)' }).click();
    await page.getByRole('button', { name: 'Save time zone' }).click();
    await expect(page.getByRole('status')).toContainText('Time zone saved');
    expect(await page.evaluate(() => localStorage.getItem('versola-time-zone'))).toBeNull();
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
    await openDevices(page, { myZoneinfo: 'Asia/Almaty' });
    await expect(page.locator('[data-life="signed-in"] dd')).toHaveText('Nov 4, 14:50');
  });

  test('read in the browser zone without a profile zone', async ({ page }) => {
    await openDevices(page, {});
    await expect(page.locator('[data-life="signed-in"] dd')).toHaveText('Nov 4, 10:50');
  });
});
