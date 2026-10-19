import { expect, test } from '@playwright/test';
import { INVALID_FIELD_BORDER, expectSheetWidth, findRequest, loadAdminApp, sidePanel, sidePanelDialog } from './fixtures';

/**
 * OTP templates on the table + side panel pattern. A template's key is (id, type, channel), so
 * the URL carries all three; an email body is shown in a sandboxed iframe and edited as HTML.
 */
const base = '/?view=otp-templates&tenant=tenant-alpha';
const locales = [
  { code: 'en', name: 'English', isDefault: true, active: true },
  { code: 'ru', name: 'Russian', isDefault: false, active: true },
];
const loginSms = { id: 'login-otp', tenantId: 'tenant-alpha', purpose: 'otp', channel: 'sms' as const, localizations: { en: 'Your code is {{code}}', ru: 'Ваш код {{code}}' } };
const loginEmail = { id: 'login-otp', tenantId: 'tenant-alpha', purpose: 'otp', channel: 'email' as const, localizations: { en: '<html><body><p>Code <b>{{code}}</b></p></body></html>' } };
const tempPassword = { id: 'temp-password', tenantId: 'tenant-alpha', purpose: 'password', channel: 'sms' as const, localizations: { en: 'Password {{password}}, {{expiresHours}} hours', ru: 'Пароль {{password}}, {{expiresHours}} ч' } };
const readOnly = { resources: { central: { permissions: ['security:read'] } }, isProd: false };
const withTemplates = (...templates: typeof loginSms[] | Array<typeof loginEmail>) => ({ otpTemplates: { 'tenant-alpha': templates as never }, locales });

test('lists templates with type, channel and languages, filters by chip and searches by ID', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: withTemplates(loginSms, loginEmail, tempPassword) });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Template', 'Type', 'Channel', 'Languages', 'Actions']);
  await expect(table.getByRole('row').filter({ hasText: 'temp-password' })).toContainText('2 of 2');
  await expect(table.getByRole('row').filter({ hasText: 'Email' })).toContainText('Missing: ru');

  await page.getByRole('button', { name: /^Password\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open template/ })).toHaveText([/temp-password/]);
  await page.getByRole('button', { name: /^Email\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open template/ })).toHaveText([/login-otp/]);
  await page.getByRole('button', { name: /^Missing translations\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open template/ })).toHaveCount(1);
  await page.getByRole('button', { name: /^All\s?3$/ }).click();

  await page.getByLabel('Search templates').fill('temp');
  await expect(table.getByRole('button', { name: /^Open template/ })).toHaveCount(1);
  await page.getByLabel('Search templates').fill('nothing');
  await expect(page.getByRole('heading', { name: 'No templates match' })).toBeVisible();
});

test('opens the panel from the row, shows the message per locale and the URL carries the full key', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: withTemplates(loginSms, loginEmail) });

  await page.locator('versola-data-table').getByRole('row').filter({ hasText: 'SMS' }).getByRole('cell').nth(1).click();
  const panel = sidePanel(page, 'login-otp');
  await expect(panel.getByRole('dialog')).toBeVisible();
  await expect(page).toHaveURL(/id=login-otp(%3A|:)otp(%3A|:)sms/);
  const message = panel.getByRole('region', { name: 'Message' });
  await expect(message).toContainText('Your code is {{code}}');
  await expect(message).toContainText('Ваш код {{code}}');
  await expect(panel.getByRole('region', { name: 'Fixed' })).toContainText('Part of the template');
  await expect(panel.getByRole('region', { name: 'Other variants' })).toContainText('OTP · Email');
  await expect(panel.getByRole('region', { name: 'Delete template' })).toBeVisible();

  await panel.getByRole('button', { name: 'Close panel' }).click();
  await expect(panel).toHaveCount(0);
  await expect(page).not.toHaveURL(/id=/);
});

test('shows an email body in a sandboxed iframe and warns about a missing translation', async ({ page }) => {
  await loadAdminApp(page, { path: `${base}&id=login-otp:otp:email`, state: withTemplates(loginEmail) });

  const panel = sidePanel(page, 'login-otp');
  const frame = panel.locator('iframe.email-preview');
  await expect(frame).toHaveCount(1);
  await expect(frame).toHaveAttribute('sandbox', '');
  await expect(frame).toHaveAttribute('title', 'Preview for en');
  await expect(panel.getByRole('status')).toContainText('Missing translations for ru');
});

test('another variant of the same ID opens from the panel', async ({ page }) => {
  await loadAdminApp(page, { path: `${base}&id=login-otp:otp:sms`, state: withTemplates(loginSms, loginEmail) });

  await sidePanel(page, 'login-otp').getByRole('button', { name: 'OTP · Email' }).click();
  await expect(page).toHaveURL(/id=login-otp(%3A|:)otp(%3A|:)email/);
  await expect(sidePanel(page, 'login-otp').locator('iframe.email-preview')).toHaveCount(1);
});

test('restores the message editor from the URL and edits an SMS text', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&id=login-otp:otp:sms&edit=message`, state: withTemplates(loginSms) });

  const editor = sidePanel(page, 'Edit message');
  await expect(editor.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
  await editor.locator('#message-en').fill('Use {{code}} to sign in');
  await editor.getByRole('button', { name: 'Save message' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/challenges/otp-templates')).body).toEqual({
    id: 'login-otp',
    tenantId: 'tenant-alpha',
    purpose: 'otp',
    channel: 'sms',
    localizations: { en: 'Use {{code}} to sign in', ru: 'Ваш код {{code}}' },
  });
  await expect(sidePanel(page, 'login-otp').getByRole('region', { name: 'Message' })).toContainText('Use {{code}} to sign in');
});

test('refuses a message without its placeholder before sending', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&id=login-otp:otp:sms&edit=message`, state: withTemplates(loginSms) });

  const editor = sidePanel(page, 'Edit message');
  const field = editor.locator('#message-en');
  await field.fill('No placeholder here');
  await editor.getByRole('button', { name: 'Save message' }).click();
  await expect(field).toHaveAttribute('aria-invalid', 'true');
  await expect(field).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await expect(editor.getByRole('alert')).toContainText('{{code}}');
  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);
});

test('creates a template for an SMS and opens it', async ({ page }) => {
  const api = await loadAdminApp(page, { path: base, state: { ...withTemplates(loginSms), } });

  await page.getByRole('button', { name: '+ Create Template' }).click();
  const dialog = sidePanel(page, 'Create template');
  await expect(page).toHaveURL(/new=1/);
  await dialog.getByLabel('Template ID *').fill('Bad ID');
  await expect(dialog.getByText('Lowercase letters, digits, underscores and hyphens, starting with a letter.').first()).toBeVisible();
  await dialog.getByLabel('Template ID *').fill('login-otp');
  await expect(dialog.getByText('An OTP template for SMS with this ID already exists.')).toBeVisible();
  await dialog.getByLabel('Template ID *').fill('signup-otp');
  await dialog.locator('#message-en').fill('Welcome, your code is {{code}}');
  await dialog.locator('#message-ru').fill('Ваш код {{code}}');
  await dialog.getByRole('button', { name: 'Create template', exact: true }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/challenges/otp-templates')).body).toEqual({
    id: 'signup-otp',
    tenantId: 'tenant-alpha',
    purpose: 'otp',
    channel: 'sms',
    localizations: { en: 'Welcome, your code is {{code}}', ru: 'Ваш код {{code}}' },
  });
  await expect(sidePanelDialog(page, 'signup-otp')).toBeVisible();
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'signup-otp' })).toHaveAttribute('aria-current', 'true');
});

test('an email template is written as HTML in the code editor and can be previewed', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&new=1`, state: withTemplates(loginSms) });

  const dialog = sidePanel(page, 'Create template');
  await dialog.getByLabel('Template ID *').fill('welcome-code');
  await dialog.getByLabel('Channel').selectOption('email');
  await expect(dialog.getByText('Email templates must contain valid HTML.').first()).toBeVisible();
  await dialog.locator('versola-code-editor#message-en textarea').fill('<html><body><p>Code {{code}}</p></body></html>');
  await dialog.getByRole('button', { name: 'Preview' }).first().click();
  await expect(dialog.locator('iframe.email-preview')).toHaveAttribute('sandbox', '');
  await dialog.getByRole('button', { name: 'Edit HTML' }).click();
  await dialog.locator('versola-code-editor#message-ru textarea').fill('<html><body><p>Код {{code}}</p></body></html>');
  await dialog.getByRole('button', { name: 'Create template', exact: true }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/challenges/otp-templates')).body).toMatchObject({ id: 'welcome-code', channel: 'email' });
});

test('deletes a template from the panel and from the row menu through the confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, { path: base, state: withTemplates(loginSms, tempPassword) });

  await page.getByRole('button', { name: 'Open template login-otp otp sms' }).click();
  await sidePanel(page, 'login-otp').getByRole('button', { name: 'Delete template login-otp:otp:sms' }).click();
  await page.getByRole('dialog', { name: 'Delete template' }).getByRole('button', { name: 'Delete', exact: true }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/challenges/otp-templates')).body).toEqual({ id: 'login-otp', tenantId: 'tenant-alpha', purpose: 'otp', channel: 'sms' });
  await expect(sidePanel(page, 'login-otp')).toHaveCount(0);

  await page.getByRole('button', { name: 'Actions for temp-password:password:sms' }).click();
  await page.getByRole('menuitem', { name: 'Delete template temp-password:password:sms' }).click();
  await page.getByRole('dialog', { name: 'Delete template' }).getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'No OTP templates yet' })).toBeVisible();
});

test('read-only console: no create, no Edit, no danger card, row menu without Delete', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: { ...withTemplates(loginSms), myPermissions: readOnly } });

  await expect(page.getByRole('button', { name: '+ Create Template' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Actions for login-otp:otp:sms' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy template ID']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const panel = sidePanel(page, 'login-otp');
  await expect(panel.getByRole('region', { name: 'Message' })).toBeVisible();
  await expect(panel.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(panel.getByRole('region', { name: 'Delete template' })).toHaveCount(0);
});

test('without a tenant it asks for one', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=otp-templates', state: { tenants: [] } });
  await expect(page.getByRole('heading', { name: 'Select a tenant' })).toBeVisible();
});

test('below 720px the panel is a full-screen sheet and nothing scrolls sideways', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${base}&id=login-otp:otp:email`, state: withTemplates(loginEmail) });

  const dialog = sidePanel(page, 'login-otp');
  const box = await dialog.getByRole('dialog').boundingBox();
  await expectSheetWidth(page, box?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test('shows the loading skeleton, then an error with Retry that recovers', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=clients&tenant=tenant-alpha', state: withTemplates(loginSms) });
  let attempts = 0;
  await page.route('**/configuration/challenges/otp-templates?*', async route => {
    attempts += 1;
    await new Promise(resolve => setTimeout(resolve, 300));
    if (attempts === 1) await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ message: 'Database unavailable' }) });
    else await route.fallback();
  });
  await page.goto(base);
  await expect(page.locator('versola-otp-templates-list versola-loading-cards')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Could not load OTP templates' })).toBeVisible();
  await page.getByRole('button', { name: 'Retry' }).click();
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open template/ })).toHaveCount(1);
});
