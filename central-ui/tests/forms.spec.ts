import { expect, test, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { findRequest, loadAdminApp } from './fixtures';
import { formBundle, translations } from './form-harness';
import { ftForms, ftLocales, ftPath, ftState } from './forms-themes-fixtures';
import { baseStylesheetMock, type MockConfigState } from './mocks';

const listPath = ftPath('forms');
const recordPath = (id: string, edit?: string) => ftPath('forms', `&id=${id}${edit ? `&edit=${edit}` : ''}`);
const readOnly = { resources: { central: { permissions: ['forms:read'] } }, isProd: false };

const formRow = (page: Page, id: string) => page.getByRole('row').filter({ has: page.getByRole('button', { name: `Open form ${id}` }) });
const srcdoc = (page: Page) => page.locator('iframe.preview-iframe').evaluate((frame: HTMLIFrameElement) => frame.srcdoc);
const crumb = (page: Page, name: string) => page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name });
const tab = (page: Page, name: string) => page.getByRole('tab', { name });
const putForms = (requests: Array<{ method: string; pathname: string; body?: unknown }>) =>
  requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/forms');

test.describe('list', () => {
  test('is a table: form, kind, locales, active version; no Used by column and no counters', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: ftState() });

    await expect(page.getByRole('heading', { level: 1, name: 'Forms' })).toBeVisible();
    await expect(page.getByRole('columnheader')).toHaveText(['Form', 'Kind', 'Locales', 'Active version', 'Actions']);
    await expect(page.getByRole('button', { name: '+ Create form' })).toHaveCount(1);
    await expect(page.getByRole('group', { name: 'Filter forms' }).getByRole('button')).toHaveText(['All', 'Built-in', 'Custom', 'Missing translations']);
    // One row per form, not per stored version.
    await expect(page.getByRole('row').filter({ has: page.getByRole('button', { name: /^Open form/ }) })).toHaveCount(7);
  });

  test('says built-in or custom from the states central lists, and the languages a form lacks', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: ftState() });

    await expect(formRow(page, 'consent')).toContainText('Built-in');
    await expect(formRow(page, 'support-contact')).toContainText('Custom');
    // Complete in en, ru and kk: two chips and +1, which names the rest.
    await expect(formRow(page, 'consent').locator('.chip.value')).toHaveText(['en', 'ru']);
    await expect(formRow(page, 'consent').locator('.chip.more')).toHaveText('+1');
    await expect(formRow(page, 'consent').locator('.chip.more')).toHaveAttribute('title', 'kk');
    // A gap is named, not counted.
    await expect(formRow(page, 'set-password').locator('.chip.warn')).toHaveText('Missing: kk');
    await expect(formRow(page, 'kyc-upload').locator('.chip.warn')).toHaveText('Missing: ru, kk');
    await expect(formRow(page, 'credential').locator('.chip.warn').first()).toHaveText('Missing: ru, kk');
  });

  test('shows the active version and warns when a newer one is stored', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: ftState() });

    await expect(formRow(page, 'credential')).toContainText('v7');
    await expect(formRow(page, 'credential')).toContainText('v8 not active');
    await expect(formRow(page, 'password')).toContainText('v4');
    await expect(formRow(page, 'password')).not.toContainText('not active');
  });

  test('says when a form has no active version at all', async ({ page }) => {
    const forms = ftForms().map(form => (form.id === 'otp' ? { ...form, active: false } : form));
    await loadAdminApp(page, { path: listPath, state: { ...ftState(), forms } });

    await expect(formRow(page, 'otp').locator('.chip.warn')).toHaveText('No active version');
  });

  test('filters by kind and by missing translations, and searches by ID', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: ftState() });
    const filter = page.getByRole('group', { name: 'Filter forms' });
    const rows = page.getByRole('row').filter({ has: page.getByRole('button', { name: /^Open form/ }) });

    await filter.getByRole('button', { name: 'Built-in' }).click();
    await expect(rows).toHaveCount(5);
    await filter.getByRole('button', { name: 'Custom' }).click();
    await expect(rows).toHaveCount(2);
    await filter.getByRole('button', { name: 'Missing translations' }).click();
    await expect(rows).toHaveCount(3);
    await expect(formRow(page, 'password')).toHaveCount(0);

    await filter.getByRole('button', { name: 'All' }).click();
    await page.getByRole('searchbox', { name: 'Search forms' }).fill('pass');
    await expect(rows).toHaveCount(2);
    await page.getByRole('searchbox', { name: 'Search forms' }).fill('zzz');
    await expect(page.getByRole('heading', { name: 'No forms match' })).toBeVisible();
  });

  test('a row opens the form: by click, by its link and from the row menu', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: ftState() });

    await formRow(page, 'password').click();
    await expect(page.getByRole('heading', { level: 1, name: 'password' })).toBeVisible();
    await expect(page).toHaveURL(/id=password/);

    await crumb(page, 'Forms').click();
    await formRow(page, 'otp').getByRole('button', { name: 'Open form otp' }).press('Enter');
    await expect(page.getByRole('heading', { level: 1, name: 'otp' })).toBeVisible();

    await crumb(page, 'Forms').click();
    await page.getByRole('button', { name: 'Actions for consent' }).click();
    await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy ID']);
    await page.getByRole('menuitem', { name: 'Open' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'consent' })).toBeVisible();
  });

  test('is a stack of cards at 375 and never scrolls sideways', async ({ page }) => {
    for (const width of [1440, 1024, 375]) {
      await page.setViewportSize({ width, height: 900 });
      await loadAdminApp(page, { path: listPath, state: ftState() });
      await expect(formRow(page, 'credential')).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    }
  });

  test('says so when there are no forms, offering to create the first', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: { ...ftState(), forms: [] } });

    await expect(page.getByRole('heading', { name: 'No forms yet' })).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Create form' })).toHaveCount(2);
  });

  test('says so when central cannot list the states, instead of quietly previewing something else', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: { ...ftState(), formPreviews: null } });

    await expect(page.getByText('Could not load forms')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
  });

  test('without the manage permission it lists and opens forms but offers no way to change them', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: { ...ftState(), myPermissions: readOnly } });

    await expect(formRow(page, 'credential')).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Create form' })).toHaveCount(0);
    await formRow(page, 'credential').click();
    await expect(page.getByRole('button', { name: 'Save as new version' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'Versions' })).toBeVisible();
    await page.getByRole('tab', { name: 'Versions' }).click();
    await expect(page.getByRole('button', { name: /^Set active/ })).toHaveCount(0);
  });

  test('a form that was never stored says so', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('nope'), state: ftState() });

    await expect(page.getByRole('heading', { name: 'Form not found' })).toBeVisible();
    await page.getByRole('button', { name: 'Back to forms' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Forms' })).toBeVisible();
  });
});

test.describe('record', () => {
  test('has a header with the ID, kind, active version and languages, one primary action and no delete', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });

    await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Forms');
    await expect(page.getByRole('heading', { level: 1, name: 'credential' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Copy form ID' })).toBeVisible();
    const header = page.locator('versola-record-header');
    await expect(header).toContainText('Built-in');
    await expect(header).toContainText('Active v7');
    await expect(header).toContainText('v8 not active');
    await expect(header.locator('.chip.warn').last()).toHaveText('Missing: ru, kk');
    await expect(page.getByRole('button', { name: 'Save as new version' })).toHaveCount(1);
    // A form has no delete: no danger card.
    await expect(page.getByRole('button', { name: /^Delete/ })).toHaveCount(0);
    await expect(page.locator('versola-danger-card')).toHaveCount(0);
  });

  test('says what saving does: a new version, not activated', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });

    const note = page.getByRole('note');
    await expect(note).toContainText('Editing a copy of v8');
    await expect(note).toContainText('Saving stores v9 without activating it');
    await expect(note).toContainText('Users keep v7');
  });

  test('has tabs Texts, Style, Script and Versions, and Properties only for a custom form', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });
    await expect(page.getByRole('tab')).toHaveText(['Texts', 'Style', 'Script', 'Versions']);
    await expect(tab(page, 'Texts')).toHaveAttribute('aria-selected', 'true');

    await loadAdminApp(page, { path: recordPath('support-contact'), state: ftState() });
    await expect(page.getByRole('tab')).toHaveText(['Texts', 'Style', 'Script', 'Properties', 'Versions']);
  });

  test('a tab is in the URL, opens from it and moves with the arrow keys', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential', 'style'), state: ftState() });
    await expect(tab(page, 'Style')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('tabpanel')).toContainText('Applied to this form only');

    await tab(page, 'Style').press('ArrowRight');
    await expect(tab(page, 'Script')).toHaveAttribute('aria-selected', 'true');
    await expect(page).toHaveURL(/edit=script/);
    await expect(tab(page, 'Script')).toBeFocused();
    await tab(page, 'Texts').click();
    await expect(page).not.toHaveURL(/edit=/);
  });

  test('keeps a live preview beside every tab', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });
    for (const name of ['Texts', 'Style', 'Script', 'Versions']) {
      await tab(page, name).click();
      await expect(page.locator('iframe.preview-iframe')).toBeVisible();
    }
  });

  test('a read-only record shows the fields but cannot change them', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('password'), state: { ...ftState(), myPermissions: readOnly } });

    await expect(page.getByRole('textbox', { name: 'title', exact: true })).toHaveAttribute('readonly', '');
    await expect(page.getByRole('note')).toHaveCount(0);
  });
});

test.describe('texts', () => {
  test('stacks each key with its English reference and a field, under group headers', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });
    await page.getByRole('button', { name: 'Русский' }).click();

    await expect(page.getByRole('heading', { level: 3 })).toHaveText(['Page', 'Fields', 'Buttons', 'Errors']);
    const entry = page.locator('.entry[data-key="continue"]');
    await expect(entry.locator('.key')).toHaveText('continue');
    await expect(entry.locator('.ref')).toContainText('Continue');
    await expect(page.getByRole('textbox', { name: 'continue', exact: true })).toHaveValue('Продолжить');
  });

  test('the reference language shows no reference line', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });

    await expect(page.locator('.entry .ref')).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: 'continue', exact: true })).toHaveValue('Continue');
  });

  test('a missing translation is a warning field that says it is shown in English', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });
    await page.getByRole('button', { name: 'Русский' }).click();

    const entry = page.locator('.entry[data-key="passkey_button"]');
    await expect(entry).toHaveClass(/missing/);
    await expect(entry.locator('.chip.warn')).toHaveText('Missing in ru');
    await expect(entry.getByRole('textbox')).toHaveAttribute('placeholder', 'Shown in English until translated');
    await expect(page.locator('.entry[data-key="continue"]')).not.toHaveClass(/missing/);
  });

  test('marks the languages that have gaps and names the first missing keys with +N', async ({ page }) => {
    const state = ftState();
    await loadAdminApp(page, { path: recordPath('kyc-upload'), state });
    await page.getByRole('button', { name: 'Русский' }).click();

    const languages = page.getByRole('group', { name: 'Language being edited' });
    await expect(languages.locator('.gap-dot')).toHaveCount(2);
    await expect(page.getByRole('status').filter({ hasText: 'Missing in ru' })).toHaveText('Missing in ru: intro, submit +1');
    await expect(page.getByRole('tab', { name: 'Texts' }).locator('.gap-dot')).toBeVisible();
  });

  test('Missing only keeps the keys the language lacks, and says when nothing is missing', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });
    await page.getByRole('button', { name: 'Русский' }).click();
    const entries = page.locator('.entry');
    const total = await entries.count();

    await page.getByLabel('Missing only').check();
    await expect(entries).toHaveCount(2);
    await expect(entries.locator('.key')).toHaveText(['phone_hint', 'passkey_button']);
    expect(total).toBeGreaterThan(2);

    await page.getByRole('button', { name: 'English' }).click();
    await expect(page.getByText('Nothing is missing in English.')).toBeVisible();
    await page.getByLabel('Missing only').uncheck();
    await expect(entries).toHaveCount(total);
  });

  test('editing a text updates the preview in the language being edited', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });
    await page.getByRole('button', { name: 'Русский' }).click();
    await expect.poll(() => srcdoc(page)).toContain('"locale":"ru"');

    await page.getByRole('textbox', { name: 'continue', exact: true }).fill('Дальше');
    await expect.poll(() => srcdoc(page)).toContain('"continue":"Дальше"');
  });

  test('a key the language lacks reaches the preview in English', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });
    await page.getByRole('button', { name: 'Русский' }).click();

    await expect.poll(() => srcdoc(page)).toContain('"passkey_button":"Sign in with Passkey"');
    await page.getByRole('textbox', { name: 'passkey_button', exact: true }).fill('Войти по ключу');
    await expect.poll(() => srcdoc(page)).toContain('"passkey_button":"Войти по ключу"');
  });

  test('the preview rings the key being typed in (accent) and the English text of each missing key (warning)', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('kyc-upload'), state: ftState() });
    await page.getByRole('button', { name: 'Русский' }).click();
    const frame = page.frameLocator('iframe.preview-iframe');
    await expect(frame.locator('h1')).toHaveText('Verify your identity');

    // Every missing key shows English, ringed in the warning colour.
    await expect(frame.locator('h1')).toHaveAttribute('data-vm', 'missing');
    await expect(frame.locator('button')).toHaveAttribute('data-vm', 'missing');

    // Typing the title gives it the accent ring; the others keep theirs.
    const field = page.getByRole('textbox', { name: 'title', exact: true });
    await field.focus();
    await field.fill('Подтвердите личность');
    await expect(frame.locator('h1')).toHaveText('Подтвердите личность');
    await expect(frame.locator('h1')).toHaveAttribute('data-vm', 'active');
    await expect(frame.locator('button')).toHaveAttribute('data-vm', 'missing');
    await page.getByRole('textbox', { name: 'intro', exact: true }).focus();
    await expect(frame.locator('h1')).not.toHaveAttribute('data-vm', 'active');
  });

  test('a form with no texts says so', async ({ page }) => {
    const forms = ftForms().map(form => (form.id === 'kyc-upload' ? { ...form, localizations: {} } : form));
    await loadAdminApp(page, { path: recordPath('kyc-upload'), state: { ...ftState(), forms } });

    await expect(page.getByText('This form has no texts yet.')).toBeVisible();
  });
});

test.describe('preview', () => {
  test('a built-in form is previewed from the states central lists, not from backend properties', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });

    const states = page.getByRole('group', { name: 'Preview state' }).getByRole('button');
    await expect(states.first()).toHaveText('Email and password');
    await expect(page.locator('.ctrl-label', { hasText: 'Show Phone' })).toHaveCount(0);
    expect(await srcdoc(page)).toContain('"primaryCredentials":["email"]');

    await page.getByRole('button', { name: 'Wrong password' }).click();
    await expect.poll(() => srcdoc(page)).toContain('"error":"login_failed"');
    await expect(page.getByRole('button', { name: 'Wrong password' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('a custom form is previewed from its own backend properties', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('support-contact'), state: ftState() });

    await expect(page.getByRole('group', { name: 'Preview state' })).toHaveCount(0);
    expect(await srcdoc(page)).toContain('"showPhone":false');
    await page.getByLabel('Show Phone').check();
    await expect.poll(() => srcdoc(page)).toContain('"showPhone":true');
    await page.getByRole('group', { name: 'Channel' }).getByRole('button', { name: 'sms' }).click();
    await expect.poll(() => srcdoc(page)).toContain('"channel":["email"]');
  });

  test('previews with the stylesheet central composes for the theme chosen, and at the width chosen', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });

    await expect.poll(() => srcdoc(page)).toContain(baseStylesheetMock);
    await page.getByLabel('Theme').selectOption('legacy-2024');
    await expect.poll(() => srcdoc(page)).toContain('font-family: Georgia');

    await expect(page.getByRole('button', { name: 'Mobile' })).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('button', { name: 'Desktop' }).click();
    await expect(page.locator('iframe.preview-iframe')).toHaveCSS('width', '1280px');
  });

  test('the preview names languages as configured under Locales, not by their codes', async ({ page }) => {
    const names = [
      { code: 'en', name: 'English (US)', isDefault: true, active: true },
      { code: 'ru', name: 'Русский язык', isDefault: false, active: true },
    ];
    const password = { ...ftForms().find(form => form.id === 'password')!, jsCompiled: await formBundle('password'), localizations: await translations('password') };
    await loadAdminApp(page, { path: recordPath('password'), state: { ...ftState(), forms: [password], locales: names } });

    const trigger = page.frameLocator('iframe.preview-iframe').locator('.locale-trigger');
    await expect(trigger).toHaveText('English (US)');
    await trigger.click();
    await expect(page.frameLocator('iframe.preview-iframe').getByRole('option', { name: 'Русский язык', exact: true })).toBeVisible();
  });

  test('switching the language inside the preview switches the language being edited', async ({ page }) => {
    const password = { ...ftForms().find(form => form.id === 'password')!, jsCompiled: await formBundle('password'), localizations: await translations('password') };
    await loadAdminApp(page, { path: recordPath('password'), state: { ...ftState(), forms: [password], locales: ftLocales.slice(0, 2) } });

    await page.frameLocator('iframe.preview-iframe').locator('.locale-trigger').click();
    await page.frameLocator('iframe.preview-iframe').getByRole('option', { name: 'Русский' }).click();
    await expect(page.getByRole('button', { name: 'Русский' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('keeps the authorization form card vertical', async ({ page }) => {
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

  test('is a collapsible band above the tabs below 900px, and a column beside them above', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });
    const pane = page.locator('versola-preview-pane');
    const editor = page.getByRole('region', { name: 'Form editor' });
    const wide = { pane: await pane.boundingBox(), editor: await editor.boundingBox() };
    expect(wide.pane!.x).toBeGreaterThan(wide.editor!.x + wide.editor!.width - 1);
    expect(wide.pane!.width).toBeGreaterThanOrEqual(499);

    await page.setViewportSize({ width: 600, height: 900 });
    const narrow = { pane: await pane.boundingBox(), editor: await editor.boundingBox() };
    expect(narrow.pane!.y).toBeLessThan(narrow.editor!.y);
    const toggle = pane.getByRole('button', { name: /Preview/ });
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(page.locator('iframe.preview-iframe')).toBeHidden();
  });

  test('is 400px wide at 1024', async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 900 });
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });

    const box = await page.locator('versola-preview-pane').boundingBox();
    expect(Math.round(box!.width)).toBe(400);
  });
});

test.describe('saving', () => {
  test('Save as new version sends the form with its edited texts, keeping what was not touched', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('support-contact'), state: ftState() });
    await page.getByRole('textbox', { name: 'title', exact: true }).fill('Reach support');
    await page.getByRole('button', { name: 'Save as new version' }).click();

    const body = (await findRequest(api.requests, 'PUT', '/configuration/forms')).body as Record<string, any>;
    expect(body.id).toBe('support-contact');
    expect(body.localizations.en.title).toBe('Reach support');
    expect(body.localizations.ru.title).toBe('Связаться с поддержкой');
    expect(body.style).toBe('.container { padding: 1.5rem; }');
    expect(body.jsSource).toBe('export const Form = () => null;');
    expect(body.properties).toEqual([{ type: 'BooleanProperty', name: 'showPhone' }, { type: 'StringArrayProperty', name: 'channel', allowedValues: ['sms', 'email'] }]);
    expect(putForms(api.requests)).toHaveLength(1);
  });

  test('says what was stored and that it is not active, with a way to the versions', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('password'), state: ftState() });
    await page.getByRole('button', { name: 'Save as new version' }).click();

    const banner = page.getByRole('status').filter({ hasText: 'Stored v5' });
    await expect(banner).toContainText('It is not active yet: users keep v4');
    await expect(page.locator('versola-record-header')).toContainText('v5 not active');
    await banner.getByRole('button', { name: 'Open versions' }).click();
    await expect(tab(page, 'Versions')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('.version-row').first()).toContainText('v5');
  });

  test('one click sends one request, and a failure is shown with the form kept', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('password'), state: ftState() });
    let puts = 0;
    await page.route('**/configuration/forms', async route => {
      if (route.request().method() !== 'PUT') return route.fallback();
      puts++;
      await new Promise(resolve => setTimeout(resolve, 300));
      return route.fulfill({ status: 400, contentType: 'application/json', body: '{"message":"Script does not compile"}' });
    });
    const save = page.getByRole('button', { name: 'Save as new version' });
    await save.dblclick();

    await expect(page.getByRole('alert').filter({ hasText: 'Script does not compile' })).toBeVisible();
    expect(puts).toBe(1);
    await expect(save).toBeEnabled();
  });

  test('the Style tab sends the per-form CSS', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('support-contact', 'style'), state: ftState() });
    await page.locator('versola-code-editor').evaluate((editor: HTMLElement) => {
      editor.dispatchEvent(new CustomEvent('code-input', { detail: { value: '.container { color: red; }' } }));
    });
    await expect.poll(() => srcdoc(page)).toContain('.container { color: red; }');
    await page.getByRole('button', { name: 'Save as new version' }).click();

    expect(((await findRequest(api.requests, 'PUT', '/configuration/forms')).body as Record<string, unknown>).style).toBe('.container { color: red; }');
  });

  test('the Script tab sends the source and keeps the compiled script', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('support-contact', 'script'), state: ftState() });
    await page.locator('versola-code-editor').evaluate((editor: HTMLElement) => {
      editor.dispatchEvent(new CustomEvent('code-input', { detail: { value: 'export const Form = () => "x";' } }));
    });
    await page.getByRole('button', { name: 'Save as new version' }).click();

    const body = (await findRequest(api.requests, 'PUT', '/configuration/forms')).body as Record<string, unknown>;
    expect(body.jsSource).toBe('export const Form = () => "x";');
    expect(String(body.jsCompiled)).toContain('__VERSOLA_FORM__');
  });

  test('saving a new version of a built-in form keeps its stored backend properties', async ({ page }) => {
    const forms = ftForms().map(form => (form.id === 'credential' ? { ...form, properties: [{ type: 'BooleanProperty' as const, name: 'showRemember' }] } : form));
    const api = await loadAdminApp(page, { path: recordPath('credential'), state: { ...ftState(), forms } });
    await page.getByRole('button', { name: 'Save as new version' }).click();

    expect(((await findRequest(api.requests, 'PUT', '/configuration/forms')).body as Record<string, unknown>).properties).toEqual([{ type: 'BooleanProperty', name: 'showRemember' }]);
  });

  test('leaving with unsaved edits asks first, and keeping them stays on the form', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('password'), state: ftState() });
    await page.getByRole('textbox', { name: 'title', exact: true }).fill('Changed');

    await crumb(page, 'Forms').click();
    await expect(page.getByRole('dialog')).toContainText('Discard changes?');
    await page.getByRole('button', { name: 'Keep editing' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'password' })).toBeVisible();

    await crumb(page, 'Forms').click();
    await page.getByRole('button', { name: 'Discard' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Forms' })).toBeVisible();
  });

  test('leaving an untouched form does not ask', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('password'), state: ftState() });
    await crumb(page, 'Forms').click();

    await expect(page.getByRole('heading', { level: 1, name: 'Forms' })).toBeVisible();
  });
});

test.describe('versions', () => {
  test('lists every stored version, the active one and the newer one that is not active', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential', 'versions'), state: ftState() });

    const rows = page.locator('.version-row');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0)).toContainText('v8');
    await expect(rows.nth(0).locator('.chip.warn')).toHaveText('Stored, not active');
    await expect(rows.nth(1).locator('.chip.ok')).toHaveText('Active');
    await expect(rows.nth(2).locator('.chip')).toHaveText('Older');
    const warning = page.getByRole('status').filter({ hasText: 'v8 is stored but not active' });
    await expect(warning).toContainText('Users still get v7');
  });

  test('Set active on a newer version sends the id and version and says it is active', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('credential', 'versions'), state: ftState() });
    await page.getByRole('button', { name: 'Set active v8' }).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/forms/active')).body).toEqual({ id: 'credential', version: 8 });
    await expect(page.getByRole('status').filter({ hasText: 'v8 is active' })).toBeVisible();
    await expect(page.locator('.version-row').first().locator('.chip.ok')).toHaveText('Active');
    await expect(page.getByRole('status').filter({ hasText: 'is stored but not active' })).toHaveCount(0);
  });

  test('Set active also rolls back to an older version', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('credential', 'versions'), state: ftState() });
    await page.getByRole('button', { name: 'Set active v6' }).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/forms/active')).body).toEqual({ id: 'credential', version: 6 });
    await expect(page.locator('.version-row').nth(2).locator('.chip.ok')).toHaveText('Active');
    await expect(page.getByRole('button', { name: /^Set active v6/ })).toHaveCount(0);
  });

  test('the active version has no Set active', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential', 'versions'), state: ftState() });

    await expect(page.getByRole('button', { name: 'Set active v7' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Set active v8' })).toBeVisible();
  });

  test('Preview draws a stored version, and Back to my edits returns to the working copy', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential', 'versions'), state: ftState() });
    await page.getByRole('button', { name: 'Preview v6' }).click();

    await expect(page.getByText('Previewing stored v6')).toBeVisible();
    await page.getByRole('button', { name: 'Back to my edits' }).click();
    await expect(page.getByText('Previewing stored v6')).toHaveCount(0);
  });

  test('Edit a copy of an older version makes it the base of the next save', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('credential', 'versions'), state: ftState() });
    await page.getByRole('button', { name: 'Edit a copy of v6' }).click();

    await expect(tab(page, 'Texts')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('note')).toContainText('Editing a copy of v6');
    await page.getByRole('button', { name: 'Русский' }).click();
    await expect(page.getByRole('textbox', { name: 'title', exact: true })).toHaveValue('Вход (старый)');
    await page.getByRole('button', { name: 'Save as new version' }).click();
    expect(((await findRequest(api.requests, 'PUT', '/configuration/forms')).body as Record<string, any>).localizations.ru.title).toBe('Вход (старый)');
  });

  test('a failed activation is shown', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('credential', 'versions'), state: ftState() });
    await page.route('**/configuration/forms/active', route => route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"No such version"}' }));
    await page.getByRole('button', { name: 'Set active v8' }).click();

    await expect(page.getByRole('alert').filter({ hasText: 'No such version' })).toBeVisible();
  });
});

test.describe('properties', () => {
  test('a custom form adds, renames and removes backend properties, and they are saved', async ({ page }) => {
    const api = await loadAdminApp(page, { path: recordPath('kyc-upload', 'properties'), state: ftState() });

    await page.getByRole('button', { name: '+ Add AnyOf' }).click();
    await page.getByRole('textbox', { name: 'Name' }).last().fill('docType');
    const add = page.getByRole('textbox', { name: 'Add a value to docType' });
    await add.fill('passport');
    await add.press('Enter');
    await add.fill('id-card');
    await add.press('Enter');
    await page.getByRole('button', { name: 'Remove value id-card' }).click();
    await page.getByRole('button', { name: '+ Add Number' }).click();
    await page.getByRole('button', { name: 'Remove property new_property_3' }).click();
    await page.getByRole('button', { name: 'Save as new version' }).click();

    expect(((await findRequest(api.requests, 'PUT', '/configuration/forms')).body as Record<string, unknown>).properties).toEqual([
      { type: 'BooleanProperty', name: 'showPhone' },
      { type: 'StringArrayProperty', name: 'docType', allowedValues: ['passport'] },
    ]);
  });

  test('says when there are none', async ({ page }) => {
    const forms = ftForms().map(form => (form.id === 'kyc-upload' ? { ...form, properties: [] } : form));
    await loadAdminApp(page, { path: recordPath('kyc-upload', 'properties'), state: { ...ftState(), forms } });

    await expect(page.getByText('No properties defined.')).toBeVisible();
  });
});

test.describe('create', () => {
  test('opens an empty editor on the Script tab, with a form ID', async ({ page }) => {
    await loadAdminApp(page, { path: listPath, state: ftState() });
    await page.getByRole('button', { name: '+ Create form' }).click();

    await expect(page).toHaveURL(/new=1/);
    await expect(page.getByRole('heading', { level: 1, name: 'Create form' })).toBeVisible();
    await expect(page.getByRole('tab')).toHaveText(['Script', 'Style', 'Texts', 'Properties']);
    await expect(tab(page, 'Script')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('note')).toContainText('Saving stores v1 without activating it');
  });

  test('refuses an ID that is empty or not lowercase letters, digits and dashes', async ({ page }) => {
    const api = await loadAdminApp(page, { path: ftPath('forms', '&new=1'), state: { ...ftState(), forms: [] } });
    const id = page.getByLabel('Form ID *');

    await page.getByRole('button', { name: 'Save as new version' }).first().click();
    await expect(page.getByRole('alert').filter({ hasText: 'A form ID is required.' })).toBeVisible();
    await id.fill('Bad ID');
    await expect(page.getByRole('alert').filter({ hasText: 'Lowercase letters, digits and dashes.' })).toBeVisible();
    await expect(id).toHaveAttribute('aria-invalid', 'true');
    expect(putForms(api.requests)).toHaveLength(0);
  });

  test('stores the first version and lands on the new form with the Versions tab', async ({ page }) => {
    const api = await loadAdminApp(page, { path: ftPath('forms', '&new=1'), state: { ...ftState(), forms: [] } });
    await page.getByLabel('Form ID *').fill('signup');
    await page.getByRole('button', { name: 'Save as new version' }).first().click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/forms')).body).toMatchObject({ id: 'signup' });
    await expect(page.getByRole('heading', { level: 1, name: 'signup' })).toBeVisible();
    await expect(tab(page, 'Versions')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('.version-row')).toHaveCount(1);
  });

  test('Cancel returns to the list', async ({ page }) => {
    await loadAdminApp(page, { path: ftPath('forms', '&new=1'), state: ftState() });
    await page.getByRole('button', { name: 'Cancel' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Forms' })).toBeVisible();
  });
});

test('the record never scrolls sideways at 1440, 1024 and 375', async ({ page }) => {
  for (const width of [1440, 1024, 375]) {
    await page.setViewportSize({ width, height: 900 });
    await loadAdminApp(page, { path: recordPath('credential'), state: ftState() });
    await expect(page.getByRole('heading', { level: 1, name: 'credential' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  }
});

test('Forms and Themes are listed in the side panel', async ({ page }) => {
  await loadAdminApp(page, { path: listPath, state: ftState() satisfies Partial<MockConfigState> });
  await expect(page.locator('versola-navigation').getByText('Forms', { exact: true })).toBeVisible();
});
