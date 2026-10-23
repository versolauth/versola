import { expect, test, type Page } from '@playwright/test';
import { loadAdminApp } from './fixtures';

/**
 * The row menu of `versola-data-table` (seen through the Clients list): the WAI-ARIA menu button
 * pattern, driven from the keyboard, and the card mode below 720px that keeps the table's
 * semantics.
 */
const clientsPath = '/?view=clients&tenant=tenant-alpha';
const trigger = (page: Page) => page.getByRole('button', { name: 'Actions for alpha-web' });
const menu = (page: Page) => page.getByRole('menu', { name: 'Actions for alpha-web' });
const item = (page: Page, name: string | RegExp) => page.getByRole('menuitem', { name });

test('opens with the first item focused and moves with the arrows, Home and End, wrapping', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath });

  await trigger(page).focus();
  await expect(trigger(page)).toHaveAttribute('aria-expanded', 'false');
  await page.keyboard.press('Enter');
  await expect(menu(page)).toBeVisible();
  await expect(trigger(page)).toHaveAttribute('aria-expanded', 'true');
  await expect(item(page, 'Open')).toBeFocused();

  await page.keyboard.press('ArrowDown');
  await expect(item(page, 'Copy client ID')).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(item(page, 'Delete client alpha-web')).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(item(page, 'Open')).toBeFocused();
  await page.keyboard.press('ArrowUp');
  await expect(item(page, 'Delete client alpha-web')).toBeFocused();
  await page.keyboard.press('Home');
  await expect(item(page, 'Open')).toBeFocused();
  await page.keyboard.press('End');
  await expect(item(page, 'Delete client alpha-web')).toBeFocused();
});

test('Escape closes the menu and returns focus to the trigger; ArrowUp opens on the last item', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath });

  await trigger(page).focus();
  await page.keyboard.press('ArrowDown');
  await expect(item(page, 'Open')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(menu(page)).toHaveCount(0);
  await expect(trigger(page)).toBeFocused();
  await expect(trigger(page)).toHaveAttribute('aria-expanded', 'false');

  await page.keyboard.press('ArrowUp');
  await expect(item(page, 'Delete client alpha-web')).toBeFocused();
});

test('Tab and a click outside close the menu without activating anything', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath });

  await trigger(page).click();
  await expect(item(page, 'Open')).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(menu(page)).toHaveCount(0);

  await trigger(page).click();
  await expect(menu(page)).toBeVisible();
  await page.getByRole('heading', { name: 'OAuth Clients', exact: true }).click();
  await expect(menu(page)).toHaveCount(0);
  await expect(page.locator('versola-clients-list .view-title')).toHaveCount(0);
});

test('Enter on an item activates it and closes the menu', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath });

  await trigger(page).focus();
  await page.keyboard.press('Enter');
  await expect(item(page, 'Open')).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(menu(page)).toHaveCount(0);
  await expect(page.locator('versola-clients-list .view-title')).toBeVisible();
});

test('below 720px the rows are cards that keep the table semantics and show the column labels', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 900 });
  await loadAdminApp(page, { path: clientsPath });

  const table = page.getByRole('table');
  await expect(table).toBeVisible();
  await expect(table.getByRole('columnheader', { name: 'Authenticates with' })).toHaveCount(1);
  const row = table.getByRole('row').filter({ has: page.getByRole('button', { name: /^Open client (.* )?alpha-web$/ }) });
  await expect(row.getByRole('cell')).toHaveCount(5);
  const label = await row.getByRole('cell').nth(2).evaluate(cell => getComputedStyle(cell, '::before').content);
  expect(label).toBe('"Authenticates with"');
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
});
