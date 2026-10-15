import { expect, test, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { findRequest, loadAdminApp } from './fixtures';
import { formBundle, translations } from './form-harness';
import type { MockConfigState } from './mocks';

const formsPath = '/?view=forms&tenant=tenant-alpha';

const localesPath = '/?view=locales&tenant=tenant-alpha';

const credentialV1 = {
  id: 'credential',
  version: 1,
  active: true,
  style: '.form { color: red; }',
  jsSource: 'export const Form = () => null;',
  jsCompiled: 'document.getElementById("versola-form-root").textContent = "preview";',
  localizations: { en: { title: 'Sign in' }, ru: { title: 'Вход' } },
  properties: [
    { type: 'BooleanProperty' as const, name: 'showRemember' },
    { type: 'StringArrayProperty' as const, name: 'mode', allowedValues: ['email', 'phone'] },
  ],
};
const credentialV2 = { ...credentialV1, version: 2, active: false };

const locales = [
  { code: 'en', name: 'English' },
  { code: 'ru', name: 'Russian' },
];

const themes = [
  { id: 'default', css: 'body { background: #fff; }', tenantId: null },
  { id: 'dark', css: 'body { background: #000; }', tenantId: null },
];

const fullState: Partial<MockConfigState> = {
  forms: [credentialV1, credentialV2],
  formLocales: locales,
  locales: locales.map(l => ({ ...l, active: true, isDefault: l.code === 'en' })),
  themes,
};

function formHeader(page: Page, text: string) {
  return page.locator('.form-header').filter({ hasText: text });
}

async function expandCredential(page: Page) {
  await formHeader(page, 'credential').click();
}

test('renders the forms, and no Themes section: themes have a page of their own', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: fullState });

  await expect(page.locator('.form-id').filter({ hasText: 'credential' })).toBeVisible();
  await expect(page.locator('.form-id').filter({ hasText: 'Themes' })).toHaveCount(0);
});

test('expands a form and shows versions with active badge', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandCredential(page);

  await expect(page.locator('.version-badge', { hasText: 'v1' })).toBeVisible();
  await expect(page.locator('.version-badge', { hasText: 'v2' })).toBeVisible();
  await expect(page.locator('.active-bubble')).toHaveCount(1);
});

test('sets a non-active version as active', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandCredential(page);
  await page.getByRole('button', { name: 'Set Active', exact: true }).click();
  await page.waitForTimeout(300);

  expect((await findRequest(api.requests, 'PUT', '/configuration/forms/active')).body).toEqual({
    id: 'credential',
    version: 2,
  });
});

test('creates a new form', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: { forms: [], formLocales: locales, themes } });

  await page.getByRole('button', { name: 'New Form', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'New Form', exact: true })).toBeVisible();
  await page.getByPlaceholder('e.g. credential').fill('signup');
  await page.getByRole('button', { name: 'Save as new version', exact: true }).click();
  await page.waitForTimeout(300);

  expect((await findRequest(api.requests, 'PUT', '/configuration/forms')).body).toMatchObject({ id: 'signup' });
});

test('creates a new version of an existing form', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandCredential(page);
  await page.getByRole('button', { name: 'New Version', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'New Version', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Save as new version', exact: true }).click();
  await page.waitForTimeout(300);

  expect((await findRequest(api.requests, 'PUT', '/configuration/forms')).body).toMatchObject({ id: 'credential' });
});

test('edits locales: adds a new locale', async ({ page }) => {
  const api = await loadAdminApp(page, { path: localesPath, state: fullState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Edit Locales', exact: true })).toBeVisible();

  await page.locator('select').selectOption('de');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = (await findRequest(api.requests, 'PUT', '/configuration/locales')).body as {
    add: Array<{ code: string; name: string }>;
    delete: string[];
  };
  expect(body.add.find(l => l.code === 'de')).toMatchObject({ code: 'de', name: 'Deutsch' });
  expect(body.delete).toEqual([]);
});

test('edits locales: removes an existing locale', async ({ page }) => {
  const api = await loadAdminApp(page, { path: localesPath, state: fullState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByRole('button', { name: 'Remove ru', exact: true }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = (await findRequest(api.requests, 'PUT', '/configuration/locales')).body as { delete: string[] };
  expect(body.delete).toEqual(['ru']);
});

test('activates a locale through the locales update endpoint', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: localesPath,
    state: {
      locales: [
        { code: 'en', name: 'English', isDefault: true, active: true },
        { code: 'ru', name: 'Russian', isDefault: false, active: false },
      ],
    },
  });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByRole('button', { name: 'Inactive', exact: true }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = (await findRequest(api.requests, 'PUT', '/configuration/locales')).body as {
    add: Array<{ code: string; active: boolean }>;
  };
  expect(body.add.find(locale => locale.code === 'ru')).toMatchObject({ code: 'ru', active: true });
});

test('shows missing localized fields when locale activation is rejected', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: localesPath,
    state: {
      locales: [
        { code: 'en', name: 'English', isDefault: true, active: true },
        { code: 'ru', name: 'Russian', isDefault: false, active: false },
      ],
      localeActivationMissing: {
        ru: ["client 'web' name", "scope 'profile' description"],
      },
    },
  });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByRole('button', { name: 'Inactive', exact: true }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  await expect(page.getByRole('heading', { name: 'Edit Locales', exact: true })).toBeVisible();
  await expect(page.getByText("Missing: client 'web' name, scope 'profile' description", { exact: false })).toBeVisible();
  const body = (await findRequest(api.requests, 'PUT', '/configuration/locales')).body as {
    add: Array<{ code: string; active: boolean }>;
  };
  expect(body.add.find(locale => locale.code === 'ru')).toMatchObject({ code: 'ru', active: true });
});

test('renders a live preview iframe', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: fullState });

  await expandCredential(page);
  await page.locator('.version-card').filter({ hasText: 'v1' }).click();
  await page.getByRole('button', { name: 'Preview', exact: true }).click();

  await expect(page.locator('iframe.preview-iframe')).toHaveCount(1);
});

test('keeps authorization form cards vertical', async ({ page }) => {
  const commonCss = await readFile(new URL('../forms/common.css', import.meta.url), 'utf8');
  await page.setContent(`<style>${commonCss}</style><div class="container"></div>`);

  const dimensions = await page.locator('.container').evaluate(element => ({
    width: element.getBoundingClientRect().width,
    height: element.getBoundingClientRect().height,
    display: getComputedStyle(element).display,
    justifyContent: getComputedStyle(element).justifyContent,
  }));

  expect(dimensions.height).toBeGreaterThan(dimensions.width);
  expect(dimensions.display).toBe('flex');
  expect(dimensions.justifyContent).toBe('center');
});

const credentialStates = {
  formId: 'credential',
  scenarios: [
    { id: 'email-password', title: { en: 'Email and password', ru: 'Email и пароль' }, step: { type: 'credential', primaryCredentials: ['email'], inlinePassword: true, passkey: false, registration: false } },
    { id: 'wrong-password', title: { en: 'Wrong password' }, step: { type: 'credential', primaryCredentials: ['login'], inlinePassword: false, passkey: false, registration: false }, error: 'login_failed' },
  ],
};

const previewSrcdoc = (page: Page) => page.locator('iframe.preview-iframe').evaluate((frame: HTMLIFrameElement) => frame.srcdoc);

test('previews a built-in form from the states central lists, not from backend properties', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: { ...fullState, formPreviews: [credentialStates] } });
  await expandCredential(page);
  await page.locator('.version-card').filter({ hasText: 'v1' }).click();

  await expect(page.getByRole('group', { name: 'Preview state' }).getByRole('button')).toHaveText(['Email and password', 'Wrong password']);
  // Its backend properties no longer drive the preview, so their controls are gone.
  await expect(page.locator('.ctrl-label', { hasText: 'Show Remember' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Preview', exact: true }).click();
  expect(await previewSrcdoc(page)).toContain('"primaryCredentials":["email"]');

  await page.getByRole('button', { name: 'Wrong password' }).click();
  const srcdoc = await previewSrcdoc(page);
  expect(srcdoc).toContain('"primaryCredentials":["login"]');
  expect(srcdoc).toContain('"error":"login_failed"');
});

test('the preview names languages as configured under Locales, not by their codes', async ({ page }) => {
  const names = [
    { code: 'en', name: 'English (US)', isDefault: true, active: true },
    { code: 'ru', name: 'Русский язык', isDefault: false, active: true },
  ];
  const password = {
    ...credentialV1,
    id: 'password',
    jsCompiled: await formBundle('password'),
    localizations: await translations('password'),
    properties: [],
  };
  await loadAdminApp(page, { path: formsPath, state: { forms: [password], formLocales: names, locales: names, themes } });
  await formHeader(page, 'password').click();
  await page.locator('.version-card').filter({ hasText: 'v1' }).click();
  await page.getByRole('button', { name: 'Preview', exact: true }).click();

  const trigger = page.frameLocator('iframe.preview-iframe').locator('.locale-trigger');
  await expect(trigger).toHaveText('English (US)');
  await trigger.click();
  await expect(page.frameLocator('iframe.preview-iframe').getByRole('button', { name: 'Русский язык', exact: true })).toBeVisible();
});

test('previews a form central lists nothing for from its own backend properties', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: { ...fullState, formPreviews: [{ formId: 'consent', scenarios: [] }] } });
  await expandCredential(page);
  await page.locator('.version-card').filter({ hasText: 'v1' }).click();

  await expect(page.getByRole('group', { name: 'Preview state' })).toHaveCount(0);
  await expect(page.locator('.ctrl-label', { hasText: 'Show Remember' })).toBeVisible();
});

test('says so when central cannot list the states, instead of quietly previewing something else', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: { ...fullState, formPreviews: null } });

  await expect(page.getByText('Could not load forms')).toBeVisible();
});

test('the new-version editor previews a built-in form from the listed states and hides its property authoring', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: { ...fullState, formPreviews: [credentialStates] } });
  await expandCredential(page);
  await page.getByRole('button', { name: 'New Version', exact: true }).click();

  await expect(page.getByRole('group', { name: 'Preview state' }).getByRole('button')).toHaveText(['Email and password', 'Wrong password']);
  await expect(page.getByText('Backend Properties', { exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: 'Preview', exact: true }).click();
  await page.getByRole('button', { name: 'Wrong password' }).click();
  expect(await previewSrcdoc(page)).toContain('"error":"login_failed"');
});

test('the new-version editor keeps the property authoring for a form with no listed states', async ({ page }) => {
  await loadAdminApp(page, { path: formsPath, state: fullState });
  await expandCredential(page);
  await page.getByRole('button', { name: 'New Version', exact: true }).click();

  await expect(page.getByText('Backend Properties', { exact: true })).toBeVisible();
  await expect(page.getByRole('group', { name: 'Preview state' })).toHaveCount(0);
});

test('saving a new version of a built-in form keeps its stored backend properties', async ({ page }) => {
  const api = await loadAdminApp(page, { path: formsPath, state: { ...fullState, formPreviews: [credentialStates] } });
  await expandCredential(page);
  await page.getByRole('button', { name: 'New Version', exact: true }).click();
  await page.getByRole('button', { name: 'Save as new version', exact: true }).click();
  await page.waitForTimeout(300);

  expect((await findRequest(api.requests, 'PUT', '/configuration/forms')).body).toMatchObject({ properties: credentialV1.properties });
});
