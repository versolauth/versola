import { expect, test, type Page } from '@playwright/test';
import { loadAdminApp } from './fixtures';
import { dana, danaId, quiet, shopManager, shopSupport, shopUser, userClients, userScopes } from './users-fixtures';

const tenant = 'tenant-alpha';
const base = `/?view=users&tenant=${tenant}&id=${danaId}`;
const state = {
  users: [dana, quiet],
  clients: { [tenant]: userClients },
  roles: { [tenant]: [shopManager, shopUser, shopSupport] },
  scopes: { [tenant]: userScopes },
};

const tabs = (page: Page) => page.getByRole('tablist', { name: 'User sections' }).getByRole('tab');
const tab = (page: Page, name: string) => page.getByRole('tab', { name, exact: true });
const card = (page: Page, name: string) => page.getByRole('region', { name, exact: true });

async function openRecord(page: Page, path = base) {
  const api = await loadAdminApp(page, { path, state });
  await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
  return api;
}

test.describe('the tabs of the user record', () => {
  test('are Profile, Roles and Devices, with Profile selected and no counters', async ({ page }) => {
    await openRecord(page);
    await expect(tabs(page)).toHaveText(['Profile', 'Roles', 'Devices']);
    await expect(tab(page, 'Profile')).toHaveAttribute('aria-selected', 'true');
    await expect(tab(page, 'Roles')).toHaveAttribute('aria-selected', 'false');
    await expect(tab(page, 'Devices')).toHaveAttribute('aria-selected', 'false');
    await expect(page.getByRole('tabpanel')).toHaveAccessibleName('Profile');
    expect(page.url()).not.toContain('tab=');
  });

  test('select with a click, keep the choice in the URL, and drop it again for Profile', async ({ page }) => {
    await openRecord(page);
    await tab(page, 'Devices').click();
    await expect(tab(page, 'Devices')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('tabpanel')).toHaveAccessibleName('Devices');
    await expect(card(page, 'Signed-in devices')).toBeVisible();
    await expect(card(page, 'Identifiers')).toHaveCount(0);
    expect(page.url()).toContain('tab=devices');

    await tab(page, 'Roles').click();
    await expect(card(page, `Roles in ${tenant}`)).toBeVisible();
    expect(page.url()).toContain('tab=roles');
    expect(page.url()).not.toContain('tab=devices');

    await tab(page, 'Profile').click();
    await expect(card(page, 'Identifiers')).toBeVisible();
    expect(page.url()).not.toContain('tab=');
  });

  test('open on the tab a link names, and fall back to Profile for one that does not exist', async ({ page }) => {
    await openRecord(page, `${base}&tab=devices`);
    await expect(tab(page, 'Devices')).toHaveAttribute('aria-selected', 'true');
    await expect(card(page, 'Signed-in devices')).toBeVisible();

    await page.goto(`${base}&tab=nonsense`);
    await expect(tab(page, 'Profile')).toHaveAttribute('aria-selected', 'true');
    await expect(card(page, 'Identifiers')).toBeVisible();
  });

  test('keep every existing link to a user working: the bare record link and an editor link', async ({ page }) => {
    await openRecord(page);
    await expect(card(page, 'Identifiers')).toBeVisible();
    await page.goto(`${base}&edit=claims`);
    await expect(page.getByRole('heading', { level: 1, name: 'Edit claims' })).toBeVisible();
    await expect(page.getByRole('tablist')).toHaveCount(0);
  });

  test('stay on the tab an editor was opened from when it closes', async ({ page }) => {
    await openRecord(page, `${base}&tab=roles`);
    await page.getByRole('button', { name: 'Edit roles' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Edit roles' })).toBeVisible();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(tab(page, 'Roles')).toHaveAttribute('aria-selected', 'true');
    await expect(card(page, `Roles in ${tenant}`)).toBeVisible();
  });

  test('do not carry over to another user', async ({ page }) => {
    await openRecord(page, `${base}&tab=devices`);
    await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Users' }).click();
    await page.getByRole('searchbox', { name: 'Search users' }).fill(quiet.login!);
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await page.getByRole('row').filter({ hasText: quiet.login! }).getByRole('button').first().click();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(tab(page, 'Profile')).toHaveAttribute('aria-selected', 'true');
    expect(page.url()).not.toContain('tab=');
  });

  test('send no request when the tab changes: what a tab shows was loaded with the page', async ({ page }) => {
    const api = await openRecord(page);
    await expect(card(page, 'Identifiers')).toBeVisible();
    // The page's own parts load once (devices, offline access, passkeys, roles); give them time to settle.
    await tab(page, 'Devices').click();
    await expect(card(page, 'Passkeys')).toContainText('iCloud Keychain');
    const before = api.requests.length;
    await tab(page, 'Roles').click();
    await tab(page, 'Profile').click();
    await tab(page, 'Devices').click();
    await expect(card(page, 'Signed-in devices')).toBeVisible();
    expect(api.requests.length).toBe(before);
  });
});

test.describe('the keyboard on the tabs', () => {
  test('move focus with the arrows, Home and End, wrapping, without selecting', async ({ page }) => {
    await openRecord(page);
    await tab(page, 'Profile').focus();
    await page.keyboard.press('ArrowRight');
    await expect(tab(page, 'Roles')).toBeFocused();
    await page.keyboard.press('ArrowRight');
    await expect(tab(page, 'Devices')).toBeFocused();
    await page.keyboard.press('ArrowRight');
    await expect(tab(page, 'Profile')).toBeFocused();
    await page.keyboard.press('ArrowLeft');
    await expect(tab(page, 'Devices')).toBeFocused();
    await page.keyboard.press('Home');
    await expect(tab(page, 'Profile')).toBeFocused();
    await page.keyboard.press('End');
    await expect(tab(page, 'Devices')).toBeFocused();
    // Moving focus along the bar selected nothing and changed no URL.
    await expect(tab(page, 'Profile')).toHaveAttribute('aria-selected', 'true');
    expect(page.url()).not.toContain('tab=');
  });

  test('select the focused tab with Enter or Space', async ({ page }) => {
    await openRecord(page);
    await tab(page, 'Profile').focus();
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    await expect(tab(page, 'Roles')).toHaveAttribute('aria-selected', 'true');
    await expect(card(page, `Roles in ${tenant}`)).toBeVisible();
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Space');
    await expect(tab(page, 'Devices')).toHaveAttribute('aria-selected', 'true');
    await expect(card(page, 'Signed-in devices')).toBeVisible();
  });

  test('keep one tab in the tab order (roving tabindex) and leave the bar to the panel with Tab', async ({ page }) => {
    await openRecord(page, `${base}&tab=roles`);
    await expect(tab(page, 'Roles')).toHaveAttribute('tabindex', '0');
    await expect(tab(page, 'Profile')).toHaveAttribute('tabindex', '-1');
    await expect(tab(page, 'Devices')).toHaveAttribute('tabindex', '-1');

    await tab(page, 'Roles').focus();
    await page.keyboard.press('ArrowRight');
    await expect(tab(page, 'Devices')).toHaveAttribute('tabindex', '0');
    await expect(tab(page, 'Roles')).toHaveAttribute('tabindex', '-1');

    // Leaving the bar puts the selected tab back in the tab order.
    await page.keyboard.press('Tab');
    await expect(tab(page, 'Roles')).toHaveAttribute('tabindex', '0');
    await expect(tab(page, 'Devices')).toHaveAttribute('tabindex', '-1');
    await expect(page.getByRole('tabpanel')).toBeFocused();
  });

  test('tie the panel to its tab', async ({ page }) => {
    await openRecord(page, `${base}&tab=devices`);
    await expect(tab(page, 'Devices')).toHaveAttribute('aria-controls', 'panel');
    await expect(page.getByRole('tabpanel')).toHaveAttribute('id', 'panel');
    await expect(page.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'tab-devices');
  });
});

test.describe('the tab bar on a phone', () => {
  test('scrolls inside itself, never the page, and keeps a 44px target', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 800 });
    await openRecord(page);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    for (const name of ['Profile', 'Roles', 'Devices']) {
      const box = await tab(page, name).boundingBox();
      expect(box!.height).toBeGreaterThanOrEqual(44);
    }
  });
});
