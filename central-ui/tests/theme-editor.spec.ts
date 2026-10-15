import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';
import { setupConfigApiMocks, type MockConfigState } from './mocks';

const themesPath = '/?view=themes&tenant=tenant-alpha';
const formsPath = () => '/?view=forms&tenant=tenant-alpha';

const presets = [
  { id: 'versola', title: { en: 'Versola', ru: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto' } },
  { id: 'studio', title: { en: 'Studio', ru: 'Студия' }, tokens: { brand: '#ff6b4a', shape: 'round', surface: 'bold', mode: 'auto' } },
  { id: 'fintech', title: { en: 'Fintech', ru: 'Финтех' }, tokens: { brand: '#5b5bd6', shape: 'soft', surface: 'neutral', mode: 'auto' } },
];

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

const credentialStates = {
  formId: 'credential',
  scenarios: [
    { id: 'email-password', title: { en: 'Email and password' }, step: { type: 'credential', primaryCredentials: ['email'] } },
    { id: 'wrong-password', title: { en: 'Wrong password' }, step: { type: 'credential', primaryCredentials: ['login'] }, error: 'login_failed' },
  ],
};

const themes = [
  { id: 'default', css: 'DEFAULT-CSS', tenantId: null },
  { id: 'legacy', css: 'LEGACY-CSS', tenantId: null },
  { id: 'brand', css: '', tenantId: null, tokens: { base: 'fintech', overrides: { brand: '#112233', mode: 'dark' } } },
];

const state: Partial<MockConfigState> = {
  forms: [credential],
  formLocales: [{ code: 'en', name: 'English' }],
  locales: [{ code: 'en', name: 'English', active: true, isDefault: true }],
  formPreviews: [credentialStates],
  themePresets: presets,
  themes,
};

async function newTheme(page: Page, overrides: Partial<MockConfigState> = {}) {
  const api = await loadAdminApp(page, { path: themesPath, state: { ...state, ...overrides } });
  await page.getByRole('button', { name: '+ New Theme', exact: true }).click();
  return api;
}

const srcdoc = (page: Page) => page.locator('iframe.preview-iframe').evaluate((frame: HTMLIFrameElement) => frame.srcdoc);
const save = async (page: Page, api: Awaited<ReturnType<typeof loadAdminApp>>) => {
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  return (await findRequest(api.requests, 'POST', '/configuration/themes')).body as Record<string, unknown>;
};

test('a new theme is a visual one on the default base, with no overrides', async ({ page }) => {
  const api = await newTheme(page);
  await page.getByPlaceholder('e.g. default').fill('mine');

  await expect(page.getByRole('group', { name: 'Built as' }).getByRole('button', { name: 'Visual' })).toHaveClass(/seg-active/);
  await expect(page.getByRole('button', { name: 'Versola' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByText('No overrides: this theme is the base theme.')).toBeVisible();

  expect(await save(page, api)).toEqual({ id: 'mine', css: '', tenantId: null, tokens: { base: 'versola' } });
});

test('builds a theme on another base', async ({ page }) => {
  const api = await newTheme(page);
  await page.getByPlaceholder('e.g. default').fill('mine');
  await page.getByRole('button', { name: 'Studio' }).click();

  expect(await save(page, api)).toMatchObject({ tokens: { base: 'studio' } });
});

test('an override is shown against the base value, can be reset, and one equal to the base is not an override', async ({ page }) => {
  const api = await newTheme(page);
  await page.getByPlaceholder('e.g. default').fill('mine');

  await page.getByRole('button', { name: '#5b5bd6' }).click();
  await expect(page.getByText('Overridden · base: #155e75')).toBeVisible();
  await expect(page.getByText('1 override on Versola')).toBeVisible();

  await page.getByRole('button', { name: 'Reset brand to the base' }).click();
  await expect(page.getByText('Overridden · base: #155e75')).toHaveCount(0);

  // Typing the base's own colour overrides nothing.
  await page.getByLabel('Brand colour, #rrggbb').fill('#155E75');
  await expect(page.getByText('No overrides: this theme is the base theme.')).toBeVisible();

  await page.getByRole('button', { name: 'Sharp' }).click();
  await page.getByRole('button', { name: 'Neutral' }).click();
  expect(await save(page, api)).toMatchObject({ tokens: { base: 'versola', overrides: { shape: 'sharp', surface: 'neutral' } } });
});

test('refuses a brand colour that is not #rrggbb and lower-cases a valid one', async ({ page }) => {
  const api = await newTheme(page);
  await page.getByPlaceholder('e.g. default').fill('mine');
  const hex = page.getByLabel('Brand colour, #rrggbb');

  await hex.fill('red');
  await expect(page.getByRole('alert').filter({ hasText: 'Use #rrggbb' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Create', exact: true })).toBeDisabled();

  await hex.fill('FF6B4A');
  await expect(page.getByRole('button', { name: 'Create', exact: true })).toBeEnabled();
  expect(await save(page, api)).toMatchObject({ tokens: { overrides: { brand: '#ff6b4a' } } });
});

test('switching the base keeps the overrides, and they can be cleared at once', async ({ page }) => {
  await newTheme(page);
  await page.getByRole('button', { name: 'Sharp' }).click();
  await page.getByRole('group', { name: 'Colour scheme' }).getByRole('button', { name: 'Dark' }).click();
  await expect(page.getByText('2 overrides on Versola')).toBeVisible();

  await page.getByRole('button', { name: 'Fintech' }).click();
  await expect(page.getByText('2 overrides on Fintech')).toBeVisible();

  await page.getByRole('button', { name: 'Clear overrides' }).click();
  await expect(page.getByText('No overrides: this theme is the base theme.')).toBeVisible();
});

test('keeps the theme\'s own CSS next to its tokens', async ({ page }) => {
  const api = await newTheme(page);
  await page.getByPlaceholder('e.g. default').fill('mine');
  await page.getByText('Extra CSS (optional)').click();
  await page.locator('versola-code-editor').evaluate((editor: HTMLElement) => {
    editor.dispatchEvent(new CustomEvent('code-input', { detail: { value: '.x { top: 0; }' } }));
  });

  expect(await save(page, api)).toMatchObject({ css: '.x { top: 0; }', tokens: { base: 'versola' } });
});

test('previews the stylesheet central renders for the tokens, on the form and state chosen', async ({ page }) => {
  await newTheme(page);
  await page.getByRole('button', { name: 'Studio' }).click();

  await expect.poll(() => srcdoc(page)).toContain('--brand:#ff6b4a');
  expect(await srcdoc(page)).toContain('DEFAULT-CSS');
  expect(await srcdoc(page)).toContain('"primaryCredentials":["email"]');

  await page.getByRole('button', { name: 'Wrong password' }).click();
  expect(await srcdoc(page)).toContain('"error":"login_failed"');

  await page.getByRole('button', { name: '#2563eb' }).click();
  await expect.poll(() => srcdoc(page)).toContain('--brand:#2563eb');
});

test('the dark preview is available for the device\'s scheme only, not for a scheme the theme fixes', async ({ page }) => {
  await newTheme(page);
  const dark = page.getByRole('group', { name: 'Preview scheme' }).getByRole('button', { name: 'Dark' });

  await dark.click();
  await expect.poll(() => srcdoc(page)).toContain('color-scheme:dark');

  await page.getByRole('group', { name: 'Colour scheme' }).getByRole('button', { name: 'Light' }).click();
  await expect(dark).toBeDisabled();
});

test('the fields of the editor are as light as the other forms of the console in the light theme', async ({ page }) => {
  await newTheme(page);

  await expect(page.locator('#theme-id')).toHaveCSS('background-color', 'rgb(250, 249, 247)');
  await expect(page.locator('#theme-scope')).toHaveCSS('background-color', 'rgb(250, 249, 247)');
});

test('opens a stored token theme on its base with its overrides, and saves it back as tokens', async ({ page }) => {
  const api = await loadAdminApp(page, { path: themesPath, state });
  await page.getByRole('button', { name: 'Edit theme brand', exact: true }).click();

  await expect(page.getByRole('button', { name: 'Fintech' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByLabel('Brand colour, #rrggbb')).toHaveValue('#112233');
  await expect(page.getByText('2 overrides on Fintech')).toBeVisible();
  await expect(page.getByRole('group', { name: 'Built as' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/themes')).body).toEqual({
    id: 'brand',
    css: '',
    tokens: { base: 'fintech', overrides: { brand: '#112233', mode: 'dark' } },
  });
});

test('a theme written as CSS opens in the CSS editor and is saved without tokens', async ({ page }) => {
  const api = await loadAdminApp(page, { path: themesPath, state });
  await page.getByRole('button', { name: 'Edit theme legacy', exact: true }).click();

  await expect(page.getByRole('button', { name: 'Studio' })).toHaveCount(0);
  await expect(page.getByText('A theme written as CSS replaces the default stylesheet entirely.')).toBeVisible();

  await page.getByRole('button', { name: 'Save', exact: true }).click();
  const body = (await findRequest(api.requests, 'PUT', '/configuration/themes')).body as Record<string, unknown>;
  expect(body).toEqual({ id: 'legacy', css: 'LEGACY-CSS' });
});

test('the theme list says which themes are built from a base and which are stylesheets', async ({ page }) => {
  await loadAdminApp(page, { path: themesPath, state });

  await expect(page.locator('.theme-card').filter({ hasText: 'brand' }).locator('.theme-kind')).toHaveText('fintech +2');
  await expect(page.locator('.theme-card').filter({ hasText: 'legacy' }).locator('.theme-kind')).toHaveText('CSS');
});

test('offers only CSS themes when central has no built-in themes', async ({ page }) => {
  const api = await newTheme(page, { themePresets: [] });
  await page.getByPlaceholder('e.g. default').fill('plain');

  await expect(page.getByRole('group', { name: 'Built as' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Studio' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  expect((await findRequest(api.requests, 'POST', '/configuration/themes')).body).toEqual({ id: 'plain', css: '', tenantId: null });
});

test('a new theme can still be written as CSS', async ({ page }) => {
  const api = await newTheme(page);
  await page.getByPlaceholder('e.g. default').fill('plain');
  await page.getByRole('group', { name: 'Built as' }).getByRole('button', { name: 'Stylesheet' }).click();

  await expect(page.getByRole('button', { name: 'Studio' })).toHaveCount(0);
  expect(await save(page, api)).toEqual({ id: 'plain', css: '', tenantId: null });
});

test('Themes is a page of its own in the side panel, after Forms', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath(), state });

  const nav = page.locator('versola-navigation');
  await expect(nav.getByText('Themes', { exact: true })).toBeVisible();
  await nav.getByText('Themes', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Themes', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Edit theme legacy', exact: true })).toBeVisible();
});

test('lists the global themes apart from the tenant\'s own', async ({ page }) => {
  await loadAdminApp(page, {
    path: themesPath,
    state: { ...state, themes: [...themes, { id: 'tenant-only', css: 'x{}', tenantId: 'tenant-alpha' }] },
  });

  const groups = page.locator('versola-themes-list .group-title');
  await expect(groups).toHaveText(['Global', 'This tenant']);
  await expect(page.locator('.theme-card')).toHaveCount(4);
});

test('shows the brand colour of a visual theme, its own override or its base\'s', async ({ page }) => {
  await loadAdminApp(page, { path: themesPath, state: { ...state, themes: [...themes, { id: 'plain-base', css: '', tenantId: null, tokens: { base: 'studio' } }] } });

  await expect(page.locator('.theme-card').filter({ hasText: 'brand' }).locator('.swatch')).toHaveCSS('background-color', 'rgb(17, 34, 51)');
  await expect(page.locator('.theme-card').filter({ hasText: 'plain-base' }).locator('.swatch')).toHaveCSS('background-color', 'rgb(255, 107, 74)');
  await expect(page.locator('.theme-card').filter({ hasText: 'legacy' }).locator('.swatch')).toHaveCount(0);
});

test('creates a theme written as a stylesheet from the page', async ({ page }) => {
  const api = await newTheme(page, { themePresets: [] });
  await page.getByPlaceholder('e.g. default').fill('plain');
  await page.getByRole('button', { name: 'Create', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/themes')).body).toMatchObject({ id: 'plain', tenantId: null });
  await expect(page.getByRole('button', { name: 'Edit theme plain', exact: true })).toBeVisible();
});

test('edits and deletes a theme from the page, but not the default one', async ({ page }) => {
  const api = await loadAdminApp(page, { path: themesPath, state });

  await page.getByRole('button', { name: 'Edit theme default', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Delete', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();

  await page.getByRole('button', { name: 'Edit theme legacy', exact: true }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/themes')).searchParams['id']).toBe('legacy');
  await expect(page.getByRole('button', { name: 'Edit theme legacy', exact: true })).toHaveCount(0);
});

test('without the manage permission the page lists themes but offers no way to change them', async ({ page }) => {
  await loadAdminApp(page, {
    path: themesPath,
    state: { ...state, myPermissions: { resources: { central: { permissions: ['forms:read'] } }, isProd: false } },
  });

  await expect(page.getByRole('button', { name: 'Edit theme legacy', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '+ New Theme', exact: true })).toHaveCount(0);
});

test('the dialog says Stylesheet for a theme written as CSS and Extra CSS for the addition to a visual one', async ({ page }) => {
  await newTheme(page);

  await expect(page.getByRole('group', { name: 'Built as' }).getByRole('button', { name: 'Stylesheet' })).toBeVisible();
  await expect(page.getByText('Extra CSS (optional)')).toBeVisible();
  await expect(page.getByText('Custom CSS')).toHaveCount(0);
});

test('an answer to an earlier edit is not applied once a newer edit is waiting to render', async ({ page }) => {
  await newTheme(page);
  await expect.poll(() => srcdoc(page)).toContain('--brand:#155e75');

  // Hold the render of the first edit until the second one has been made.
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
  // The second edit is still inside its debounce: the first answer must not show.
  await page.waitForTimeout(100);
  expect(await srcdoc(page)).not.toContain('--brand:#5b5bd6');
  await expect.poll(() => srcdoc(page)).toContain('--brand:#e5484d');
  await expect(page.locator('.preview-stage')).toHaveAttribute('aria-busy', 'false');
});

test('a preview that cannot be rendered says so and is marked as out of date', async ({ page }) => {
  await newTheme(page);
  await expect.poll(() => srcdoc(page)).toContain('--brand:#155e75');
  await page.route('**/configuration/themes/css', route => route.fulfill({ status: 400, contentType: 'application/json', body: '{"message":"Refused"}' }));

  await page.getByRole('button', { name: '#5b5bd6' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Refused' })).toContainText('last version that rendered');
  await expect(page.locator('.preview-stage')).toHaveCSS('opacity', '0.55');
  expect(await srcdoc(page)).toContain('--brand:#155e75');
});

test('New Theme waits for the built-in themes, then opens as a visual theme on the default base', async ({ page }) => {
  await setupConfigApiMocks(page, state);
  let release: () => void = () => {};
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/configuration/themes/presets', async route => {
    await held;
    return route.fallback();
  });
  await page.goto(themesPath);

  const create = page.getByRole('button', { name: '+ New Theme', exact: true });
  await expect(create).toBeDisabled();
  release();
  await expect(create).toBeEnabled();
  await create.click();
  await expect(page.getByRole('group', { name: 'Built as' }).getByRole('button', { name: 'Visual' })).toHaveClass(/seg-active/);
  await expect(page.getByRole('button', { name: 'Versola' })).toHaveAttribute('aria-pressed', 'true');
});

test('switching the base drops an override that equals the new base\'s value', async ({ page }) => {
  const api = await newTheme(page);
  await page.getByPlaceholder('e.g. default').fill('mine');
  // Fintech is neutral on the page and soft in shape; Studio is round.
  await page.getByRole('button', { name: 'Neutral' }).click();
  await page.getByRole('button', { name: 'Sharp' }).click();
  await expect(page.getByText('2 overrides on Versola')).toBeVisible();

  await page.getByRole('button', { name: 'Fintech' }).click();
  // Neutral is Fintech's own surface now, so only the shape stays an override.
  await expect(page.getByText('1 override on Fintech')).toBeVisible();
  expect(await save(page, api)).toMatchObject({ tokens: { base: 'fintech', overrides: { shape: 'sharp' } } });
});

test('the dark preview is switched off again when the theme fixes its scheme', async ({ page }) => {
  await newTheme(page);
  const scheme = page.getByRole('group', { name: 'Preview scheme' });
  await scheme.getByRole('button', { name: 'Dark' }).click();
  await page.getByRole('group', { name: 'Colour scheme' }).getByRole('button', { name: 'Light' }).click();
  await page.getByRole('group', { name: 'Colour scheme' }).getByRole('button', { name: 'Auto' }).click();

  await expect(scheme.getByRole('button', { name: 'Light' })).toHaveClass(/seg-active/);
  await expect.poll(() => srcdoc(page)).not.toContain('color-scheme:dark');
});
