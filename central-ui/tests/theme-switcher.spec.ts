import { expect, test, type Page } from '@playwright/test';
import { loadAdminApp } from './fixtures';

/**
 * The Auto / Light / Dark switcher in the sidebar footer: what it paints, what it remembers,
 * and that it can be driven from the keyboard.
 */
const root = (page: Page) => page.locator('html');
const trigger = (page: Page, current: string) => page.getByRole('button', { name: `Colour scheme: ${current}` });

test('opens from the keyboard, moves between options and picks with Enter', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await loadAdminApp(page);

  await trigger(page, 'Auto').focus();
  await page.keyboard.press('ArrowDown');
  const listbox = page.getByRole('listbox', { name: 'Colour scheme: Auto' });
  await expect(listbox).toBeVisible();
  await expect(page.getByRole('option', { name: 'Auto' })).toBeFocused();
  await expect(page.getByRole('option', { name: 'Auto' })).toHaveAttribute('aria-selected', 'true');

  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('option', { name: 'Dark' })).toBeFocused();
  await page.keyboard.press('Enter');

  await expect(root(page)).toHaveAttribute('data-theme', 'dark');
  await expect(root(page)).toHaveAttribute('data-theme-pref', 'dark');
  await expect(listbox).toHaveCount(0);
  await expect(trigger(page, 'Dark')).toBeFocused();
});

test('closes on Escape and on a click outside without changing the choice', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await loadAdminApp(page);

  await trigger(page, 'Auto').click();
  await expect(page.getByRole('listbox')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await expect(trigger(page, 'Auto')).toBeFocused();

  await trigger(page, 'Auto').click();
  await expect(page.getByRole('listbox')).toBeVisible();
  await page.getByRole('heading', { name: 'OAuth Clients', exact: true }).click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await expect(root(page)).toHaveAttribute('data-theme-pref', 'auto');
});

test('remembers the choice across a reload', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await loadAdminApp(page);
  await expect(root(page)).toHaveAttribute('data-theme', 'dark');

  await trigger(page, 'Auto').click();
  await page.getByRole('option', { name: 'Light' }).click();
  await expect(root(page)).toHaveAttribute('data-theme', 'light');
  expect(await page.evaluate(() => localStorage.getItem('versola-theme'))).toBe('light');

  await page.reload();
  await expect(root(page)).toHaveAttribute('data-theme', 'light');
  await expect(root(page)).toHaveAttribute('data-theme-pref', 'light');
  await expect(trigger(page, 'Light')).toBeVisible();
});

test('follows the operating system while the choice is Auto, and only then', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await loadAdminApp(page);
  await expect(root(page)).toHaveAttribute('data-theme', 'light');

  await page.emulateMedia({ colorScheme: 'dark' });
  await expect(root(page)).toHaveAttribute('data-theme', 'dark');
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href', 'favicon-dark.svg');

  await page.emulateMedia({ colorScheme: 'light' });
  await expect(root(page)).toHaveAttribute('data-theme', 'light');

  await trigger(page, 'Auto').click();
  await page.getByRole('option', { name: 'Dark' }).click();
  await page.emulateMedia({ colorScheme: 'light' });
  await expect(root(page)).toHaveAttribute('data-theme', 'dark');
});

test('follows a choice made in another tab', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await loadAdminApp(page);
  await expect(trigger(page, 'Auto')).toBeVisible();

  // What the browser fires in this tab when another tab writes the key.
  await page.evaluate(() => {
    localStorage.setItem('versola-theme', 'dark');
    window.dispatchEvent(new StorageEvent('storage', { key: 'versola-theme', newValue: 'dark', storageArea: localStorage }));
  });
  await expect(root(page)).toHaveAttribute('data-theme', 'dark');
  await expect(root(page)).toHaveAttribute('data-theme-pref', 'dark');
  await expect(trigger(page, 'Dark')).toBeVisible();

  await page.evaluate(() => {
    localStorage.setItem('versola-theme', 'auto');
    window.dispatchEvent(new StorageEvent('storage', { key: 'versola-theme', newValue: 'auto', storageArea: localStorage }));
  });
  await expect(root(page)).toHaveAttribute('data-theme', 'light');
  await expect(trigger(page, 'Auto')).toBeVisible();
});

test('a light or dark value stored before Auto existed still applies', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => localStorage.setItem('versola-theme', 'light'));
  await loadAdminApp(page);

  await expect(root(page)).toHaveAttribute('data-theme', 'light');
  await expect(root(page)).toHaveAttribute('data-theme-pref', 'light');
  await expect(trigger(page, 'Light')).toBeVisible();
});
