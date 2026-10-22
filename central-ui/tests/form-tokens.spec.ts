import { expect, test, type Page } from '@playwright/test';
import { renderForm } from './form-harness';

// The tokens of the theming API change what the real login forms paint: the defaults are the look the forms always had, a
// single value moves both colour schemes, and a { light, dark } pair (written light-dark(a,b)) moves each its own way.
const credential = { step: { type: 'credential', primaryCredentials: ['email'] } };
const otp = { step: { type: 'otp', length: 6, resendAfter: 0, destination: 'u***@example.com' } };

const style = (page: Page, selector: string, property: string) =>
  page.locator(selector).first().evaluate((element, name) => getComputedStyle(element).getPropertyValue(name), property);
const rgb = (r: number, g: number, b: number) => `rgb(${r}, ${g}, ${b})`;

test.describe('defaults are the look the forms had before the tokens', () => {
  test('light: the main button is ink on white, the secondary has the line border, the card is 460px wide', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ colorScheme: 'light' });
    await renderForm(page, 'credential', credential);

    expect(await style(page, '.btn-primary', 'background-color')).toBe(rgb(28, 27, 25));
    expect(await style(page, '.btn-primary', 'color')).toBe(rgb(255, 255, 255));
    expect(await style(page, 'h1', 'font-weight')).toBe('750');
    expect(await style(page, '.container', 'width')).toBe('460px');
    expect(await style(page, '.container', 'padding-left')).toBe('40px');
    expect(await style(page, '.input-field', 'border-top-color')).toBe('rgba(0, 0, 0, 0)');
    await page.locator('.input-field').first().focus();
    // The border is transitioned, so it is read once it has settled.
    await expect.poll(() => style(page, '.input-field', 'border-top-color')).toBe(rgb(28, 27, 25));
  });

  test('dark: the main button is light ink on the dark card', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await renderForm(page, 'credential', credential);

    expect(await style(page, '.btn-primary', 'background-color')).toBe(rgb(243, 242, 240));
    expect(await style(page, '.btn-primary', 'color')).toBe(rgb(28, 27, 25));
  });

  test('the secondary button keeps the line border and ink text', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'light' });
    await renderForm(page, 'otp', otp);

    await expect(page.locator('.btn-secondary').first()).toBeVisible();
    expect(await style(page, '.btn-secondary', 'border-top-color')).toBe(rgb(230, 226, 216));
    expect(await style(page, '.btn-secondary', 'color')).toBe(rgb(28, 27, 25));
  });
});

test.describe('a token override', () => {
  test('a single colour moves the main button in both schemes, and the hover follows it unless set', async ({ page }) => {
    for (const scheme of ['light', 'dark'] as const) {
      await page.emulateMedia({ colorScheme: scheme });
      await renderForm(page, 'credential', credential, { extraCss: ':root{--btn:#d9480f;--on-btn:#ffffff;}' });
      expect(await style(page, '.btn-primary', 'background-color'), scheme).toBe(rgb(217, 72, 15));
      expect(await style(page, '.btn-primary', 'color'), scheme).toBe(rgb(255, 255, 255));
      await page.locator('.btn-primary').first().hover();
      // 12% of the card mixed in: lighter than the fill in the light scheme, and not the default's.
      await expect.poll(() => style(page, '.btn-primary', 'background-color')).not.toBe(rgb(217, 72, 15));
      await page.unroute('**/__form__');
    }
  });

  test('--btn-hover sets the hover fill on its own', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'light' });
    await renderForm(page, 'credential', credential, { extraCss: ':root{--btn:#d9480f;--btn-hover:#7a2300;}' });
    await page.locator('.btn-primary').first().hover();
    await expect.poll(() => style(page, '.btn-primary', 'background-color')).toBe(rgb(122, 35, 0));
  });

  test('a light-dark pair gives each scheme its own colour', async ({ page }) => {
    const css = ':root{--btn:light-dark(#112233,#ddeeff);--on-btn:light-dark(#ffffff,#000000);}';
    await page.emulateMedia({ colorScheme: 'light' });
    await renderForm(page, 'credential', credential, { extraCss: css });
    expect(await style(page, '.btn-primary', 'background-color')).toBe(rgb(17, 34, 51));
    expect(await style(page, '.btn-primary', 'color')).toBe(rgb(255, 255, 255));
    await page.unroute('**/__form__');
    await page.emulateMedia({ colorScheme: 'dark' });
    await renderForm(page, 'credential', credential, { extraCss: css });
    expect(await style(page, '.btn-primary', 'background-color')).toBe(rgb(221, 238, 255));
    expect(await style(page, '.btn-primary', 'color')).toBe(rgb(0, 0, 0));
  });

  test('the card, the title, the fields and the secondary button follow their tokens', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ colorScheme: 'light' });
    await renderForm(page, 'credential', credential, {
      extraCss: ':root{--sheet:#fffbe6;--sheet-line:#c9a227;--card-w:520px;--card-pad:56px;--heading-weight:400;--field:#eef6ff;--field-focus:#0b5fff;--rf:4px;--shadow:none;--link:#0b5fff;}',
    });

    expect(await style(page, '.container', 'background-color')).toBe(rgb(255, 251, 230));
    expect(await style(page, '.container', 'border-top-color')).toBe(rgb(201, 162, 39));
    expect(await style(page, '.container', 'width')).toBe('520px');
    expect(await style(page, '.container', 'padding-left')).toBe('56px');
    expect(await style(page, '.container', 'box-shadow')).toBe('none');
    expect(await style(page, 'h1', 'font-weight')).toBe('400');
    expect(await style(page, '.input-field', 'background-color')).toBe(rgb(238, 246, 255));
    expect(await style(page, '.input-field', 'border-top-left-radius')).toBe('4px');
    await page.locator('.input-field').first().focus();
    await expect.poll(() => style(page, '.input-field', 'border-top-color')).toBe(rgb(11, 95, 255));
  });

  test('the secondary button tokens move its border and text', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'light' });
    await renderForm(page, 'otp', otp, { extraCss: ':root{--btn-secondary-line:#d9480f;--btn-secondary-ink:#7a2300;}' });
    expect(await style(page, '.btn-secondary', 'border-top-color')).toBe(rgb(217, 72, 15));
    expect(await style(page, '.btn-secondary', 'color')).toBe(rgb(122, 35, 0));
  });

  test('on a phone the side padding is the smaller of the token and 22px', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await renderForm(page, 'credential', credential, { extraCss: ':root{--card-pad:56px;}' });
    expect(await style(page, '.container', 'padding-left')).toBe('22px');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  });
});
