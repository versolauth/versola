import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { CSRF, expectSubmissions, renderForm, type FormConfig } from './form-harness';

/**
 * The login forms in central-ui/forms, rendered from source with their real strings (see
 * form-harness.ts). Each test drives a form the way a user does and checks what the browser would
 * send to the server: path, `ui_locale`, and the fields, including the CSRF value.
 */

const STRONG_PASSWORD = 'Str0ngPassw0rd';
const PASSWORD_REGEX = '^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$';

test.describe('otp', () => {
  const step = { type: 'otp', length: 6, resendAfter: 0, destination: 'u***@example.com' };

  test('says where the code was sent', async ({ page }) => {
    const { t } = await renderForm(page, 'otp', { step });

    await expect(page.getByRole('heading', { name: t.title })).toBeVisible();
    await expect(page.getByText(t.description.replace('{destination}', 'u***@example.com'))).toBeVisible();
  });

  test('submits as soon as the code is complete', async ({ page }) => {
    const { submitted } = await renderForm(page, 'otp', { step });

    await page.keyboard.type('123456');

    await expectSubmissions(submitted, 1);
    expect(submitted[0]).toMatchObject({ method: 'POST', path: '/challenge/otp' });
    expect(submitted[0].query.get('ui_locale')).toBe('en');
    expect(submitted[0].fields.get('code')).toBe('123456');
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });

  test('does not submit an incomplete code', async ({ page }) => {
    const { submitted } = await renderForm(page, 'otp', { step });

    await page.keyboard.type('12345');
    await page.waitForTimeout(300);

    expect(submitted).toHaveLength(0);
  });

  // A pasted code is commonly written with a space or a dash. The field limits its raw length to the
  // code length, so the browser cuts the paste before the form can strip the separator and the code
  // is one digit short. Skipped until the form handles it; remove `fixme` when it does.
  test.fixme('accepts a code pasted with a separator', async ({ page }) => {
    const { submitted } = await renderForm(page, 'otp', { step });

    await page.locator('input[name="code"]').evaluate((input: HTMLInputElement) => {
      input.focus();
      document.execCommand('insertText', false, '123 456');
    });

    await expectSubmissions(submitted, 1);
    expect(submitted[0].fields.get('code')).toBe('123456');
  });

  test('offers a resend once the wait is over, and posts it with the CSRF value', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'otp', { step });

    await page.getByRole('button', { name: t.resend_button }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/otp/resend');
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });

  test('counts down before offering a resend', async ({ page }) => {
    const { t } = await renderForm(page, 'otp', { step: { ...step, resendAfter: 30 } });

    await expect(page.getByText(/Resend available in (29|30)s/)).toBeVisible();
    await expect(page.getByRole('button', { name: t.resend_button })).toHaveCount(0);
  });

  test('locks the input while the server says so', async ({ page }) => {
    await renderForm(page, 'otp', { step: { ...step, lockedSeconds: 20 } });

    await expect(page.getByText(/Try again in (19|20)s/)).toBeVisible();
    await expect(page.locator('input[name="code"]')).toBeDisabled();
  });

  test('shows a wrong-code error in words', async ({ page }) => {
    const { t } = await renderForm(page, 'otp', { step, error: 'otp_wrong' });

    await expect(page.getByText(t.otp_wrong)).toBeVisible();
  });
});

test.describe('password', () => {
  const step = { type: 'password', passwordRegex: PASSWORD_REGEX };

  test('submits the password with the CSRF value', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'password', { step });

    await page.getByPlaceholder(t.password_placeholder).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/password');
    expect(submitted[0].fields.get('password')).toBe(STRONG_PASSWORD);
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });

  test('refuses a password the policy rejects, without asking the server', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'password', { step });

    await page.getByPlaceholder(t.password_placeholder).fill('weak');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(page.getByText(t.password_not_allowed)).toBeVisible();
    expect(submitted).toHaveLength(0);
  });

  test('shows a wrong-password error in words', async ({ page }) => {
    const { t } = await renderForm(page, 'password', { step, error: 'password_wrong' });

    await expect(page.getByText(t.password_wrong)).toBeVisible();
  });

  test('switches language and sends the chosen one', async ({ page }) => {
    const { submitted, all } = await renderForm(page, 'password', { step });

    await page.locator('.locale-trigger').click();
    await page.getByRole('button', { name: 'ru', exact: true }).click();
    await expect(page.getByRole('heading', { name: all.ru.title })).toBeVisible();

    await page.getByPlaceholder(all.ru.password_placeholder).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: all.ru.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].query.get('ui_locale')).toBe('ru');
  });
});

test.describe('set-password', () => {
  const step = { type: 'set-password', passwordRegex: PASSWORD_REGEX };

  test('submits matching passwords', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'set-password', { step });

    await page.getByPlaceholder(t.password_placeholder).fill(STRONG_PASSWORD);
    await page.getByPlaceholder(t.confirm_placeholder).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/set-password');
    expect(submitted[0].fields.get('password')).toBe(STRONG_PASSWORD);
    expect(submitted[0].fields.get('confirm')).toBe(STRONG_PASSWORD);
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });

  test('flags a mismatch as it is typed and does not submit it', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'set-password', { step });

    await page.getByPlaceholder(t.password_placeholder).fill(STRONG_PASSWORD);
    await page.getByPlaceholder(t.confirm_placeholder).fill('Different1Password');
    await expect(page.getByText(t.password_mismatch)).toBeVisible();

    await page.getByRole('button', { name: t.continue }).click();
    expect(submitted).toHaveLength(0);
  });

  test('refuses a password the policy rejects', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'set-password', { step });

    await page.getByPlaceholder(t.password_placeholder).fill('weak');
    await page.getByPlaceholder(t.confirm_placeholder).fill('weak');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(page.getByText(t.password_not_allowed)).toBeVisible();
    expect(submitted).toHaveLength(0);
  });
});

test.describe('credential', () => {
  test('email: posts the address to the email challenge', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] } });

    await page.getByPlaceholder(t.email_placeholder).fill('ada@example.com');
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/email');
    expect(submitted[0].fields.get('email')).toBe('ada@example.com');
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });

  test('login: asks for a password too and posts both', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['login'], passwordRegex: PASSWORD_REGEX },
    });

    await page.getByPlaceholder(t.login_placeholder).fill('ada');
    await page.getByPlaceholder(t.password_placeholder).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/login-password');
    expect(submitted[0].fields.get('login')).toBe('ada');
    expect(submitted[0].fields.get('password')).toBe(STRONG_PASSWORD);
  });

  test('login: refuses a password the policy rejects', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['login'], passwordRegex: PASSWORD_REGEX },
    });

    await page.getByPlaceholder(t.login_placeholder).fill('ada');
    await page.getByPlaceholder(t.password_placeholder).fill('weak');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(page.getByText(t.password_not_allowed)).toBeVisible();
    expect(submitted).toHaveLength(0);
  });

  test('email with an inline password posts to the combined challenge', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['email'], inlinePassword: true },
    });

    await page.getByPlaceholder(t.email_placeholder).fill('ada@example.com');
    await page.getByPlaceholder(t.password_placeholder).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/email-password');
  });

  test('phone: only numbers with an allowed prefix are sent', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['phone'], allowedPhonePrefixes: ['+7'] },
    });
    const phone = page.getByPlaceholder(t.phone_placeholder);

    await phone.fill('+15551234567');
    await page.getByRole('button', { name: t.continue }).click();
    await expect(page.getByText(t.phone_not_allowed)).toBeVisible();
    expect(submitted).toHaveLength(0);

    await phone.fill('+77001234567');
    await expect(page.getByText(t.phone_not_allowed)).toHaveCount(0);
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/phone');
    expect(submitted[0].fields.get('phone')).toBe('+77001234567');
  });

  test('several first factors share one field named for all of them', async ({ page }) => {
    const { t } = await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email', 'phone'] } });

    await expect(page.getByPlaceholder(`${t.email_placeholder} / ${t.phone_placeholder}`)).toBeVisible();
  });

  test('shows a failed sign-in in words', async ({ page }) => {
    const { t } = await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['login'] },
      error: 'login_failed',
    });

    await expect(page.getByText(t.login_failed)).toBeVisible();
  });

  test('offers a passkey only when the step enables it', async ({ page }) => {
    const { t } = await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'], passkey: true } });
    await expect(page.getByRole('button', { name: t.passkey_button })).toBeVisible();

    const without = await page.context().newPage();
    await renderForm(without, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] } });
    await expect(without.getByRole('button', { name: t.passkey_button })).toHaveCount(0);
  });

  test('reports a failed passkey sign-in in a dialog that can be closed', async ({ page }) => {
    const { t } = await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['email'], passkey: true },
      error: 'passkey_failed',
    });

    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText(t.passkey_failed);
    await dialog.getByRole('button', { name: t.close }).click();
    await expect(dialog).toHaveCount(0);
  });

  test('falls back to the built-in mark when the logo cannot load', async ({ page }) => {
    await page.route('**/missing-logo.png', route => route.abort());
    await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['email'] },
      logo: '/missing-logo.png',
    });

    await expect(page.locator('.brand-mark')).toHaveCount(0);
  });
});

test.describe('consent', () => {
  const scopes = [
    { scope: 'openid', description: 'Know who you are', claims: [], deselectable: false },
    { scope: 'profile', description: 'Read your profile', claims: ['name'], claimLocalizations: [{ en: 'Your name' }], deselectable: true },
    { scope: 'email', description: 'Read your email address', claims: [], deselectable: true },
  ];
  const step = { type: 'consent', clientName: 'Acme', scopes, allowPartial: true, denyUri: '/challenge/consent/deny' };

  test('names the application and lists what it asks for', async ({ page }) => {
    await renderForm(page, 'consent', { step });

    await expect(page.getByText('Acme wants to access your account.')).toBeVisible();
    await expect(page.getByText('Read your profile')).toBeVisible();
    await expect(page.getByText('Your name')).toBeVisible();
  });

  test('sends exactly the scopes the user left selected', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'consent', { step });

    await page.getByLabel('Read your profile').uncheck();
    await page.getByRole('button', { name: t.allow_button }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/consent');
    expect(submitted[0].fields.get('scope')).toBe('openid email');
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });

  test('does not let the user drop a scope the client requires', async ({ page }) => {
    await renderForm(page, 'consent', { step });

    await expect(page.getByLabel('Know who you are')).toBeDisabled();
    await expect(page.getByLabel('Read your profile')).toBeEnabled();
  });

  test('is all-or-nothing when the client does not allow a partial grant', async ({ page }) => {
    await renderForm(page, 'consent', { step: { ...step, allowPartial: false } });

    for (const scope of scopes) {
      await expect(page.getByLabel(scope.description)).toBeDisabled();
    }
  });

  test('posts a denial with the CSRF value', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'consent', { step });

    await page.getByRole('button', { name: t.deny_button }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/consent/deny');
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });

  test('opens the legal links in a new tab without handing over the opener', async ({ page }) => {
    const { t } = await renderForm(page, 'consent', {
      step: { ...step, policyUri: 'https://acme.example/privacy', tosUri: 'https://acme.example/terms' },
    });

    for (const [name, href] of [[t.policy_link, 'https://acme.example/privacy'], [t.tos_link, 'https://acme.example/terms']]) {
      const link = page.getByRole('link', { name });
      await expect(link).toHaveAttribute('href', href);
      await expect(link).toHaveAttribute('target', '_blank');
      await expect(link).toHaveAttribute('rel', /noopener/);
    }
  });
});

test.describe('passkey-enroll', () => {
  const step = { type: 'passkey-enroll', publicKeyOptions: '{}' };

  test('refuses a name the server would reject', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'passkey-enroll', { step });

    await page.getByPlaceholder(t.name_placeholder).fill(' leading space');
    await page.getByRole('button', { name: t.enroll_button }).click();

    await expect(page.getByText(t.name_invalid)).toBeVisible();
    expect(submitted).toHaveLength(0);
  });

  test('lets the user skip, and says so with the CSRF value', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'passkey-enroll', { step });

    await page.getByRole('button', { name: t.skip_button }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/passkey/skip');
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });
});

test.describe('confirm-logout', () => {
  test('posts the confirmation with the values it was given', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'confirm-logout', {
      step: { type: 'confirm-logout', csrfToken: 'logout-csrf', state: 'abc', postLogoutRedirectUri: 'https://app.example/bye' },
    });

    await page.getByRole('button', { name: t.confirm_button }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0]).toMatchObject({ method: 'POST', path: '/logout' });
    expect(Object.fromEntries(submitted[0].fields)).toEqual({
      csrf_token: 'logout-csrf',
      post_logout_redirect_uri: 'https://app.example/bye',
      state: 'abc',
    });
  });

  test('leaves out what it was not given', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'confirm-logout', { step: { type: 'confirm-logout', csrfToken: 'logout-csrf' } });

    await page.getByRole('button', { name: t.confirm_button }).click();

    await expectSubmissions(submitted, 1);
    expect(Object.fromEntries(submitted[0].fields)).toEqual({ csrf_token: 'logout-csrf' });
  });
});

test.describe('terminal screens', () => {
  for (const id of ['conversation-expired', 'service-unavailable']) {
    test(`${id}: offers a way back only when it has one`, async ({ page }) => {
      const { t } = await renderForm(page, id, { step: { type: id, redirectUri: 'https://app.example/start' } });
      await expect(page.getByRole('link', { name: t.return_button })).toHaveAttribute('href', 'https://app.example/start');

      const without = await page.context().newPage();
      await renderForm(without, id, { step: { type: id } });
      await expect(without.getByRole('link', { name: t.return_button })).toHaveCount(0);
    });
  }

  test('access-denied: returns to the client', async ({ page }) => {
    const { t } = await renderForm(page, 'access-denied', { step: { type: 'access-denied', redirectUri: 'https://app.example/start' } });

    await expect(page.getByRole('link', { name: t.return_button })).toHaveAttribute('href', 'https://app.example/start');
  });

  test('signed-out: notifies every front-channel logout URI, then continues on its own', async ({ page }) => {
    await page.clock.install();
    await page.route('**/rp-*/logout', route => route.fulfill({ contentType: 'text/html', body: '' }));
    await page.route('**/after-logout', route => route.fulfill({ contentType: 'text/html', body: '<p>done</p>' }));
    const { t } = await renderForm(page, 'signed-out', {
      step: { type: 'signed-out', logoutUris: ['/rp-1/logout', '/rp-2/logout'], redirectUri: '/after-logout' },
    });

    await expect(page.locator('iframe')).toHaveCount(2);
    await expect(page.getByRole('link', { name: t.continue_button })).toHaveAttribute('href', '/after-logout');

    await page.clock.fastForward(3000);
    await expect(page).toHaveURL(/\/after-logout$/);
  });
});

/**
 * One axe scan per form, in a typical configuration. axe covers part of WCAG; the screenshots and
 * the behaviour tests above cover the rest.
 */
const accessibilityCases: Array<[string, FormConfig]> = [
  ['otp', { step: { type: 'otp', length: 6, resendAfter: 0, destination: 'u***@example.com' } }],
  ['password', { step: { type: 'password' } }],
  ['set-password', { step: { type: 'set-password' } }],
  ['credential', { step: { type: 'credential', primaryCredentials: ['login'], passkey: true } }],
  ['consent', { step: { type: 'consent', clientName: 'Acme', allowPartial: true, denyUri: '/d', scopes: [{ scope: 'openid', description: 'Know who you are', claims: [], deselectable: true }] } }],
  ['passkey-enroll', { step: { type: 'passkey-enroll', publicKeyOptions: '{}' } }],
  ['confirm-logout', { step: { type: 'confirm-logout', csrfToken: 'x' } }],
  ['conversation-expired', { step: { type: 'conversation-expired', redirectUri: 'https://app.example' } }],
  ['service-unavailable', { step: { type: 'service-unavailable', redirectUri: 'https://app.example' } }],
  ['access-denied', { step: { type: 'access-denied', redirectUri: 'https://app.example' } }],
  ['signed-out', { step: { type: 'signed-out', logoutUris: [], redirectUri: 'https://app.example' } }],
];

for (const [id, config] of accessibilityCases) {
  test(`${id} has no axe violations`, async ({ page }) => {
    await renderForm(page, id, config);

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();

    expect(results.violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
  });
}
