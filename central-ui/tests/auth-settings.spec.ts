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
    hasPassword: true,
    passwordRegex: '^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$',
  },
  t: {
    page_title: 'Security', sessions_title: 'Sessions', no_sessions: 'No active sessions', unknown_device: 'Unknown device',
    current_session_badge: 'This device', expires_label: 'Expires {date}', revoke_button: 'Sign out', delete_session_confirm: 'Sign out this session?',
    passkeys_title: 'Passkeys', no_passkeys: 'No passkeys yet', unnamed_passkey: 'Passkey', rename_button: 'Rename',
    delete_button: 'Delete', delete_passkey_confirm: 'Remove this passkey?', save_button: 'Save', cancel_button: 'Cancel',
    confirm_button: 'Confirm', name_placeholder: 'Passkey name', name_required: 'Enter a passkey name',
    add_passkey_button: 'Add a passkey', enroll_failed: 'Could not add the passkey',
    step_up_unavailable: 'This action is not available for your account right now',
    step_up_failed: 'We could not confirm your sign-in',
    password_title: 'Password', current_password_label: 'Current password', new_password_label: 'New password',
    confirm_password_label: 'Repeat the new password', show_password: 'Show password', hide_password: 'Hide password',
    change_password_button: 'Change password', password_changed: 'Password changed', password_mismatch: 'Passwords do not match',
    password_not_allowed: 'Password is too weak', password_reused: 'This password was used recently',
    current_password_incorrect: 'The current password is incorrect', too_many_attempts: 'Too many failed attempts',
    too_many_attempts_retry: 'Too many failed attempts, retry in {seconds} s', password_change_failed: 'Could not change the password',
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

async function loadAccountForm(page: Page, config: unknown = formConfig, url = accountUrl) {
  const [commonCss, formCss, script] = await Promise.all([
    readFile(new URL('../forms/common.css', import.meta.url), 'utf8'),
    readFile(new URL('../forms/auth-settings/auth-settings.css', import.meta.url), 'utf8'),
    accountFormScript(),
  ]);
  await page.goto(url);
  await page.setContent(`
    <style>${commonCss}${formCss}</style>
    <script>window.__VERSOLA_FORM__ = ${JSON.stringify(config)};</script>
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
test('goes to the login an action was answered with when it needs a stronger sign-in, and changes nothing', async ({ page }) => {
  await page.route('**/resources/auth/settings/**', async route => {
    await route.fulfill({
      status: 401,
      headers: {
        'WWW-Authenticate': 'Bearer error="insufficient_user_authentication", acr_values="sensitive", max_age="300"',
        Location: '/login/console?acr_values=sensitive&max_age=300',
      },
      body: '',
    });
  });
  await page.route('**/login/console**', route => route.fulfill({ status: 200, contentType: 'text/html', body: '<p>sign in</p>' }));
  await loadAccountForm(page);

  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm', exact: true }).click();

  // The login is asked to send the browser back to this very page, and the action is kept for it.
  await page.waitForURL('**/login/console?acr_values=sensitive&max_age=300&return_to=%2Fresources%2Fauth%2Fsettings');
  expect(await page.evaluate(() => JSON.parse(sessionStorage.getItem('versola.account.pending') ?? 'null'))).toMatchObject({
    kind: 'passkey',
    id: 'passkey-1',
  });
});

async function rememberPending(page: Page, pending: Record<string, unknown>, at = Date.now()) {
  await page.addInitScript(([value]) => sessionStorage.setItem('versola.account.pending', value), [JSON.stringify({ ...pending, at })]);
}

test('carries out the action the sign-in interrupted, without asking again', async ({ page }) => {
  const requests: Array<{ method: string; body: string }> = [];
  await page.route('**/resources/auth/settings/**', async route => {
    requests.push({ method: route.request().method(), body: route.request().postData() ?? '' });
    await route.fulfill({ status: 204, body: '' });
  });
  await rememberPending(page, { kind: 'passkey', id: 'passkey-1' });
  await loadAccountForm(page);

  await expect(page.getByText('Old name', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(requests).toEqual([{ method: 'DELETE', body: JSON.stringify({ credentialId: 'passkey-1' }) }]);
  expect(await page.evaluate(() => sessionStorage.getItem('versola.account.pending'))).toBeNull();
});

test('signs the other session out the same way, and starts an enrollment with the name that was typed', async ({ page }) => {
  const requests: Array<{ method: string; url: string; body: string }> = [];
  await page.route('**/resources/auth/settings/**', async route => {
    requests.push({ method: route.request().method(), url: route.request().url(), body: route.request().postData() ?? '' });
    await route.fulfill({ status: 204, body: '' });
  });
  await rememberPending(page, { kind: 'session', id: 'other-session' });
  await loadAccountForm(page);
  await expect(page.getByText('Firefox · Linux', { exact: true })).toHaveCount(0);
  expect(requests.map(r => r.method)).toEqual(['DELETE']);
  expect(requests[0].body).toBe(JSON.stringify({ targetSessionId: 'other-session' }));

  const enrol = await page.context().newPage();
  const enrolRequests: string[] = [];
  await enrol.route('**/resources/auth/settings/**', async route => {
    enrolRequests.push(route.request().url());
    await route.fulfill({ status: 500, body: '' });
  });
  await rememberPending(enrol, { kind: 'enroll', name: 'Work laptop' });
  await loadAccountForm(enrol);
  await expect(enrol.getByPlaceholder('Passkey name')).toHaveValue('Work laptop');
  expect(enrolRequests.some(url => url.endsWith('/passkeys/register/start'))).toBe(true);
});

test('does not go round again when the action is still refused right after the sign-in', async ({ page }) => {
  let calls = 0;
  await page.route('**/resources/auth/settings/**', async route => {
    calls += 1;
    await route.fulfill({ status: 401, headers: { Location: '/login/console?acr_values=sensitive' }, body: '' });
  });
  await page.route('**/login/console**', route => route.fulfill({ status: 200, contentType: 'text/html', body: '<p>sign in</p>' }));
  await rememberPending(page, { kind: 'passkey', id: 'passkey-1' });
  await loadAccountForm(page);

  await expect(page.getByRole('alert')).toHaveText('We could not confirm your sign-in');
  expect(calls).toBe(1);
  expect(new URL(page.url()).pathname).toBe(accountUrl);
  expect(await page.evaluate(() => sessionStorage.getItem('versola.account.pending'))).toBeNull();
});

test('ignores an interrupted action that is too old or not one this page wrote', async ({ page }) => {
  const requests: string[] = [];
  await page.route('**/resources/auth/settings/**', async route => {
    requests.push(route.request().method());
    await route.fulfill({ status: 204, body: '' });
  });
  await rememberPending(page, { kind: 'session', id: 'other-session' }, Date.now() - 11 * 60 * 1000);
  await loadAccountForm(page);
  await expect(page.getByText('Firefox · Linux', { exact: true })).toBeVisible();

  const foreign = await page.context().newPage();
  await foreign.route('**/resources/auth/settings/**', async route => {
    requests.push(route.request().method());
    await route.fulfill({ status: 204, body: '' });
  });
  await foreign.addInitScript(() => sessionStorage.setItem('versola.account.pending', '{"kind":"transfer","id":"x","at":' + Date.now() + '}'));
  await loadAccountForm(foreign);
  await expect(foreign.getByText('Firefox · Linux', { exact: true })).toBeVisible();
  expect(requests).toEqual([]);
});

test('says so when the sign-in ended with an error, forgets the action and takes the error off the address', async ({ page }) => {
  await rememberPending(page, { kind: 'passkey', id: 'passkey-1' });
  await loadAccountForm(page, formConfig, `${accountUrl}?error=unmet_authentication_requirements`);

  await expect(page.getByRole('alert')).toHaveText('This action is not available for your account right now');
  await expect(page.getByRole('status')).toHaveCount(0);
  expect(new URL(page.url()).search).toBe('');
  expect(await page.evaluate(() => sessionStorage.getItem('versola.account.pending'))).toBeNull();
});

test('does not follow a login URL that leaves this origin', async ({ page }) => {
  await page.route('**/resources/auth/settings/**', async route => {
    await route.fulfill({ status: 401, headers: { Location: 'https://evil.example/login' }, body: '' });
  });
  await loadAccountForm(page);

  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm', exact: true }).click();

  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText('Old name', { exact: true })).toBeVisible();
  expect(new URL(page.url()).hostname).not.toBe('evil.example');
});

test('the heading stays reachable when the content is taller than the card', async ({ page }) => {
  await page.setViewportSize({ width: 760, height: 420 });
  await loadAccountForm(page);
  const card = (await page.locator('.account-container').boundingBox())!;
  const heading = (await page.getByRole('heading', { level: 1 }).boundingBox())!;
  expect(heading.y).toBeGreaterThanOrEqual(card.y);
  const overflows = await page.locator('.account-container').evaluate(el => el.scrollHeight > el.clientHeight);
  expect(overflows).toBe(true);
});

test('a row action stays on one line when the details beside it are long', async ({ page }) => {
  await page.setViewportSize({ width: 560, height: 900 });
  await loadAccountForm(page);

  const lines = await page.getByRole('button', { name: 'Sign out', exact: true }).evaluate(button => {
    const range = document.createRange();
    range.selectNodeContents(button);
    return new Set([...range.getClientRects()].map(rect => Math.round(rect.top))).size;
  });
  expect(lines).toBe(1);
});


async function fillPasswordForm(page: Page, current: string, next: string, confirm = next) {
  await page.locator('#currentPassword').fill(current);
  await page.locator('#newPassword').fill(next);
  await page.locator('#confirm').fill(confirm);
  await page.getByRole('button', { name: 'Change password', exact: true }).click();
}

test('shows no password section to an account without a password', async ({ page }) => {
  await loadAccountForm(page, { ...formConfig, step: { ...formConfig.step, hasPassword: false } });
  await expect(page.getByRole('heading', { name: 'Security' })).toBeVisible();
  await expect(page.locator('#currentPassword')).toHaveCount(0);
});

test('changes the password and clears the form', async ({ page }) => {
  const bodies: string[] = [];
  await page.route('**/resources/auth/settings/password', async route => {
    bodies.push(route.request().postData() ?? '');
    await route.fulfill({ status: 204, body: '' });
  });
  await loadAccountForm(page);
  await fillPasswordForm(page, 'OldPass1', 'NewPass1');
  await expect(page.getByText('Password changed')).toBeVisible();
  await expect(page.locator('#newPassword')).toHaveValue('');
  expect(bodies.map(body => JSON.parse(body))).toEqual([{ currentPassword: 'OldPass1', newPassword: 'NewPass1' }]);
});

test('checks the new password locally before asking the server', async ({ page }) => {
  let requests = 0;
  await page.route('**/resources/auth/settings/password', async route => { requests += 1; await route.fulfill({ status: 204, body: '' }); });
  await loadAccountForm(page);
  await fillPasswordForm(page, 'OldPass1', 'weak');
  await expect(page.getByText('Password is too weak')).toBeVisible();
  await fillPasswordForm(page, 'OldPass1', 'NewPass1', 'NewPass2');
  await expect(page.getByText('Passwords do not match')).toBeVisible();
  expect(requests).toBe(0);
});

// Each refusal arrives as a status plus a stable code; the page, not the server, supplies the words.
for (const refusal of [
  { name: 'a wrong current password', status: 400, body: { error: 'current_password_incorrect' }, text: 'The current password is incorrect', field: '#currentPassword' },
  { name: 'a reused password', status: 400, body: { error: 'password_reused' }, text: 'This password was used recently', field: '#newPassword' },
  { name: 'a server-side policy refusal', status: 400, body: { error: 'password_not_allowed' }, text: 'Password is too weak', field: '#newPassword' },
  { name: 'a ban', status: 429, body: { error: 'too_many_attempts' }, text: 'Too many failed attempts', field: undefined },
  { name: 'a wait', status: 429, body: { error: 'too_many_attempts', retryAfterSeconds: 30 }, text: 'Too many failed attempts, retry in 30 s', field: undefined },
  { name: 'a code the page does not know', status: 400, body: { error: 'something_new' }, text: 'Could not change the password', field: undefined },
  { name: 'a plain-text error from a proxy', status: 502, body: 'Bad gateway', text: 'Could not change the password', field: undefined },
]) {
  test(`shows ${refusal.name} in the page's own words`, async ({ page }) => {
    await page.route('**/resources/auth/settings/password', route => route.fulfill({
      status: refusal.status,
      contentType: typeof refusal.body === 'string' ? 'text/plain' : 'application/json',
      body: typeof refusal.body === 'string' ? refusal.body : JSON.stringify(refusal.body),
    }));
    await loadAccountForm(page);
    await fillPasswordForm(page, 'OldPass1', 'NewPass1');
    const message = page.getByRole('alert').filter({ hasText: refusal.text });
    await expect(message).toBeVisible();
    if (refusal.field) await expect(page.locator(refusal.field)).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByText('Password changed')).toHaveCount(0);
  });
}
