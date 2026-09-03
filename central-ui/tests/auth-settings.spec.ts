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
  },
  t: {
    page_title: 'Security', sessions_title: 'Sessions', no_sessions: 'No active sessions', unknown_device: 'Unknown device',
    current_session_badge: 'This device', expires_label: 'Expires {date}', revoke_button: 'Sign out', delete_session_confirm: 'Sign out this session?',
    passkeys_title: 'Passkeys', no_passkeys: 'No passkeys yet', unnamed_passkey: 'Passkey', rename_button: 'Rename',
    delete_button: 'Delete', delete_passkey_confirm: 'Remove this passkey?', save_button: 'Save', cancel_button: 'Cancel',
    confirm_button: 'Confirm', name_placeholder: 'Passkey name', name_required: 'Enter a passkey name',
    add_passkey_button: 'Add a passkey', enroll_failed: 'Could not add the passkey',
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