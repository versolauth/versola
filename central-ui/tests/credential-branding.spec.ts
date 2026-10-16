import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';

const centralUiDir = fileURLToPath(new URL('..', import.meta.url));
const credentialBundle = fileURLToPath(
  new URL('../../central/src/main/resources/forms/credential.js', import.meta.url),
);

test.describe.configure({ mode: 'serial' });

const baseConfig = {
  step: {
    type: 'credential',
    primaryCredentials: ['email'],
    inlinePassword: false,
    passkey: false,
  },
  t: { title: 'Sign in', email_placeholder: 'Email', continue: 'Continue' },
  locale: 'en',
  locales: [],
  allT: {},
  csrf: '',
};

test.beforeAll(() => {
  execFileSync(process.execPath, ['scripts/build-forms.mjs'], { cwd: centralUiDir, stdio: 'ignore' });
});

async function renderCredential(page: import('@playwright/test').Page, logo?: string) {
  await page.setContent('<div id="versola-form-root"></div>');
  await page.evaluate(config => {
    (window as Window & { __VERSOLA_FORM__?: unknown }).__VERSOLA_FORM__ = config;
  }, { ...baseConfig, logo });
  await page.addScriptTag({ path: credentialBundle });
}

test('renders the Versola wordmark when no logo is configured', async ({ page }) => {
  await renderCredential(page);

  await expect(page.locator('.page-brand .page-wordmark')).toHaveCount(1);
  await expect(page.locator('.page-brand img')).toHaveCount(0);
});

test('replaces a configured logo with the wordmark when the image fails to load', async ({ page }) => {
  await page.route('https://assets.example/broken.svg', route => route.abort());
  await renderCredential(page, 'https://assets.example/broken.svg');

  await expect(page.locator('.page-brand img')).toHaveCount(0);
  await expect(page.locator('.page-brand .page-wordmark')).toHaveCount(1);
});