import { expect, test, type Page } from '@playwright/test';
import { loadAdminApp } from './fixtures';
import { consentForm, consentPreviewState, consentPreviews } from './consent-preview-fixtures';
import type { MockConfigState } from './mocks';

const clientsPath = '/?view=clients&tenant=tenant-alpha';

const partnerClient = {
  id: 'acme-app',
  clientName: { en: 'Acme Wallet' },
  logoUri: 'https://acme.example/logo.svg',
  redirectUris: ['https://acme.example/callback'],
  scope: ['openid'],
  permissions: [],
  secretRotation: false,
  accessTokenTtl: 3600,
  authFlow: { primary: { credentials: ['phone'], inlinePassword: false, factors: [{ type: 'otp', required: true }] }, passkey: null, otpType: 'sms' },
  consentFlow: { allowPartial: false, rememberDuration: 15552000 },
  theme: 'money',
};

const stateWith = (overrides: Partial<MockConfigState> = {}, client: Record<string, unknown> = partnerClient): Partial<MockConfigState> => ({
  ...consentPreviewState,
  clients: { 'tenant-alpha': [client as never] },
  ...overrides,
});

const frame = (page: Page) => page.locator('versola-client-consent-preview iframe');
const srcdoc = (page: Page) => frame(page).evaluate((element: HTMLIFrameElement) => element.srcdoc);
const renders = (api: Awaited<ReturnType<typeof loadAdminApp>>) =>
  api.requests.filter(request => request.method === 'POST' && request.pathname === '/configuration/themes/css').map(request => request.body as Record<string, unknown>);

async function openBranding(page: Page) {
  await page.getByRole('button', { name: /^Open client (.* )?acme-app$/ }).click();
  await page.getByRole('button', { name: 'Edit branding and logout', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Appearance for this app' })).toBeVisible();
}

async function startPartnerWizard(page: Page) {
  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: /Web app/ }).click();
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
  await page.locator('versola-client-form #client-id').fill('wizard-app');
  await page.locator('versola-client-form input#client-name').fill('Wizard App');
  await page.locator('versola-client-form #redirect-uri').fill('https://wizard.example/callback');
  await page.locator('versola-client-form #redirect-uri').press('Enter');
  await page.getByRole('button', { name: /^Continue to sign-in$/ }).click();
  await page.getByRole('button', { name: 'Partner app', exact: true }).click();
}

test('the Branding page draws the consent form for the app, with its name and logo and the typical request', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: stateWith() });
  await openBranding(page);

  await expect(frame(page)).toHaveCount(1);
  const doc = await srcdoc(page);
  const config = JSON.parse(/window\.__VERSOLA_FORM__=(\{.*?\});<\/script>/s.exec(doc)![1]);
  expect(config.step).toMatchObject({ type: 'consent', clientName: 'Acme Wallet', clientNames: { en: 'Acme Wallet' }, logoUri: 'https://acme.example/logo.svg', marker: 'typical' });
  // The catalog's sample app is gone, and the theme's own CSS reached the frame through central's render.
  expect(doc).not.toContain('Danceliker');
  expect(doc).toContain('MONEY-CSS');
  expect(doc).toContain('BASE-STYLESHEET');
  const inner = page.frameLocator('versola-client-consent-preview iframe');
  await expect(inner.locator('#consent-client')).toHaveText('Acme Wallet');
  await expect(inner.locator('#consent-logo')).toHaveAttribute('src', 'https://acme.example/logo.svg');
  // The sketch of a card on a dark bar is gone.
  await expect(page.locator('versola-client-form').locator('.sketch')).toHaveCount(0);
  // The first render asks central for the theme as it is: no overrides.
  expect(renders(api)[0]).toEqual({ tokens: { base: 'fintech', overrides: { shape: 'sharp' } }, css: 'MONEY-CSS' });
});

test('choosing an override asks central for the client\'s effective stylesheet and redraws the frame', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: stateWith() });
  await openBranding(page);
  await expect(frame(page)).toHaveCount(1);
  expect(await srcdoc(page)).not.toContain('client overrides');

  await page.getByRole('region', { name: 'Appearance for this app' }).getByRole('group', { name: 'Shape' }).getByRole('button', { name: 'Round' }).click();
  await expect.poll(async () => srcdoc(page)).toContain('client overrides {"shape":"round"}');
  const last = renders(api).at(-1)!;
  expect(last).toEqual({ tokens: { base: 'fintech', overrides: { shape: 'sharp' } }, css: 'MONEY-CSS', overrides: { shape: 'round' } });
});

test('a stylesheet theme is rendered without tokens, with the overrides on top', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: stateWith({}, { ...partnerClient, theme: 'legacy', themeOverrides: { brand: '#112233' } }) });
  await openBranding(page);
  await expect.poll(async () => srcdoc(page)).toContain('LEGACY-CSS');

  expect(renders(api)[0]).toEqual({ css: 'LEGACY-CSS', overrides: { brand: '#112233' } });
  expect(await srcdoc(page)).not.toContain('BASE-STYLESHEET');
});

test('the Light and Dark buttons set the scheme of the frame, starting from the device', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await loadAdminApp(page, { path: clientsPath, state: stateWith() });
  await openBranding(page);
  const scheme = page.getByRole('group', { name: 'Preview scheme' });
  await expect(scheme.getByRole('button', { name: 'Dark' })).toHaveAttribute('aria-pressed', 'true');
  await expect.poll(async () => srcdoc(page)).toContain(':root{color-scheme:dark}');

  await scheme.getByRole('button', { name: 'Light' }).click();
  await expect(scheme.getByRole('button', { name: 'Light' })).toHaveAttribute('aria-pressed', 'true');
  await expect.poll(async () => srcdoc(page)).toContain(':root{color-scheme:light}');
});

test('a look fixed to one scheme disables the other, and Mobile and Desktop change the width of the frame', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await loadAdminApp(page, { path: clientsPath, state: stateWith({}, { ...partnerClient, themeOverrides: { mode: 'light' } }) });
  await openBranding(page);
  const scheme = page.getByRole('group', { name: 'Preview scheme' });
  await expect(scheme.getByRole('button', { name: 'Dark' })).toBeDisabled();
  await expect(scheme.getByRole('button', { name: 'Light' })).toHaveAttribute('aria-pressed', 'true');
  expect(await srcdoc(page)).not.toContain('color-scheme:dark');

  const width = async () => (await frame(page).boundingBox())!.width;
  expect(await width()).toBeCloseTo(390, 0);
  await page.getByRole('group', { name: 'Preview width' }).getByRole('button', { name: 'Desktop' }).click();
  await expect.poll(width).toBeGreaterThan(600);
});

test('shows why the preview is missing when central refuses to render, instead of a blank frame', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: stateWith({ themeRenderError: 'Theme could not be rendered' }) });
  await openBranding(page);
  await expect(page.locator('versola-client-consent-preview').getByRole('alert')).toContainText('Theme could not be rendered');
  await expect(frame(page)).toHaveCount(0);
});

test('says so in one line when central has no consent form, and keeps the controls', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: stateWith({ forms: [] }) });
  await openBranding(page);
  const block = page.getByRole('region', { name: 'Appearance for this app' });
  await expect(block.getByText('The consent screen cannot be previewed here')).toBeVisible();
  await expect(frame(page)).toHaveCount(0);
  await expect(block.getByRole('group', { name: 'Shape' })).toBeVisible();
});

test('says so when the catalog of previews is not there (an older central)', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: stateWith({ formPreviews: null }) });
  await openBranding(page);
  await expect(page.getByText('The consent screen cannot be previewed here')).toBeVisible();
  await expect(frame(page)).toHaveCount(0);
});

test('falls back to the first consent scenario when the catalog lacks the typical one', async ({ page }) => {
  const catalog = { ...consentPreviews, scenarios: consentPreviews.scenarios.filter(s => s.id !== 'typical') };
  await loadAdminApp(page, { path: clientsPath, state: stateWith({ formPreviews: [catalog] }) });
  await openBranding(page);
  await expect.poll(async () => srcdoc(page)).toContain('"marker":"everything"');
});

test('does not draw a version of the consent form that is not active', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: stateWith({ forms: [{ ...consentForm, active: false }] }) });
  await openBranding(page);
  await expect(page.getByText('The consent screen cannot be previewed here')).toBeVisible();
});

test('the partner wizard draws the consent form with the name and logo typed so far', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: stateWith() });
  await startPartnerWizard(page);
  await expect(frame(page)).toHaveCount(1);
  await expect(page.frameLocator('versola-client-consent-preview iframe').locator('#consent-client')).toHaveText('Wizard App');
  await expect(page.frameLocator('versola-client-consent-preview iframe').locator('#consent-logo')).toHaveCount(0);

  await page.getByLabel('Logo URI').fill('https://wizard.example/icon.png');
  await expect.poll(async () => srcdoc(page)).toContain('"logoUri":"https://wizard.example/icon.png"');
  await page.getByLabel('Forms Theme').selectOption('money');
  await page.getByRole('region', { name: 'Appearance for this app' }).getByRole('group', { name: 'Shape' }).getByRole('button', { name: 'Round' }).click();
  await expect.poll(async () => srcdoc(page)).toContain('client overrides {"shape":"round"}');
  expect(renders(api).at(-1)).toMatchObject({ overrides: { shape: 'round' } });
});

test('a first-party app has no appearance block, so no preview', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: stateWith() });
  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: /Web app/ }).click();
  await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
  await expect(page.locator('versola-client-consent-preview')).toHaveCount(0);
});
