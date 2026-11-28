import { expect, test, type Page } from '@playwright/test';
import { loadAdminApp } from './fixtures';
import { dana, danaId, quiet } from './users-fixtures';
import { ACTIVITY_NOW } from './user-activity-fixtures';

/**
 * The times on a user's page are read in the browser's own time zone, written the same way in every browser, and carry the offset
 * they were read under, so `14:50` is never a guess: `Nov 4, 14:50 (UTC+5)`, `Nov 4, 04:50 (UTC-5)`, `Nov 4, 09:50 UTC`.
 */
test.use({ locale: 'en-US', viewport: { width: 1440, height: 1000 } });

const session = (createdAt: string) => ({
  ...dana.sessions[0],
  createdAt,
  expiresAt: '2026-11-11T09:50:00Z',
  idleExpiresAt: '2026-11-04T10:50:00Z',
});

async function openDevices(page: Page, createdAt = '2026-11-04T09:50:00Z') {
  // The clock is pinned to the instant the specs of the Activity screen use, so "this year" is 2026 on every run.
  await page.clock.install({ time: new Date(ACTIVITY_NOW) });
  await loadAdminApp(page, {
    path: `/?view=users&tenant=tenant-alpha&id=${danaId}&tab=devices`,
    state: { users: [{ ...dana, sessions: [session(createdAt)] }, quiet] },
  });
  await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
}

const signedIn = (page: Page) => page.locator('[data-life="signed-in"] dd');
const idle = (page: Page) => page.locator('[data-life="idle"] dd');

// 09:50 UTC on Nov 4: east of Greenwich with whole and half hours, at Greenwich, and west of it.
for (const [zone, signedInText, idleText] of [
  ['Asia/Almaty', 'Nov 4, 14:50 (UTC+5)', 'Nov 4, 15:50 (UTC+5)'],
  ['Asia/Kolkata', 'Nov 4, 15:20 (UTC+5:30)', 'Nov 4, 16:20 (UTC+5:30)'],
  ['Europe/Berlin', 'Nov 4, 10:50 (UTC+1)', 'Nov 4, 11:50 (UTC+1)'],
  ['UTC', 'Nov 4, 09:50 UTC', 'Nov 4, 10:50 UTC'],
  ['America/New_York', 'Nov 4, 04:50 (UTC-5)', 'Nov 4, 05:50 (UTC-5)'],
  ['America/St_Johns', 'Nov 4, 06:20 (UTC-3:30)', 'Nov 4, 07:20 (UTC-3:30)'],
] as const) {
  test.describe(`a browser in ${zone}`, () => {
    test.use({ timezoneId: zone });

    test('reads the times of a device in its own zone and says the offset', async ({ page }) => {
      await openDevices(page);
      await expect(signedIn(page)).toHaveText(signedInText);
      await expect(idle(page)).toHaveText(idleText);
    });
  });
}

test.describe('a browser in Berlin', () => {
  test.use({ timezoneId: 'Europe/Berlin' });

  test('gives each time the offset in force at that time, not today\'s', async ({ page }) => {
    // Summer time on the day, winter time now.
    await openDevices(page, '2026-07-04T09:50:00Z');
    await expect(signedIn(page)).toHaveText('Jul 4, 11:50 (UTC+2)');
  });

  test('shows the year of a time that is not in this year', async ({ page }) => {
    await openDevices(page, '2025-06-15T09:50:00Z');
    await expect(signedIn(page)).toHaveText('Jun 15, 2025, 11:50 (UTC+2)');
  });

  test('puts a time near midnight on the day it falls on in the browser zone', async ({ page }) => {
    await openDevices(page, '2026-11-03T23:30:00Z');
    await expect(signedIn(page)).toHaveText('Nov 4, 00:30 (UTC+1)');
  });
});
