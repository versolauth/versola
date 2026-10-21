import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';
import { ftClients, ftPath, ftState } from './forms-themes-fixtures';
import { baseStylesheetMock, setupConfigApiMocks, type MockConfigState } from './mocks';

const listPath = ftPath('themes');
const recordPath = (id: string) => ftPath('themes', `&id=${id}`);
const newPath = ftPath('themes', '&new=1');
const readOnly = { resources: { central: { permissions: ['forms:read'] } }, isProd: false };

const credential = {
  id: 'credential',
  version: 1,
  active: true,
  style: '.container { color: red; }',
  jsSource: 'export {};',
  jsCompiled: 'document.getElementById("versola-form-root").textContent = "preview";',
  localizations: { en: { title: 'Sign in' } },
  properties: [],
};

const state: Partial<MockConfigState> = { ...ftState(), forms: [credential], locales: [{ code: 'en', name: 'English', active: true, isDefault: true }] };

const themeRow = (page: Page, id: string) => page.getByRole('row').filter({ has: page.getByRole('button', { name: `Open theme ${id}` }) });
const srcdoc = (page: Page) => page.locator('iframe.preview-iframe').evaluate((frame: HTMLIFrameElement) => frame.srcdoc);
const bar = (page: Page) => page.locator('.bar-text');
const save = (page: Page, label = 'Save theme') => page.getByRole('button', { name: label, exact: true });
const crumb = (page: Page, name: string) => page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name });
const group = (page: Page, name: string) => page.getByRole('group', { name, exact: true });
const card = (page: Page, name: string) => page.getByRole('region', { name, exact: true });
const noSideScroll = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);

async function newTheme(page: Page, overrides: Partial<MockConfigState> = {}) {
  const api = await loadAdminApp(page, { path: newPath, state: { ...state, ...overrides } });
  await expect(page.getByRole('heading', { level: 1, name: 'Create theme' })).toBeVisible();
  return api;
}

const created = async (page: Page, api: Awaited<ReturnType<typeof loadAdminApp>>) => {
  await save(page, 'Create theme').click();
  return (await findRequest(api.requests, 'POST', '/configuration/themes')).body as Record<string, unknown>;
};

test.describe('list', () => {
  test('is a table: theme, base, look, used by; one primary action and filters without counts', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state });

    await expect(page.getByRole('heading', { level: 1, name: 'Themes' })).toBeVisible();
    await expect(page.getByRole('columnheader')).toHaveText(['Theme', 'Base', 'Look', 'Used by', 'Actions']);
    await expect(page.getByRole('button', { name: '+ Create theme' })).toHaveCount(1);
    await expect(group(page, 'Filter themes').getByRole('button')).toHaveText(['All', 'This tenant']);
    // One column that says where a theme lives, not two stacked groups.
    await expect(themeRow(page, 'default')).toContainText('Global');
    await expect(themeRow(page, 'shop-brand')).toContainText('This tenant');
    await expect(page.locator('versola-themes-list .group-title')).toHaveCount(0);
  });

  test('names the overridden tokens, never counts them; a stored stylesheet is listed as legacy and read-only', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state });

    await expect(themeRow(page, 'default')).toContainText('Versola');
    await expect(themeRow(page, 'default')).toContainText('No overrides');
    await expect(themeRow(page, 'shop-brand')).toContainText('Overrides: brand, page +1');
    await expect(themeRow(page, 'partner-green')).toContainText('Overrides: brand');
    await expect(themeRow(page, 'legacy-2024')).toContainText('Stylesheet (legacy)');
    await expect(themeRow(page, 'legacy-2024')).toContainText('Read-only');
  });

  test('shows the look as a thumbnail and the brand colour, its own override or its base\'s', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state });

    await expect(themeRow(page, 'shop-brand').locator('code')).toHaveText('#e5484d');
    await expect(themeRow(page, 'default').locator('code')).toHaveText('#155e75');
    await expect(themeRow(page, 'ledger').locator('code')).toHaveText('#5b5bd6');
    await expect(themeRow(page, 'shop-brand').locator('versola-theme-thumb')).toHaveAttribute('brand', '#e5484d');
    await expect(themeRow(page, 'legacy-2024')).toContainText('Set by its CSS');
    await expect(themeRow(page, 'legacy-2024').locator('versola-theme-thumb')).toHaveCount(0);
  });

  test('says which clients use a theme, computed from the clients the screen loads', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state });

    await expect(themeRow(page, 'shop-brand').locator('.tag')).toHaveText(['shop-mobile', 'storefront-web']);
    await expect(themeRow(page, 'partner-green').locator('.tag')).toHaveText(['partner-portal']);
    await expect(themeRow(page, 'ledger')).toContainText('No clients');
    await expect(themeRow(page, 'legacy-2024')).toContainText('No clients');
  });

  test('two clients and +N that names the rest', async ({ page }) => {
    const clients = [...ftClients, { ...ftClients[0], id: 'kiosk', theme: 'shop-brand' }];
    await loadAdminApp(page, { path: listPath, state: { ...state, clients: { 'tenant-alpha': clients } as MockConfigState['clients'] } });

    await expect(themeRow(page, 'shop-brand').locator('.chip.more')).toHaveText('+1');
    await expect(themeRow(page, 'shop-brand').locator('.chip.more')).toHaveAttribute('title', 'storefront-web');
  });

  test('a client that names no theme counts as using default', async ({ page }) => {
    const bare = { ...ftClients[0], id: 'bare', theme: undefined };
    await loadAdminApp(page, { path: listPath, state: { ...state, clients: { 'tenant-alpha': [bare] } as MockConfigState['clients'] } });

    await expect(themeRow(page, 'default').locator('.tag')).toHaveText(['bare']);
  });

  test('filters by scope, never by kind, and searches by ID', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state });
    const filter = group(page, 'Filter themes');
    const rows = page.getByRole('row').filter({ has: page.getByRole('button', { name: /^Open theme/ }) });
    await expect(rows).toHaveCount(5);

    await expect(filter.getByRole('button', { name: /Visual|Stylesheet/ })).toHaveCount(0);
    await filter.getByRole('button', { name: 'This tenant' }).click();
    await expect(rows).toHaveCount(3);
    await filter.getByRole('button', { name: 'All' }).click();
    await page.getByRole('searchbox', { name: 'Search themes' }).fill('green');
    await expect(rows).toHaveCount(1);
    await page.getByRole('searchbox', { name: 'Search themes' }).fill('zzz');
    await expect(page.getByRole('heading', { name: 'No themes match' })).toBeVisible();
  });

  test('a row opens the theme; the row menu opens it and copies the ID', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state });
    await themeRow(page, 'ledger').click();
    await expect(page.getByRole('heading', { level: 1, name: 'ledger' })).toBeVisible();
    await expect(page).toHaveURL(/id=ledger/);

    await crumb(page, 'Themes').click();
    await page.getByRole('button', { name: 'Actions for partner-green' }).click();
    await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy ID', /^Delete theme/]);
    await page.getByRole('menuitem', { name: 'Open' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'partner-green' })).toBeVisible();
  });

  test('the row menu\'s Delete is disabled, with the reason, for a theme clients use and for default', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state });

    await page.getByRole('button', { name: 'Actions for shop-brand' }).click();
    const used = page.getByRole('menuitem', { name: 'Delete theme shop-brand' });
    await expect(used).toHaveAttribute('aria-disabled', 'true');
    await expect(used).toHaveAttribute('title', 'Used by shop-mobile and storefront-web');
    await page.keyboard.press('Escape');

    await page.getByRole('button', { name: 'Actions for default' }).click();
    await expect(page.getByRole('menuitem', { name: 'Delete theme default' })).toHaveAttribute('title', 'The default theme cannot be deleted');
  });

  test('is a stack of cards at 375 and never scrolls sideways', async ({ page }) => {
    for (const width of [1440, 1024, 375]) {
      await page.setViewportSize({ width, height: 900 });
      await loadAdminApp(page, { path: listPath, state });
      await expect(themeRow(page, 'shop-brand')).toBeVisible();
      expect(await noSideScroll(page)).toBe(true);
    }
  });

  test('says so when there are no themes, offering to create the first', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: { ...state, themes: [] } });

    await expect(page.getByRole('heading', { name: 'No themes yet' })).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Create theme' })).toHaveCount(2);
  });

  test('says so, with Retry, when the built-in themes cannot be loaded', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: { ...state, themePresets: null } });

    await expect(page.getByText('Could not load themes')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
  });

  test('a theme that does not exist says so', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('nope'), state });

    await expect(page.getByRole('heading', { name: 'Theme not found' })).toBeVisible();
    await page.getByRole('button', { name: 'Back to themes' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Themes' })).toBeVisible();
  });

  test('without the manage permission it lists and opens themes but offers no way to change them', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: { ...state, myPermissions: readOnly } });

    await expect(themeRow(page, 'shop-brand')).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Create theme' })).toHaveCount(0);
    await themeRow(page, 'shop-brand').click();
    await expect(page.getByRole('button', { name: 'Save theme' })).toHaveCount(0);
    await expect(page.locator('versola-danger-card')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Clear overrides' })).toHaveCount(0);
  });

  test('Themes is a page of its own in the side panel, after Forms', async ({ page }) => {
    await loadAdminApp(page, { path: ftPath('forms'), state });

    const nav = page.locator('versola-navigation');
    await nav.getByText('Themes', { exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Themes' })).toBeVisible();
    await expect(themeRow(page, 'legacy-2024')).toBeVisible();
  });
});

test.describe('record', () => {
  test('has a header with the ID, kind, scope and the clients that use it', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });

    await expect(crumb(page, 'Themes')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'shop-brand' })).toBeVisible();
    const header = page.locator('versola-record-header');
    await expect(header).toContainText('Visual');
    await expect(header).toContainText('This tenant');
    await expect(header).toContainText('Used by');
    await expect(header.locator('.tag')).toHaveText(['shop-mobile', 'storefront-web']);
    await expect(page.getByRole('button', { name: 'Copy theme ID' })).toBeVisible();
    // The page's save is the bar's, not a header button.
    await expect(save(page)).toHaveCount(1);
  });

  test('a theme no client uses says No clients in its header', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('ledger'), state });

    await expect(page.locator('versola-record-header')).toContainText('No clients');
  });

  test('is cards: Base theme, Look and Delete last, with no Extra CSS section', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });

    await expect(page.locator('versola-theme-record section.card, versola-theme-record versola-danger-card section.card').evaluateAll(
      (cards: Element[]) => cards.map(card => card.getAttribute('aria-label')))).resolves.toEqual(['Base theme', 'Look', 'Delete theme']);
    await expect(page.locator('details.advanced')).toHaveCount(0);
    await expect(page.getByText('Extra CSS (optional)')).toHaveCount(0);
    await expect(page.locator('versola-code-editor')).toHaveCount(0);
  });

  test('opens a stored theme on its base with its overrides, named and not counted', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });

    await expect(page.getByRole('button', { name: 'Versola' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByLabel('Brand colour, #rrggbb')).toHaveValue('#e5484d');
    await expect(card(page, 'Look')).toContainText('Overrides on Versola: brand colour, page background, background style.');
    await expect(page.getByRole('group', { name: 'Built as' })).toHaveCount(0);
    await expect(bar(page)).toHaveText('No unsaved changes');
    await expect(save(page)).toBeDisabled();
  });

  test('each overridden token has an accent dot, the base value in its title and a Reset', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });

    await expect(page.getByTitle('Overridden · base: #155e75')).toBeVisible();
    await expect(page.getByTitle('Overridden · base: bold')).toBeVisible();
    await expect(page.getByTitle('Overridden · base: glow')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Reset shape to the base' })).toHaveCount(0);
    await expect(page.getByRole('img', { name: 'Overridden' })).toHaveCount(3);
    // No wordy badge in the row any more.
    await expect(page.getByText(/Overridden ·/)).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Reset (brand|surface|background)/ })).toHaveCount(3);
  });

  test('the background thumbnails are drawn in the scheme the preview shows', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ colorScheme: 'light' });
    await loadAdminApp(page, { path: recordPath('default'), state });
    const swatches = page.locator('.bg-swatch');

    await expect(swatches.first()).toHaveAttribute('data-scheme', 'light');
    await page.getByRole('group', { name: 'Preview scheme' }).getByRole('button', { name: 'Dark' }).click();
    await expect(swatches.first()).toHaveAttribute('data-scheme', 'dark');
    await page.getByRole('group', { name: 'Preview scheme' }).getByRole('button', { name: 'Light' }).click();
    await expect(swatches.first()).toHaveAttribute('data-scheme', 'light');
  });

  test('is two columns at 1440 with a 560px preview, 380px at 1024 and a band above below 900px', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });
    const pane = page.locator('versola-preview-pane');
    const controls = page.locator('.controls');
    expect(Math.round((await pane.boundingBox())!.width)).toBe(560);
    expect((await pane.boundingBox())!.x).toBeGreaterThan((await controls.boundingBox())!.x);

    await page.setViewportSize({ width: 1024, height: 900 });
    expect(Math.round((await pane.boundingBox())!.width)).toBe(380);
    // The width choice is dropped: it is the phone only.
    await expect(page.getByRole('group', { name: 'Preview width' })).toBeHidden();
    expect(await noSideScroll(page)).toBe(true);

    await page.setViewportSize({ width: 600, height: 900 });
    expect((await pane.boundingBox())!.y).toBeLessThan((await controls.boundingBox())!.y);
    // Collapsed behind a toggle until asked for; the choice stays for the session.
    const toggle = pane.getByRole('button', { name: 'Show preview' });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(page.locator('iframe.preview-iframe')).toBeHidden();
    await toggle.click();
    await expect(pane.getByRole('button', { name: 'Hide preview' })).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('iframe.preview-iframe')).toBeVisible();
    await page.reload();
    await expect(pane.getByRole('button', { name: 'Hide preview' })).toHaveAttribute('aria-expanded', 'true');
    expect(await noSideScroll(page)).toBe(true);
  });

  test('the preview stays on screen while the controls scroll, and the bar sticks to the bottom', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 700 });
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });
    await page.evaluate(() => window.scrollTo(0, 600));
    await page.waitForTimeout(200);

    const pane = (await page.locator('versola-preview-pane').boundingBox())!;
    expect(pane.y).toBeGreaterThanOrEqual(0);
    expect(pane.y).toBeLessThan(100);
    const bottom = (await page.locator('.bar').boundingBox())!;
    expect(bottom.y + bottom.height).toBeLessThanOrEqual(700);
  });

  test('no counter anywhere: nothing says how many overrides there are', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });

    await expect(page.locator('versola-theme-record')).not.toContainText(/\d+ overrides?/);
  });
});

test.describe('editing', () => {
  test('switching the base keeps the overrides, and Clear overrides removes them at once', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await page.getByRole('button', { name: 'Sharp' }).click();
    await group(page, 'Colour scheme').getByRole('button', { name: 'Dark' }).click();
    await expect(card(page, 'Look')).toContainText('Overrides on Versola: shape, colour scheme.');

    await page.getByRole('button', { name: 'Fintech' }).click();
    await expect(card(page, 'Look')).toContainText('Overrides on Fintech: shape, colour scheme.');

    await page.getByRole('button', { name: 'Clear overrides' }).click();
    await expect(card(page, 'Look')).toContainText('No overrides: this theme is the base theme.');
    await expect(page.getByRole('button', { name: 'Clear overrides' })).toHaveCount(0);
  });

  test('an override is shown against the base value, can be reset, and one equal to the base is not an override', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });

    await page.getByRole('button', { name: '#5b5bd6' }).click();
    await expect(page.getByTitle('Overridden · base: #155e75')).toBeVisible();
    await expect(card(page, 'Look')).toContainText('Overrides on Versola: brand colour.');
    await page.getByRole('button', { name: 'Reset brand to the base' }).click();
    await expect(page.getByRole('img', { name: 'Overridden' })).toHaveCount(0);

    // Typing the base's own colour overrides nothing.
    await page.getByLabel('Brand colour, #rrggbb').fill('#155E75');
    await expect(card(page, 'Look')).toContainText('No overrides: this theme is the base theme.');
  });

  test('the bar names what is not saved yet, and goes quiet when it is undone', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await expect(save(page)).toBeDisabled();

    await page.getByRole('button', { name: '#5b5bd6' }).click();
    await page.getByRole('button', { name: 'Sharp' }).click();
    await page.getByRole('button', { name: 'Studio' }).click();
    await expect(bar(page)).toHaveText('Unsaved: base theme, brand colour, shape');
    await expect(save(page)).toBeEnabled();

    await page.getByRole('button', { name: 'Versola' }).click();
    await page.getByRole('button', { name: 'Reset brand to the base' }).click();
    await page.getByRole('button', { name: 'Reset shape to the base' }).click();
    await expect(bar(page)).toHaveText('No unsaved changes');
    await expect(save(page)).toBeDisabled();
  });

  test('refuses a brand colour that is not #rrggbb and lower-cases a valid one', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('default'), state });
    const hex = page.getByLabel('Brand colour, #rrggbb');

    await hex.fill('red');
    await expect(page.getByRole('alert').filter({ hasText: 'Use #rrggbb' })).toBeVisible();
    await expect(save(page)).toBeDisabled();

    await hex.fill('FF6B4A');
    await expect(save(page)).toBeEnabled();
    await save(page).click();
    expect(((await findRequest(api.requests, 'PUT', '/configuration/themes')).body as { tokens: { overrides: { brand: string } } }).tokens.overrides.brand).toBe('#ff6b4a');
  });

  test('Save theme sends the tokens, stays on the page and says it is saved', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('brand-ish'), state: { ...state, themes: [{ id: 'brand-ish', css: '', tenantId: null, tokens: { base: 'fintech', overrides: { brand: '#112233', mode: 'dark' } } }] } });
    await page.getByRole('button', { name: 'Sharp' }).click();
    await save(page).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/themes')).body).toEqual({
      id: 'brand-ish',
      css: '',
      tokens: { base: 'fintech', overrides: { brand: '#112233', mode: 'dark', shape: 'sharp' } },
    });
    await expect(page.getByRole('status').filter({ hasText: 'Theme saved' })).toBeVisible();
    await expect(bar(page)).toHaveText('No unsaved changes');
    await expect(page.getByRole('heading', { level: 1, name: 'brand-ish' })).toBeVisible();
  });

  test('the built-in default theme opens in the visual editor on the versola base', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });

    await expect(page.getByRole('button', { name: 'Versola' })).toHaveAttribute('aria-pressed', 'true');
    await expect(card(page, 'Look')).toContainText('No overrides: this theme is the base theme.');
    await expect.poll(() => srcdoc(page)).toContain(baseStylesheetMock);
  });

  test('has no Extra CSS editor, and keeps CSS that was stored with the tokens when it is saved', async ({ page }) => {
    const themes = [{ id: 'extra', css: '.y{}', tenantId: null, tokens: { base: 'versola' } }];
    const api = await loadAdminApp(page, { path: recordPath('extra'), state: { ...state, themes } });

    await expect(page.getByText('Extra CSS (optional)')).toHaveCount(0);
    await expect(page.locator('versola-code-editor')).toHaveCount(0);
    await expect(card(page, 'Look')).toContainText('carries extra CSS that was stored through the API');
    await page.getByRole('button', { name: 'Studio' }).click();
    await save(page).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/themes')).body).toEqual({ id: 'extra', css: '.y{}', tokens: { base: 'studio' } });
  });

  test('a stored stylesheet theme is shown read-only as legacy, with Delete only', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('legacy-2024'), state });

    await expect(header(page)).toContainText('Stylesheet (legacy)');
    await expect(page.getByRole('button', { name: 'Studio' })).toHaveCount(0);
    await expect(page.locator('versola-theme-record section.card, versola-theme-record versola-danger-card section.card').evaluateAll(
      (cards: Element[]) => cards.map(card => card.getAttribute('aria-label')))).resolves.toEqual(['Stylesheet (legacy)', 'Delete theme']);
    await expect(card(page, 'Stylesheet (legacy)')).toContainText('The console no longer creates or edits these');
    await expect(page.getByRole('textbox', { name: 'CSS (read-only)' })).toBeDisabled();
    await expect(page.getByRole('textbox', { name: 'CSS (read-only)' })).toHaveValue('body { font-family: Georgia, serif; }');
    // Nothing to save: no bar, no Save.
    await expect(bar(page)).toHaveCount(0);
    await expect(save(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Delete theme legacy-2024' })).toBeEnabled();
    // The preview still draws the stored stylesheet.
    await expect.poll(() => srcdoc(page)).toContain('Georgia');
    expect(api.requests.filter(request => request.method === 'PUT')).toHaveLength(0);
  });

  test('Cancel with edits asks first; keeping them stays, discarding returns to the list', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await page.getByRole('button', { name: 'Sharp' }).click();

    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(page.getByRole('dialog')).toContainText('Discard changes?');
    await page.getByRole('button', { name: 'Keep editing' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'default' })).toBeVisible();

    await crumb(page, 'Themes').click();
    await page.getByRole('button', { name: 'Discard' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Themes' })).toBeVisible();
  });

  test('Cancel with nothing changed just returns', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Themes' })).toBeVisible();
  });

  test('a failed save shows the reason and keeps the edits', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await page.route('**/configuration/themes', route => route.request().method() === 'PUT'
      ? route.fulfill({ status: 400, contentType: 'application/json', body: '{"message":"Refused"}' }) : route.fallback());
    await page.getByRole('button', { name: 'Sharp' }).click();
    await save(page).click();

    await expect(page.getByRole('alert').filter({ hasText: 'Refused' })).toBeVisible();
    await expect(bar(page)).toHaveText('Unsaved: shape');
  });
});

test.describe('background and shape', () => {
  const backgrounds = (page: Page) => group(page, 'Background style');

  test('the background is chosen from preset cards, shown against the base, sent as an override and rendered by central', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('default'), state });

    await expect(backgrounds(page).getByRole('button')).toHaveText(['Glow', 'Linear', 'Horizon', 'Flat']);
    await expect(backgrounds(page).getByRole('button', { name: 'Glow' })).toHaveAttribute('aria-pressed', 'true');
    await expect.poll(() => srcdoc(page)).toContain('--background:glow');

    await backgrounds(page).getByRole('button', { name: 'Horizon' }).click();
    await expect(page.getByTitle('Overridden · base: glow')).toBeVisible();
    await expect.poll(() => srcdoc(page)).toContain('--background:horizon');
    await save(page).click();
    expect((await findRequest(api.requests, 'PUT', '/configuration/themes')).body).toMatchObject({ tokens: { base: 'versola', overrides: { background: 'horizon' } } });
  });

  test('a base brings its own background: an override follows the base switch only when it differs', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await backgrounds(page).getByRole('button', { name: 'Linear' }).click();
    await expect(card(page, 'Look')).toContainText('Overrides on Versola: background style.');

    // Fintech is linear itself: the override is then the base's value and is dropped.
    await page.getByRole('button', { name: 'Fintech' }).click();
    await expect(card(page, 'Look')).toContainText('No overrides: this theme is the base theme.');
    await expect(backgrounds(page).getByRole('button', { name: 'Linear' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('the background cards are drawn in the brand colour of the theme', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await page.getByRole('button', { name: '#f5c518' }).click();

    await expect(backgrounds(page).locator('.bg-swatch').first()).toHaveAttribute('style', /--b:#f5c518/);
  });

  test('switching the base drops an override that equals the new base\'s value', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await page.getByRole('button', { name: 'Neutral' }).click();
    await page.getByRole('button', { name: 'Sharp' }).click();
    await expect(card(page, 'Look')).toContainText('Overrides on Versola: page background, shape.');

    // Fintech is neutral on the page and soft in shape.
    await page.getByRole('button', { name: 'Fintech' }).click();
    await expect(card(page, 'Look')).toContainText('Overrides on Fintech: shape.');
  });
});

test.describe('preview', () => {
  test('is the stylesheet central renders for the tokens, on the form and state chosen', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state: { ...state, formPreviews: [{ formId: 'credential', scenarios: [
      { id: 'email-password', title: { en: 'Email and password' }, step: { type: 'credential', primaryCredentials: ['email'] } },
      { id: 'wrong-password', title: { en: 'Wrong password' }, step: { type: 'credential', primaryCredentials: ['login'] }, error: 'login_failed' },
    ] }] } });
    await page.getByRole('button', { name: 'Studio' }).click();

    await expect.poll(() => srcdoc(page)).toContain('--brand:#ff6b4a');
    expect(await srcdoc(page)).toContain(baseStylesheetMock);
    expect(await srcdoc(page)).toContain('"primaryCredentials":["email"]');
    await page.getByRole('button', { name: 'Wrong password' }).click();
    expect(await srcdoc(page)).toContain('"error":"login_failed"');
    await page.getByRole('button', { name: '#2563eb' }).click();
    await expect.poll(() => srcdoc(page)).toContain('--brand:#2563eb');
  });

  test('the dark preview is for the device\'s scheme only, not for a scheme the theme fixes', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    const dark = group(page, 'Preview scheme').getByRole('button', { name: 'Dark' });

    await dark.click();
    await expect.poll(() => srcdoc(page)).toContain('color-scheme:dark');
    await group(page, 'Colour scheme').getByRole('button', { name: 'Light' }).click();
    await expect(dark).toBeDisabled();
    await expect(group(page, 'Preview scheme').getByRole('button', { name: 'Light' })).toHaveAttribute('aria-pressed', 'true');

    await group(page, 'Colour scheme').getByRole('button', { name: 'Auto' }).click();
    await expect.poll(() => srcdoc(page)).not.toContain('color-scheme:dark');
  });

  test('starts in the scheme of the operator\'s device', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'light' });
    await loadAdminApp(page, { path: recordPath('default'), state });
    await expect.poll(() => srcdoc(page)).toContain(':root{color-scheme:light}');

    await page.emulateMedia({ colorScheme: 'dark' });
    await loadAdminApp(page, { path: recordPath('default'), state });
    await expect(group(page, 'Preview scheme').getByRole('button', { name: 'Dark' })).toHaveAttribute('aria-pressed', 'true');
    await group(page, 'Preview scheme').getByRole('button', { name: 'Light' }).click();
    await expect.poll(() => srcdoc(page)).toContain(':root{color-scheme:light}');
    expect(await srcdoc(page)).not.toContain(':root{color-scheme:dark}');
  });

  test('an answer to an earlier edit is not applied once a newer edit is waiting to render', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await expect.poll(() => srcdoc(page)).toContain('--brand:#155e75');

    let release: () => void = () => {};
    const held = new Promise<void>(resolve => { release = resolve; });
    let arrived: () => void = () => {};
    const requested = new Promise<void>(resolve => { arrived = resolve; });
    await page.route('**/configuration/themes/css', async route => {
      if (!route.request().postData()?.includes('#5b5bd6')) return route.fallback();
      arrived();
      await held;
      return route.fallback();
    });

    await page.getByRole('button', { name: '#5b5bd6' }).click();
    await requested;
    await page.getByRole('button', { name: '#e5484d' }).click();
    release();
    await page.waitForTimeout(100);
    expect(await srcdoc(page)).not.toContain('--brand:#5b5bd6');
    await expect.poll(() => srcdoc(page)).toContain('--brand:#e5484d');
    await expect(page.locator('versola-preview-pane')).not.toHaveAttribute('busy', '');
  });

  test('a preview that cannot be rendered says so and is marked as out of date', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });
    await expect.poll(() => srcdoc(page)).toContain('--brand:#155e75');
    await page.route('**/configuration/themes/css', route => route.fulfill({ status: 400, contentType: 'application/json', body: '{"message":"Refused"}' }));
    await page.getByRole('button', { name: '#5b5bd6' }).click();

    await expect(page.getByRole('alert').filter({ hasText: 'Refused' })).toContainText('last version that rendered');
    await expect(page.locator('versola-preview-pane')).toHaveAttribute('stale', '');
    expect(await srcdoc(page)).toContain('--brand:#155e75');
  });

  test('says when no form has an active version to preview', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state: { ...state, forms: [] } });

    await expect(page.getByText('No form has an active version to preview yet.')).toBeVisible();
  });
});

test.describe('delete', () => {
  test('is the last card, disabled with the clients that use the theme', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });

    const danger = page.locator('versola-danger-card');
    await expect(danger.getByRole('heading', { name: 'Delete theme' })).toBeVisible();
    await expect(danger.getByRole('button', { name: 'Delete theme shop-brand' })).toBeDisabled();
    await expect(danger).toContainText('Used by shop-mobile and storefront-web. Move them to another theme first.');
    expect(await danger.evaluate(el => el.nextElementSibling === null)).toBe(true);
  });

  test('the default theme cannot be deleted, and says why', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('default'), state });

    await expect(page.getByRole('button', { name: 'Delete theme default' })).toBeDisabled();
    await expect(page.locator('versola-danger-card')).toContainText('cannot be deleted');
  });

  test('a theme no client uses is deleted after a confirm that names it', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('ledger'), state });
    await page.getByRole('button', { name: 'Delete theme ledger' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('Delete theme');
    await expect(dialog).toContainText('ledger');
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    expect(api.requests.filter(request => request.method === 'DELETE')).toHaveLength(0);

    await page.getByRole('button', { name: 'Delete theme ledger' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();
    expect((await findRequest(api.requests, 'DELETE', '/configuration/themes')).searchParams['id']).toBe('ledger');
    await expect(page.getByRole('heading', { level: 1, name: 'Themes' })).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: 'Deleted theme ledger.' })).toBeVisible();
    await expect(themeRow(page, 'ledger')).toHaveCount(0);
  });

  test('a refusal from central (a client of another tenant uses it) is shown', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('ledger'), state });
    await page.route('**/configuration/themes?id=ledger', route => route.fulfill({ status: 409, contentType: 'application/json', body: '{"message":"One or more clients still use this theme"}' }));
    await page.getByRole('button', { name: 'Delete theme ledger' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

    await expect(page.getByRole('alert').filter({ hasText: 'clients still use this theme' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'ledger' })).toBeVisible();
  });
});

test.describe('create', () => {
  test('a new theme is a visual one on the default base, with no overrides', async ({ page }) => {
    const api = await newTheme(page);
    await page.getByPlaceholder('e.g. default').fill('mine');

    await expect(page.getByRole('button', { name: 'Versola' })).toHaveAttribute('aria-pressed', 'true');
    await expect(card(page, 'Look')).toContainText('No overrides: this theme is the base theme.');
    await expect(bar(page)).toHaveText('New theme');
    expect(await created(page, api)).toEqual({ id: 'mine', css: '', tenantId: null, tokens: { base: 'versola' } });
  });

  test('builds a theme on another base, with overrides, in this tenant', async ({ page }) => {
    const api = await newTheme(page);
    await page.getByPlaceholder('e.g. default').fill('mine');
    await page.getByLabel('Scope').selectOption('tenant');
    await page.getByRole('button', { name: 'Studio' }).click();
    await page.getByRole('button', { name: 'Sharp' }).click();
    await expect(bar(page)).toHaveText('New theme: base theme, shape');

    expect(await created(page, api)).toEqual({ id: 'mine', css: '', tenantId: 'tenant-alpha', tokens: { base: 'studio', overrides: { shape: 'sharp' } } });
  });

  test('lands on the new theme\'s page and says it is created', async ({ page }) => {
    await newTheme(page);
    await page.getByPlaceholder('e.g. default').fill('mine');
    await save(page, 'Create theme').click();

    await expect(page.getByRole('heading', { level: 1, name: 'mine' })).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: 'Theme created' })).toBeVisible();
    await expect(page).toHaveURL(/id=mine/);
    await expect(page).not.toHaveURL(/new=1/);
  });

  test('cannot be created without an ID', async ({ page }) => {
    await newTheme(page);

    await expect(save(page, 'Create theme')).toBeDisabled();
    await page.getByPlaceholder('e.g. default').fill('x');
    await expect(save(page, 'Create theme')).toBeEnabled();
  });

  test('has no stylesheet path: no Built as choice, no CSS editor, every new theme is built on a base', async ({ page }) => {
    await newTheme(page);

    await expect(page.getByRole('group', { name: 'Built as' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Stylesheet' })).toHaveCount(0);
    await expect(page.locator('versola-code-editor')).toHaveCount(0);
    await expect(page.getByText('Extra CSS')).toHaveCount(0);
  });

  test('cannot be created when central has no built-in themes to build on', async ({ page }) => {
    await newTheme(page, { themePresets: [] });
    await page.getByPlaceholder('e.g. default').fill('plain');

    await expect(group(page, 'Base theme').getByRole('button')).toHaveCount(0);
    await expect(save(page, 'Create theme')).toBeDisabled();
  });

  test('an existing ID is refused by central and shown', async ({ page }) => {
    await newTheme(page);
    await page.getByPlaceholder('e.g. default').fill('default');
    await save(page, 'Create theme').click();

    await expect(page.getByRole('alert').filter({ hasText: 'already exists' })).toBeVisible();
  });

  test('+ Create theme opens it from the list, and Cancel returns', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state });
    await page.getByRole('button', { name: '+ Create theme' }).click();
    await expect(page).toHaveURL(/new=1/);

    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Themes' })).toBeVisible();
  });

  test('opens on the default base once the built-in themes have arrived', async ({ page }) => {
    await setupConfigApiMocks(page, state);
    let release: () => void = () => {};
    const held = new Promise<void>(resolve => { release = resolve; });
    await page.route('**/configuration/themes/presets', async route => {
      await held;
      return route.fallback();
    });
    await page.goto(newPath);
    release();

    await expect(page.getByRole('button', { name: 'Versola' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('the fields are as light as the other forms of the console', async ({ page }) => {
    await newTheme(page);

    await expect(page.locator('#theme-id')).toHaveCSS('background-color', 'rgb(245, 243, 238)');
    await expect(page.locator('#theme-scope')).toHaveCSS('background-color', 'rgb(245, 243, 238)');
  });
});

const presetIds = ['Versola', 'Studio', 'Fintech', 'Wellness', 'Editorial', 'Sunny', 'Berry'];

test.describe('base theme picker', () => {
  test('lists all seven built-in themes as one row of pills, the selected one filled, and no thumbnails', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });
    const pills = group(page, 'Base theme').getByRole('button');

    await expect(pills).toHaveText(presetIds);
    await expect(pills.filter({ has: page.locator('.dot') })).toHaveCount(7);
    await expect(group(page, 'Base theme').locator('versola-theme-thumb')).toHaveCount(0);
    await expect(page.locator('versola-theme-record versola-theme-thumb')).toHaveCount(0);
    await expect(group(page, 'Base theme').getByRole('button', { pressed: true })).toHaveText('Versola');
    await expect(group(page, 'Base theme').getByRole('button', { name: 'Versola' })).toHaveClass(/seg-active/);
    // Each pill's dot is the preset's own brand colour.
    await expect(pills.nth(1).locator('.dot')).toHaveCSS('background-color', 'rgb(255, 107, 74)');
    await expect(pills.nth(6).locator('.dot')).toHaveCSS('background-color', 'rgb(180, 52, 138)');
  });

  test('says under the picker what the chosen built-in theme sets, and never calls one dark or light', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });
    const hint = page.locator('#preset-hint');

    await expect(hint).toContainText('Versola: Brand #155e75, soft corners, bold page, glow background.');
    await page.getByRole('button', { name: 'Editorial' }).click();
    await expect(hint).toContainText('Editorial: Brand #14130f, sharp corners, neutral page, flat background.');
    await page.getByRole('button', { name: 'Wellness' }).click();
    await expect(hint).toContainText('Wellness: Brand #0e9f8e, round corners, soft page, horizon background.');
    await expect(hint).not.toContainText(/midnight|\bdark theme\b|\blight theme\b/i);
    for (const name of presetIds) await expect(group(page, 'Base theme').getByRole('button', { name })).not.toContainText(/dark|light|midnight/i);
  });

  test('every built-in theme is scheme-neutral: the preview\'s Light and Dark are the only place the scheme shows', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });

    await expect(group(page, 'Colour scheme').getByRole('button', { pressed: true })).toHaveText('Auto');
    for (const name of presetIds) {
      await page.getByRole('button', { name, exact: true }).click();
      await expect(group(page, 'Colour scheme').getByRole('button', { pressed: true })).toHaveText('Auto');
    }
    await expect(group(page, 'Preview scheme').getByRole('button')).toHaveText(['Light', 'Dark']);
  });

  test('stays a compact row that does not eat the page, at 1440, 1024 and 375', async ({ page }) => {
    for (const [width, rows] of [[1440, 2], [1024, 4], [375, 4]] as const) {
      await page.setViewportSize({ width, height: 900 });
      await loadAdminApp(page, { path: recordPath('shop-brand'), state });
      const box = (await group(page, 'Base theme').boundingBox())!;
      // Pills are 32px high: at most `rows` rows with their gaps.
      expect(box.height, `${width}px`).toBeLessThanOrEqual(rows * 32 + (rows - 1) * 8 + 2);
      expect(await noSideScroll(page), `${width}px`).toBe(true);
      await expect(group(page, 'Base theme').getByRole('button', { name: 'Berry' })).toBeVisible();
    }
  });

  test('a new theme can be built on any of the seven', async ({ page }) => {
    const api = await newTheme(page);
    await page.getByPlaceholder('e.g. default').fill('sunny-web');
    await page.getByRole('button', { name: 'Sunny', exact: true }).click();

    expect(await created(page, api)).toEqual({ id: 'sunny-web', css: '', tenantId: null, tokens: { base: 'sunny' } });
  });
});

test('the record never scrolls sideways at 1440, 1024 and 375', async ({ page }) => {
  for (const width of [1440, 1024, 375]) {
    await page.setViewportSize({ width, height: 900 });
    await loadAdminApp(page, { path: recordPath('shop-brand'), state });
    await expect(page.getByRole('heading', { level: 1, name: 'shop-brand' })).toBeVisible();
    expect(await noSideScroll(page)).toBe(true);
  }
});

function header(page: Page) {
  return page.locator('versola-record-header');
}
