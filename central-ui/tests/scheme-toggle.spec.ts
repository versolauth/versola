import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { renderForm, type FormConfig } from './form-harness';

/**
 * The visitor's own Auto / Light / Dark choice on the login forms (forms/shared/scheme-toggle.tsx):
 * one per browser, kept in localStorage, applied to <html> and hidden when the theme fixes the scheme.
 */

const localeNames = { en: 'English', ru: 'Русский' };
const credential: FormConfig = { step: { type: 'credential', primaryCredentials: ['login'], passkey: true }, localeNames };
const otp: FormConfig = { step: { type: 'otp', length: 6, resendAfter: 0, destination: 'u***@example.com' }, localeNames };

// Every form that draws the language switcher also draws the toggle; signed-out has no card controls.
const forms: Array<[string, FormConfig]> = [
  ['credential', credential],
  ['otp', otp],
  ['password', { step: { type: 'password' } }],
  ['set-password', { step: { type: 'set-password' } }],
  ['passkey-enroll', { step: { type: 'passkey-enroll', publicKeyOptions: '{}' } }],
  ['consent', { step: { type: 'consent', clientName: 'Acme', allowPartial: true, denyUri: '/d', scopes: [{ scope: 'openid', description: 'Know who you are', claims: [], deselectable: true }] } }],
  ['confirm-logout', { step: { type: 'confirm-logout', csrfToken: 'x' } }],
  ['conversation-expired', { step: { type: 'conversation-expired', redirectUri: 'https://app.example' } }],
  ['service-unavailable', { step: { type: 'service-unavailable', redirectUri: 'https://app.example' } }],
  ['access-denied', { step: { type: 'access-denied', redirectUri: 'https://app.example' } }],
  ['auth-settings', { step: { type: 'auth-settings', credentials: [], sessions: [] } }],
].map(([id, config]) => [id as string, { localeNames, ...(config as FormConfig) }]);

const toggle = (page: Page) => page.getByRole('button', { name: 'Colour scheme' });
const scheme = (page: Page) => page.evaluate(() => document.documentElement.style.colorScheme);
const stored = (page: Page) => page.evaluate(() => localStorage.getItem('versola.colorScheme'));
const cardColour = (page: Page) => page.evaluate(() => getComputedStyle(document.querySelector('.container')!).backgroundColor);

test.describe('colour scheme toggle', () => {
  for (const [id, config] of forms) {
    test(`${id}: shows an icon button beside the language switcher, at 320px too`, async ({ page }) => {
      await page.setViewportSize({ width: 320, height: 640 });
      await renderForm(page, id, config);

      const box = await toggle(page).boundingBox();
      const locale = await page.getByRole('button', { name: 'English' }).boundingBox();
      expect(box!.width).toBeGreaterThanOrEqual(24);
      expect(box!.height).toBeGreaterThanOrEqual(24);
      // the same row, to the left of the language switcher, not touching it
      expect(Math.abs(box!.y + box!.height / 2 - (locale!.y + locale!.height / 2))).toBeLessThan(2);
      expect(box!.x + box!.width).toBeLessThanOrEqual(locale!.x);
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
    });
  }

  test('leaves the language switcher where it was', async ({ page }) => {
    await renderForm(page, 'credential', credential);
    const withToggle = await page.getByRole('button', { name: 'English' }).boundingBox();
    await toggle(page).evaluate(node => node.parentElement!.remove());
    const without = await page.getByRole('button', { name: 'English' }).boundingBox();

    expect(withToggle!.x + withToggle!.width).toBeCloseTo(without!.x + without!.width, 0);
    expect(withToggle!.y).toBeCloseTo(without!.y, 0);
  });

  test('is shown on a single-language form, without a language switcher', async ({ page }) => {
    await renderForm(page, 'password', { step: { type: 'password' }, locales: ['en'] });

    await expect(toggle(page)).toBeVisible();
    await expect(page.getByRole('button', { name: 'English' })).toHaveCount(0);
  });

  test('offers Auto, Light and Dark, with Auto current, and the label in the chosen language', async ({ page }) => {
    const { all } = await renderForm(page, 'credential', credential);

    await toggle(page).click();
    await expect(page.getByRole('option')).toHaveText(['Auto', 'Light', 'Dark']);
    await expect(page.getByRole('option', { name: 'Auto' })).toHaveAttribute('aria-selected', 'true');
    await page.keyboard.press('Escape');

    await page.getByRole('button', { name: 'English' }).click();
    await page.getByRole('option', { name: 'Русский' }).click();
    await expect(page.getByRole('button', { name: all.ru.scheme_label })).toBeVisible();
    await page.getByRole('button', { name: all.ru.scheme_label }).click();
    await expect(page.getByRole('option')).toHaveText([all.ru.scheme_auto, all.ru.scheme_light, all.ru.scheme_dark]);
  });

  test('Dark is applied at once, stored, and survives a reload and another form; Auto removes it', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'light' });
    await renderForm(page, 'credential', credential);
    const light = await cardColour(page);

    await toggle(page).click();
    await page.getByRole('option', { name: 'Dark' }).click();
    expect(await scheme(page)).toBe('dark');
    expect(await stored(page)).toBe('dark');
    expect(await cardColour(page)).not.toBe(light);

    // a reload: the shell's script applies it before the form is drawn
    await renderForm(page, 'credential', credential);
    expect(await scheme(page)).toBe('dark');
    await toggle(page).click();
    await expect(page.getByRole('option', { name: 'Dark' })).toHaveAttribute('aria-selected', 'true');
    await page.keyboard.press('Escape');

    await renderForm(page, 'otp', otp);
    expect(await scheme(page)).toBe('dark');
    expect(await cardColour(page)).not.toBe(light);

    await toggle(page).click();
    await page.getByRole('option', { name: 'Auto' }).click();
    expect(await stored(page)).toBeNull();
    expect(await scheme(page)).toBe('');
    expect(await cardColour(page)).toBe(light);

    await renderForm(page, 'otp', otp);
    expect(await scheme(page)).toBe('');
  });

  test('Light on a dark device gives the light card', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await renderForm(page, 'credential', credential);
    const dark = await cardColour(page);

    await toggle(page).click();
    await page.getByRole('option', { name: 'Light' }).click();

    expect(await scheme(page)).toBe('light');
    expect(await cardColour(page)).not.toBe(dark);
    expect(await cardColour(page)).toBe('rgb(255, 255, 255)');
  });

  test('is not drawn when the theme fixes the scheme, and the stored choice is not applied', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('versola.colorScheme', 'light'));
    await renderForm(page, 'credential', credential, { extraCss: ':root{color-scheme:dark;--scheme-locked:1}' });

    await expect(toggle(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'English' })).toBeVisible();
    expect(await scheme(page)).toBe('');
  });

  test('is drawn when the theme resets the lock (a client override to auto)', async ({ page }) => {
    await renderForm(page, 'credential', credential, { extraCss: ':root{--scheme-locked:1}\n:root{color-scheme:light dark;--scheme-locked:0}' });

    await expect(toggle(page)).toBeVisible();
  });

  test('is not drawn in a console preview, whose own Light / Dark control wins', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('versola.colorScheme', 'dark'));
    await renderForm(page, 'credential', { ...credential, preview: true, previewId: 'p1' });

    await expect(toggle(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'English' })).toBeVisible();
  });

  test('works by keyboard: Enter opens, arrows move, Enter picks, focus returns; Escape closes', async ({ page }) => {
    await renderForm(page, 'credential', credential);

    await toggle(page).focus();
    await page.keyboard.press('Enter');
    await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('option', { name: 'Auto' })).toBeFocused();
    await page.keyboard.press('End');
    await expect(page.getByRole('option', { name: 'Dark' })).toBeFocused();
    await page.keyboard.press('ArrowUp');
    await expect(page.getByRole('option', { name: 'Light' })).toBeFocused();
    await page.keyboard.press('Enter');

    expect(await scheme(page)).toBe('light');
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(toggle(page)).toBeFocused();

    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(toggle(page)).toBeFocused();
    expect(await scheme(page)).toBe('light');
  });

  test('an outside click closes it, and opening the language list closes it too', async ({ page }) => {
    await renderForm(page, 'credential', credential);

    await toggle(page).click();
    await expect(page.getByRole('listbox')).toBeVisible();
    await page.mouse.click(5, 5);
    await expect(page.getByRole('listbox')).toHaveCount(0);

    await toggle(page).click();
    await page.getByRole('button', { name: 'English' }).click();
    await expect(page.getByRole('listbox')).toHaveCount(1);
    await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false');
  });

  test('has a visible focus ring', async ({ page }) => {
    await renderForm(page, 'credential', credential);
    await toggle(page).focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');

    expect(await toggle(page).evaluate(node => getComputedStyle(node).outlineStyle)).toBe('solid');
  });

  test('still works for the page, with no console errors, when storage is blocked', async ({ page }) => {
    const problems: string[] = [];
    page.on('console', message => { if (message.type() === 'error') problems.push(message.text()); });
    page.on('pageerror', error => problems.push(error.message));
    await page.addInitScript(() => {
      Object.defineProperty(window, 'localStorage', { get() { throw new DOMException('blocked', 'SecurityError'); } });
    });
    await renderForm(page, 'credential', credential);

    await toggle(page).click();
    await page.getByRole('option', { name: 'Dark' }).click();
    expect(await scheme(page)).toBe('dark');
    await toggle(page).click();
    await page.getByRole('option', { name: 'Auto' }).click();
    expect(await scheme(page)).toBe('');
    expect(problems).toEqual([]);
  });

  test('still works when storage refuses writes', async ({ page }) => {
    const problems: string[] = [];
    page.on('pageerror', error => problems.push(error.message));
    await page.addInitScript(() => {
      Storage.prototype.setItem = () => { throw new DOMException('quota', 'QuotaExceededError'); };
    });
    await renderForm(page, 'credential', credential);

    await toggle(page).click();
    await page.getByRole('option', { name: 'Light' }).click();
    expect(await scheme(page)).toBe('light');
    expect(problems).toEqual([]);
  });

  for (const colourScheme of ['light', 'dark'] as const) {
    test(`has no axe violations in the ${colourScheme} scheme, closed or with the list open`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: colourScheme, reducedMotion: 'reduce' });
      await renderForm(page, 'credential', credential);
      const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();

      expect((await scan()).violations.map(v => `${v.id}: ${v.help}`)).toEqual([]);
      await toggle(page).press('ArrowDown');
      await expect(page.getByRole('listbox')).toBeVisible();
      expect((await scan()).violations.map(v => `${v.id}: ${v.help}`)).toEqual([]);
    });
  }

  test('has no axe violations when the visitor chose the opposite scheme to the device', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
    await renderForm(page, 'credential', credential);
    await toggle(page).click();
    await page.getByRole('option', { name: 'Dark' }).click();
    await toggle(page).press('ArrowDown');
    await expect(page.getByRole('listbox')).toBeVisible();
    // axe reads the colours as painted: let the colour transitions of the switch finish first
    await page.evaluate(() => Promise.all(document.getAnimations().map(animation => animation.finished)));

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect(results.violations.map(v => `${v.id}: ${v.help}`)).toEqual([]);
  });

  test('reflows at 320px with the list open', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 640 });
    await renderForm(page, 'credential', credential);
    await toggle(page).click();

    await expect(page.getByRole('listbox')).toBeVisible();
    const box = await page.getByRole('listbox').boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
  });
});
