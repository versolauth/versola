import { expect, test, type Page } from '@playwright/test';
import { build } from 'esbuild';
import { solidPlugin } from 'esbuild-plugin-solid';
import { readFile } from 'node:fs/promises';

const accountUrl = '/resources/auth/settings';
const formConfig = {
  step: {
    type: 'auth-settings',
    sessions: [
      { id: 'current-session', browser: 'Chrome', os: 'macOS', createdAt: '2026-01-01T00:00:00Z', expiresAt: '2026-02-01T00:00:00Z', current: true },
      { id: 'other-session', browser: 'Firefox', os: 'Linux', createdAt: '2026-01-02T00:00:00Z', expiresAt: '2026-02-02T00:00:00Z', current: false },
    ],
    passkeys: [{ id: 'passkey-1', name: 'Old name', backedUp: true, createdAt: '2026-01-01T00:00:00Z' }],
    phone: '+7 701 ••• 67',
    email: 'm•••e@example.com',
  },
  t: {
    page_title: 'Security', sessions_title: 'Sessions', no_sessions: 'No active sessions', unknown_device: 'Unknown device',
    current_session_badge: 'This device', expires_label: 'Expires {date}', revoke_button: 'Sign out', delete_session_confirm: 'Sign out this session?',
    passkeys_title: 'Passkeys', no_passkeys: 'No passkeys yet', unnamed_passkey: 'Passkey', rename_button: 'Rename',
    delete_button: 'Delete', delete_passkey_confirm: 'Remove this passkey?', save_button: 'Save', cancel_button: 'Cancel',
    confirm_button: 'Confirm', name_placeholder: 'Passkey name', name_required: 'Enter a passkey name',
    add_passkey_button: 'Add a passkey', enroll_failed: 'Could not add the passkey',
    identifiers_title: 'Phone number and email', phone_label: 'Phone number', email_label: 'Email', change_kind_label: 'What to change',
    change_description: 'To change one, enter the new value.', new_phone_placeholder: 'New phone number', new_email_placeholder: 'New email',
    change_button: 'Change', change_invalid: 'Enter a valid value', change_unchanged: 'This is already your current value',
    change_failed: 'Could not start the change',
  },
  locales: ['en'],
};

let formScript: Promise<string> | undefined;

async function accountFormScript(): Promise<string> {
  formScript ??= build({
    entryPoints: [new URL('../forms/auth-settings/auth-settings.tsx', import.meta.url).pathname],
    bundle: true,
    write: false,
    format: 'iife',
    minify: true,
    target: 'es2019',
    plugins: [solidPlugin()],
  }).then(result => result.outputFiles[0].text);
  return formScript;
}

async function loadAccountForm(page: Page) {
  const [commonCss, formCss, script] = await Promise.all([
    readFile(new URL('../forms/common.css', import.meta.url), 'utf8'),
    readFile(new URL('../forms/auth-settings/auth-settings.css', import.meta.url), 'utf8'),
    accountFormScript(),
  ]);
  await page.goto(accountUrl);
  await page.setContent(`
    <style>${commonCss}${formCss}</style>
    <script>window.__VERSOLA_FORM__ = ${JSON.stringify(formConfig)};</script>
    <div id="versola-form-root"></div>
    <script>${script}</script>
  `);
}

test('uses styled deletion confirmation and submits account actions', async ({ page }) => {
  const requests: Array<{ method: string; url: string; body: string }> = [];
  let browserDialogCount = 0;
  page.on('dialog', async dialog => {
    browserDialogCount += 1;
    await dialog.dismiss();
  });
  await page.route('**/resources/auth/settings/**', async route => {
    const request = route.request();
    requests.push({ method: request.method(), url: request.url(), body: request.postData() ?? '' });
    await route.fulfill({ status: 204, body: '' });
  });
  await loadAccountForm(page);

  await expect(page.getByText(`(Expires ${new Date('2026-02-01T00:00:00Z').toLocaleString('en')})`)).toBeVisible();

  await page.getByRole('button', { name: 'Rename', exact: true }).click();
  await page.locator('.account-rename input').fill('Work laptop');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByText('Work laptop', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByText('Work laptop', { exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByText('Firefox · Linux', { exact: true })).toHaveCount(0);

  expect(browserDialogCount).toBe(0);
  expect(requests).toEqual([
    {
      method: 'PATCH',
      url: expect.stringContaining('/resources/auth/settings/passkeys'),
      body: JSON.stringify({ credentialId: 'passkey-1', name: 'Work laptop' }),
    },
    {
      method: 'DELETE',
      url: expect.stringContaining('/resources/auth/settings/passkeys'),
      body: JSON.stringify({ credentialId: 'passkey-1' }),
    },
    {
      method: 'DELETE',
      url: expect.stringContaining('/resources/auth/settings/sessions'),
      body: JSON.stringify({ targetSessionId: 'other-session' }),
    },
  ]);
});
test('says what a change replaces, records the intent, then continues at the login with the reserved value', async ({ page }) => {
  const requests: Array<{ method: string; url: string; body: string }> = [];
  await page.route('**/resources/auth/settings/identifier/intent', async route => {
    const request = route.request();
    requests.push({ method: request.method(), url: request.url(), body: request.postData() ?? '' });
    await route.fulfill({ status: 204, body: '' });
  });
  await page.route('**/login/current**', route => route.fulfill({ contentType: 'text/html', body: '<p>login</p>' }));
  await loadAccountForm(page);

  await expect(page.getByText('+7 701 ••• 67', { exact: true })).toBeVisible();
  await expect(page.getByText('m•••e@example.com', { exact: true })).toBeVisible();

  await page.getByPlaceholder('New phone number').fill('+77011234568');
  await page.getByRole('button', { name: 'Change', exact: true }).click();

  await expect(page).toHaveURL(/\/login\/current\?/);
  const target = new URL(page.url());
  expect(target.searchParams.get('acr_values')).toBe('urn:versola:acr:phone-change');
  expect(target.searchParams.get('login_hint')).toBe('+77011234568');
  expect(requests).toEqual([
    {
      method: 'POST',
      url: expect.stringContaining('/resources/auth/settings/identifier/intent'),
      body: JSON.stringify({ kind: 'phone', value: '+77011234568' }),
    },
  ]);
});

test('names the kind of the change in the intent and in the reserved value', async ({ page }) => {
  let body = '';
  await page.route('**/resources/auth/settings/identifier/intent', async route => {
    body = route.request().postData() ?? '';
    await route.fulfill({ status: 204, body: '' });
  });
  await page.route('**/login/current**', route => route.fulfill({ contentType: 'text/html', body: '<p>login</p>' }));
  await loadAccountForm(page);

  await page.getByLabel('What to change').selectOption('email');
  await page.getByPlaceholder('New email').fill('new@example.com');
  await page.getByRole('button', { name: 'Change', exact: true }).click();

  await expect(page).toHaveURL(/\/login\/current\?/);
  expect(new URL(page.url()).searchParams.get('acr_values')).toBe('urn:versola:acr:email-change');
  expect(body).toBe(JSON.stringify({ kind: 'email', value: 'new@example.com' }));
});

test('stays on the page and says why when the change cannot be started', async ({ page }) => {
  let status = 409;
  await page.route('**/resources/auth/settings/identifier/intent', route => route.fulfill({ status, body: '' }));
  await loadAccountForm(page);

  await page.getByPlaceholder('New phone number').fill('+77011234567');
  await page.getByRole('button', { name: 'Change', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('This is already your current value');

  status = 400;
  await page.getByRole('button', { name: 'Change', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Enter a valid value');

  status = 500;
  await page.getByRole('button', { name: 'Change', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Could not start the change');
});
