/**
 * Generates the screenshots of the Forms and Themes screens and of the login screens they style, for the
 * public documentation (versola-website: docs/console/forms, themes and system-settings). Like
 * docs-screenshots.spec.ts it is not an assertion suite: each scene writes a dark `name.png` and a light
 * `name-light.png` under `docs-screenshots/`, to be copied into versola-website/public/img/docs/.
 * The console runs against the mocked API with realistic example.com data, the forms are the real
 * compiled forms, and the preview stylesheet is built here the way central builds it.
 *
 * Run with: npx playwright test docs-screenshots-branding
 */
import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { loadAdminApp } from './fixtures';
import { renderForm } from './form-harness';

const outputDir = 'docs-screenshots';
const tenant = 'tenant-alpha';
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const golden = JSON.parse(read('../../central/src/test/resources/form-previews.golden.json')) as {
  previews: Array<{ formId: string; scenarios: Array<{ id: string; title: Record<string, string>; step: Record<string, unknown>; error?: string }> }>;
};

const presets = [
  { id: 'versola', title: { en: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto' } },
  { id: 'studio', title: { en: 'Studio' }, tokens: { brand: '#ff6b4a', shape: 'round', surface: 'bold', mode: 'auto' } },
  { id: 'fintech', title: { en: 'Fintech' }, tokens: { brand: '#5b5bd6', shape: 'soft', surface: 'neutral', mode: 'auto' } },
  { id: 'wellness', title: { en: 'Wellness' }, tokens: { brand: '#0e9f8e', shape: 'round', surface: 'soft', mode: 'auto' } },
  { id: 'editorial', title: { en: 'Editorial' }, tokens: { brand: '#14130f', shape: 'sharp', surface: 'neutral', mode: 'auto' } },
  { id: 'sunny', title: { en: 'Sunny' }, tokens: { brand: '#f5c518', shape: 'soft', surface: 'bold', mode: 'auto' } },
  { id: 'berry', title: { en: 'Berry' }, tokens: { brand: '#b4348a', shape: 'round', surface: 'bold', mode: 'auto' } },
];

type Tokens = { brand: string; shape: string; surface: string; mode: string };

// ThemeCss.generate, as central writes it.
function generate(t: Tokens): string {
  const channel = (value: number) => { const u = value / 255; return u <= 0.03928 ? u / 12.92 : ((u + 0.055) / 1.055) ** 2.4; };
  const lum = (hex: string) => 0.2126 * channel(parseInt(hex.slice(1, 3), 16)) + 0.7152 * channel(parseInt(hex.slice(3, 5), 16)) + 0.0722 * channel(parseInt(hex.slice(5, 7), 16));
  const contrast = (a: number, b: number) => (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  const own = lum(t.brand);
  const onBrand = contrast(own, 1) >= contrast(own, lum('#1c1b19')) ? '#ffffff' : '#1c1b19';
  const [field, button, card] = ({ round: ['18px', '999px', '32px'], soft: ['14px', '14px', '24px'], sharp: ['4px', '4px', '6px'] } as Record<string, string[]>)[t.shape];
  const surface = t.surface === 'soft'
    ? '--page-ink:light-dark(#1c1b19,#f3f2f0);--page-light:color-mix(in oklab,var(--brand) 14%,#f7f4ee);--page-dark:color-mix(in oklab,var(--brand) 7%,#0e0e0d);--glow-dark:color-mix(in oklab,var(--brand) 16%,transparent);'
    : t.surface === 'neutral' ? '--page-ink:light-dark(#1c1b19,#f3f2f0);--page-light:#ecebe7;--page-dark:#0b0b0a;--glow-dark:transparent;' : '';
  const scheme = t.mode === 'auto' ? '' : `color-scheme:${t.mode};`;
  return `:root{--brand:${t.brand};--on-brand:${onBrand};--rf:${field};--rb:${button};--rs:${card};${surface}${scheme}}`;
}

const resolve = (tokens: { base: string; overrides?: Partial<Tokens> }): Tokens =>
  ({ ...presets.find(p => p.id === tokens.base)!.tokens, ...(tokens.overrides ?? {}) });

const forms = golden.previews.map(p => p.formId);
const compiled = new Map<string, string>();
async function activeForms() {
  const { build } = await import('esbuild');
  const { solidPlugin } = await import('esbuild-plugin-solid');
  const out = [];
  for (const id of forms) {
    if (!compiled.has(id)) {
      const result = await build({ entryPoints: [new URL(`../forms/${id}/${id}.tsx`, import.meta.url).pathname], bundle: true, write: false, format: 'iife', minify: true, target: 'es2019', plugins: [solidPlugin()] });
      compiled.set(id, result.outputFiles[0].text);
    }
    out.push({ id, version: 1, active: true, style: read(`../forms/${id}/${id}.css`), jsSource: null, jsCompiled: compiled.get(id)!, localizations: JSON.parse(read(`../forms/${id}/${id}.i18n.json`)), properties: [] });
  }
  return out;
}

const themes = [
  { id: 'default', css: '', tenantId: null, tokens: { base: 'versola' } },
  { id: 'legacy-acme', css: 'body { font-family: Georgia, serif; }', tenantId: null },
  { id: 'acme', css: '', tenantId: null, tokens: { base: 'fintech', overrides: { brand: '#2563eb' } } },
  { id: 'storefront', css: '', tenantId: tenant, tokens: { base: 'studio', overrides: { brand: '#b4348a', shape: 'sharp' } } },
];

async function openConsole(page: Page, scheme: 'dark' | 'light', path: string) {
  await page.addInitScript(value => localStorage.setItem('versola-theme', value), scheme);
  await page.setViewportSize({ width: 1440, height: 900 });
  await loadAdminApp(page, {
    path,
    state: {
      forms: await activeForms(),
      formLocales: [{ code: 'en', name: 'English' }, { code: 'ru', name: 'Russian' }],
      locales: [{ code: 'en', name: 'English', active: true, isDefault: true }, { code: 'ru', name: 'Russian', active: true, isDefault: false }],
      formPreviews: golden.previews,
      themePresets: presets,
      themes,
    },
  });
  // The preview's stylesheet is what central would build for the tokens.
  await page.route('**/configuration/themes/css', async route => {
    const { tokens, css } = route.request().postDataJSON() as { tokens: { base: string; overrides?: Partial<Tokens> }; css?: string };
    const generated = generate(resolve(tokens));
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ css: [read('../forms/common.css'), generated, css ?? ''].filter(Boolean).join('\n'), generated }) });
  });
}

async function shoot(page: Page, name: string, scheme: 'dark' | 'light', fullPage = false) {
  if (fullPage) {
    const height = await page.evaluate(() => document.documentElement.scrollHeight);
    await page.setViewportSize({ width: 1440, height: Math.max(900, height) });
  }
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${outputDir}/console/${name}${scheme === 'light' ? '-light' : ''}.png`, fullPage });
}

for (const scheme of ['dark', 'light'] as const) {
  test(`forms ${scheme}`, async ({ page }) => {
    await openConsole(page, scheme, `/?view=forms&tenant=${tenant}`);
    await expect(page.locator('.form-id').filter({ hasText: 'consent' })).toBeVisible();
    await shoot(page, 'forms-list', scheme);

    await page.locator('.form-header').filter({ hasText: 'consent' }).click();
    await page.locator('.version-card').filter({ hasText: 'v1' }).click();
    await page.getByRole('button', { name: 'Preview', exact: true }).click();
    await page.getByRole('button', { name: 'Everything requested' }).click();
    await page.getByRole('button', { name: 'Mobile', exact: true }).click();
    await page.locator('.version-card').scrollIntoViewIfNeeded();
    await expect(page.getByRole('group', { name: 'Preview state' })).toBeVisible();
    await shoot(page, 'forms-list-expanded', scheme, true);
  });

  test(`new version of a form ${scheme}`, async ({ page }) => {
    await openConsole(page, scheme, `/?view=forms&tenant=${tenant}`);
    await page.locator('.form-header').filter({ hasText: 'consent' }).click();
    await page.getByRole('button', { name: 'New Version', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'New Version', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Preview', exact: true }).click();
    await shoot(page, 'form-edit', scheme, true);
  });

  test(`themes ${scheme}`, async ({ page }) => {
    await openConsole(page, scheme, `/?view=themes&tenant=${tenant}`);
    await expect(page.getByRole('button', { name: 'Edit theme storefront', exact: true })).toBeVisible();
    await shoot(page, 'themes', scheme);

    await page.getByRole('button', { name: '+ New Theme', exact: true }).click();
    await page.getByPlaceholder('e.g. default').fill('acme-web');
    await page.getByRole('button', { name: 'Fintech' }).click();
    await page.getByRole('button', { name: 'Mobile', exact: true }).click();
    await expect.poll(() => page.locator('iframe.preview-iframe').evaluate((f: HTMLIFrameElement) => f.srcdoc)).toContain('--brand:#5b5bd6');
    await shoot(page, 'theme-new', scheme, true);
  });

  test(`editing a theme ${scheme}`, async ({ page }) => {
    await openConsole(page, scheme, `/?view=themes&tenant=${tenant}`);
    await page.getByRole('button', { name: 'Edit theme storefront', exact: true }).click();
    await page.locator('#preview-form').selectOption('consent');
    await expect(page.getByText('2 overrides on Studio')).toBeVisible();
    await expect.poll(() => page.locator('iframe.preview-iframe').evaluate((f: HTMLIFrameElement) => f.srcdoc)).toContain('--brand:#b4348a');
    await shoot(page, 'theme-edit', scheme, true);
  });
}

// A partner app's own appearance on the client page (docs: entities/client). The client is the mock's, so the
// screenshot shows the mocked API, not a staged stack.
for (const scheme of ['dark', 'light'] as const) {
  test(`client appearance ${scheme}`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), scheme);
    await page.setViewportSize({ width: 1440, height: 900 });
    await loadAdminApp(page, {
      path: `/?view=clients&tenant=${tenant}`,
      state: {
        clients: {
          [tenant]: [{
            id: 'acme-wallet',
            clientName: { en: 'Acme Wallet' },
            redirectUris: ['https://wallet.example.com/callback'],
            scope: ['openid', 'profile'],
            permissions: [],
            secretRotation: false,
            accessTokenTtl: 3600,
            theme: 'acme',
            authFlow: { primary: { credentials: ['phone'], inlinePassword: false, factors: [{ type: 'otp', required: true }] }, passkey: null, otpType: 'sms' },
            consentFlow: { allowPartial: false, rememberDuration: 15552000 },
            themeOverrides: { brand: '#b4348a', shape: 'sharp' },
          }],
        },
        themePresets: presets,
        themes,
      },
    });
    await page.getByRole('button', { name: 'Open client acme-wallet', exact: true }).click();
    await page.getByRole('button', { name: 'Edit branding and logout', exact: true }).click();
    const block = page.getByRole('region', { name: 'Appearance for this app' });
    await expect(block.getByText('Overridden · base: #2563eb')).toBeVisible();
    await page.waitForTimeout(500);
    await page.screenshot({ path: `${outputDir}/entities/client-appearance${scheme === 'light' ? '-light' : ''}.png` });
  });
}

// The login screens the themes style, rendered by the real forms. Dark and light are the device's scheme.
const kz = { region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}' };
const partnerLogo = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" rx="14" fill="%23b4348a"/><text x="32" y="43" font-size="30" font-weight="800" text-anchor="middle" fill="white" font-family="sans-serif">S</text></svg>';
const idpLogo = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="140" height="40"><text x="0" y="30" font-size="30" font-weight="800" fill="%231c1b19" font-family="Georgia,serif" font-style="italic">Acme ID</text></svg>';
const scenario = (formId: string, id: string) => golden.previews.find(p => p.formId === formId)!.scenarios.find(s => s.id === id)!;

async function loginShot(page: Page, scheme: 'dark' | 'light', name: string, formId: string, step: Record<string, unknown>, opts: { tokens?: Tokens; logo?: string; error?: string; act?: (p: Page) => Promise<void>; width?: number }) {
  await page.emulateMedia({ colorScheme: scheme });
  // A login screen is a narrow card: a small window keeps it legible at the docs' image width.
  await page.setViewportSize({ width: opts.width ?? 800, height: opts.width ? 844 : 860 });
  await renderForm(page, formId, { step, ...(opts.logo ? { logo: opts.logo } : {}), ...(opts.error ? { error: opts.error } : {}) }, opts.tokens ? { extraCss: generate(opts.tokens) } : {});
  await opts.act?.(page);
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${outputDir}/login/${name}${scheme === 'light' ? '-light' : ''}.png` });
}

for (const scheme of ['dark', 'light'] as const) {
  test(`login screens ${scheme}`, async ({ page }) => {
    const phone = { type: 'credential', primaryCredentials: ['phone'], passkey: true, phoneRegions: [kz] };
    await loginShot(page, scheme, 'sign-in', 'credential', phone, { act: p => p.locator('.phone-input').fill('7012345678') });
    await loginShot(page, scheme, 'sign-in-brand', 'credential', phone, { tokens: resolve({ base: 'studio', overrides: { brand: '#b4348a', shape: 'sharp' } }), act: p => p.locator('.phone-input').fill('7012345678') });
    await loginShot(page, scheme, 'sign-in-idp-logo', 'credential', phone, { logo: idpLogo, act: p => p.locator('.phone-input').fill('7012345678') });
    await loginShot(page, scheme, 'code', 'otp', scenario('otp', 'resend-timer').step, { act: p => p.keyboard.type('482') });
    await loginShot(page, scheme, 'consent', 'consent', { ...scenario('consent', 'typical').step, clientName: 'Storefront', logoUri: partnerLogo, user: { name: 'Anna Petrova' } }, {});
    await loginShot(page, scheme, 'consent-everything', 'consent', { ...scenario('consent', 'everything').step, clientName: 'Storefront', logoUri: partnerLogo, user: { name: 'Anna Petrova' } }, { width: 390 });
  });
}
