import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';
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

  test('shows the digits as they are typed', async ({ page }) => {
    await renderForm(page, 'otp', { step });

    await page.keyboard.type('482');

    await expect(page.locator('.otp-cell').nth(0)).toHaveText('4');
    await expect(page.locator('.otp-cell').nth(1)).toHaveText('8');
    await expect(page.locator('.otp-cell').nth(2)).toHaveText('2');
    await expect(page.locator('.otp-cell').nth(3)).toHaveText('');
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

    await page.getByLabel(t.password_placeholder, { exact: true }).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/password');
    expect(submitted[0].fields.get('password')).toBe(STRONG_PASSWORD);
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });

  test('refuses a password the policy rejects, without asking the server', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'password', { step });

    await page.getByLabel(t.password_placeholder, { exact: true }).fill('weak');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(page.getByText(t.password_not_allowed)).toBeVisible();
    expect(submitted).toHaveLength(0);
  });

  test('shows a wrong-password error in words', async ({ page }) => {
    const { t } = await renderForm(page, 'password', { step, error: 'password_wrong' });

    await expect(page.getByText(t.password_wrong)).toBeVisible();
  });

  test('reveals and hides the password on request', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'password', { step });
    const field = page.getByLabel(t.password_placeholder, { exact: true });

    await field.fill(STRONG_PASSWORD);
    await expect(field).toHaveAttribute('type', 'password');

    await page.getByRole('button', { name: t.show_password }).click();
    await expect(field).toHaveAttribute('type', 'text');
    await expect(field).toHaveValue(STRONG_PASSWORD);

    await page.getByRole('button', { name: t.hide_password }).click();
    await expect(field).toHaveAttribute('type', 'password');

    await page.getByRole('button', { name: t.continue }).click();
    await expectSubmissions(submitted, 1);
    expect(submitted[0].fields.get('password')).toBe(STRONG_PASSWORD);
  });

  test('ties a rejected password to its field for assistive technology', async ({ page }) => {
    const { t } = await renderForm(page, 'password', { step });
    const field = page.getByLabel(t.password_placeholder, { exact: true });

    await field.fill('weak');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(field).toHaveAttribute('aria-invalid', 'true');
    await expect(field).toHaveAccessibleDescription(t.password_not_allowed);
    await expect(page.getByRole('alert')).toHaveText(t.password_not_allowed);
  });

  test('switches language and sends the chosen one', async ({ page }) => {
    const { submitted, all } = await renderForm(page, 'password', { step });

    await page.locator('.locale-trigger').click();
    await page.getByRole('button', { name: 'Русский', exact: true }).click();
    await expect(page.getByRole('heading', { name: all.ru.title })).toBeVisible();

    await page.getByLabel(all.ru.password_placeholder, { exact: true }).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: all.ru.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].query.get('ui_locale')).toBe('ru');
  });
});

test.describe('set-password', () => {
  const step = { type: 'set-password', passwordRegex: PASSWORD_REGEX };

  test('submits matching passwords', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'set-password', { step });

    await page.getByLabel(t.password_placeholder, { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel(t.confirm_placeholder, { exact: true }).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/set-password');
    expect(submitted[0].fields.get('password')).toBe(STRONG_PASSWORD);
    expect(submitted[0].fields.get('confirm')).toBe(STRONG_PASSWORD);
    expect(submitted[0].fields.get('csrf')).toBe(CSRF);
  });

  test('flags a mismatch as it is typed and does not submit it', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'set-password', { step });

    await page.getByLabel(t.password_placeholder, { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel(t.confirm_placeholder, { exact: true }).fill('Different1Password');
    await expect(page.getByText(t.password_mismatch)).toBeVisible();

    await page.getByRole('button', { name: t.continue }).click();
    expect(submitted).toHaveLength(0);
  });

  test('refuses a password the policy rejects', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'set-password', { step });

    await page.getByLabel(t.password_placeholder, { exact: true }).fill('weak');
    await page.getByLabel(t.confirm_placeholder, { exact: true }).fill('weak');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(page.getByText(t.password_not_allowed)).toBeVisible();
    expect(submitted).toHaveLength(0);
  });
});

test.describe('credential', () => {
  test('email: posts the address to the email challenge', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] } });

    await page.getByLabel(t.email_placeholder, { exact: true }).fill('ada@example.com');
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

    await page.getByLabel(t.login_placeholder, { exact: true }).fill('ada');
    await page.getByLabel(t.password_placeholder, { exact: true }).fill(STRONG_PASSWORD);
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

    await page.getByLabel(t.login_placeholder, { exact: true }).fill('ada');
    await page.getByLabel(t.password_placeholder, { exact: true }).fill('weak');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(page.getByText(t.password_not_allowed)).toBeVisible();
    expect(submitted).toHaveLength(0);
  });

  test('email with an inline password posts to the combined challenge', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['email'], inlinePassword: true },
    });

    await page.getByLabel(t.email_placeholder, { exact: true }).fill('ada@example.com');
    await page.getByLabel(t.password_placeholder, { exact: true }).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/email-password');
  });

  const kz = { region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}' };
  const ru = { region: 'RU', dialCode: '+7', length: 10, regex: '9\\d{9}' };
  const uz = { region: 'UZ', dialCode: '+998', length: 9, regex: '\\d{9}' };
  const kg = { region: 'KG', dialCode: '+996', length: 9, regex: '[2-7]\\d{8}' };
  const phoneStep = (phoneRegions: unknown[]) => ({ type: 'credential', primaryCredentials: ['phone'], phoneRegions });

  test('phone: one dial code is locked and a region sharing it widens what is accepted', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', { step: phoneStep([kz, ru]) });
    const phone = page.getByLabel(t.phone_placeholder);

    await expect(page.getByRole('button', { name: new RegExp(t.phone_country_code) })).toHaveCount(0);
    await expect(page.locator('.phone-code-locked')).toContainText('+7');

    await phone.fill('1012345678');
    await page.getByRole('button', { name: t.continue }).click();
    await expect(phone).toHaveAttribute('aria-invalid', 'true');
    expect(submitted).toHaveLength(0);

    await phone.fill('9012345678');
    await expect(phone).toHaveAttribute('aria-invalid', 'false');
    await expect(phone).toHaveValue('901 234 56 78');
    await page.getByRole('button', { name: t.continue }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].path).toBe('/challenge/phone');
    expect(submitted[0].fields.get('phone')).toBe('+79012345678');
  });

  test('phone: with only one region the other region\'s numbers are refused', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', { step: phoneStep([ru]) });
    const phone = page.getByLabel(t.phone_placeholder);

    await phone.fill('7012345678');
    await page.getByRole('button', { name: t.continue }).click();
    await expect(phone).toHaveAttribute('aria-invalid', 'true');
    expect(submitted).toHaveLength(0);

    await phone.fill('9012345678');
    await page.getByRole('button', { name: t.continue }).click();
    await expectSubmissions(submitted, 1);
    expect(submitted[0].fields.get('phone')).toBe('+79012345678');
  });

  test('phone: a code is picked from the selector and the number is sent with it', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', { step: phoneStep([kz, ru, uz, kg]) });
    const phone = page.getByLabel(t.phone_placeholder);

    await page.getByRole('button', { name: new RegExp(t.phone_country_code) }).click();
    await expect(page.getByRole('option')).toHaveCount(3);
    await expect(page.getByRole('searchbox')).toHaveCount(0);
    await page.getByRole('option', { name: /\+998/ }).click();

    await phone.fill('901234567');
    await page.getByRole('button', { name: t.continue }).click();
    await expectSubmissions(submitted, 1);
    expect(submitted[0].fields.get('phone')).toBe('+998901234567');
  });

  test('phone: a pasted number with a plus switches the code', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', { step: phoneStep([kz, ru, uz, kg]) });
    const phone = page.getByLabel(t.phone_placeholder);

    await phone.fill('+996 700 123 456');
    await expect(page.getByRole('button', { name: new RegExp(t.phone_country_code) })).toContainText('+996');
    await expect(phone).toHaveValue('700 123 456');
    await page.getByRole('button', { name: t.continue }).click();
    await expectSubmissions(submitted, 1);
    expect(submitted[0].fields.get('phone')).toBe('+996700123456');
  });

  test('phone: a long list of codes gets a search box', async ({ page }) => {
    const more = ['AM:+374', 'GE:+995', 'TJ:+992'].map((entry) => {
      const [region, dialCode] = entry.split(':');
      return { region, dialCode, length: 8, regex: '\\d{8}' };
    });
    const { t } = await renderForm(page, 'credential', { step: phoneStep([kz, uz, kg, ...more]) });

    await page.getByRole('button', { name: new RegExp(t.phone_country_code) }).click();
    const search = page.getByRole('searchbox');
    await expect(search).toBeFocused();
    await expect(page.getByRole('option')).toHaveCount(6);

    await search.fill('99');
    await expect(page.getByRole('option')).toHaveCount(4);
    await search.fill('zzz');
    await expect(page.getByText(t.phone_no_results)).toBeVisible();

    await search.fill('+374');
    await search.press('Enter');
    await expect(page.getByRole('button', { name: new RegExp(t.phone_country_code) })).toContainText('+374');
  });

  test('phone: the selector works from the keyboard', async ({ page }) => {
    const { t } = await renderForm(page, 'credential', { step: phoneStep([kz, uz, kg]) });
    const trigger = page.getByRole('button', { name: new RegExp(t.phone_country_code) });

    await trigger.focus();
    await trigger.press('Enter');
    await expect(page.getByRole('option', { name: /\+7/ })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(trigger).toContainText('+998');
    await expect(page.getByLabel(t.phone_placeholder)).toBeFocused();

    await trigger.press('Enter');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });

  test('phone: a tenant with no regions gets the plain E.164 field', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'credential', { step: phoneStep([]) });

    await page.getByLabel(t.phone_placeholder, { exact: true }).fill('+4915112345678');
    await page.getByRole('button', { name: t.continue }).click();
    await expectSubmissions(submitted, 1);
    expect(submitted[0].fields.get('phone')).toBe('+4915112345678');
  });

  test('several first factors share one field named for all of them', async ({ page }) => {
    const { t } = await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email', 'phone'] } });

    await expect(page.getByLabel(`${t.email_placeholder} / ${t.phone_placeholder}`, { exact: true })).toBeVisible();
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

  test('shows the client\'s logo above the card, and the Versola wordmark when there is none or it cannot load', async ({ page }) => {
    const logo = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="%23c33"/></svg>';
    await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] }, logo });
    await expect(page.locator('.page-brand img')).toBeVisible();
    await expect(page.locator('.page-wordmark')).toHaveCount(0);
    expect(await page.locator('.page-brand').evaluate(el => el.nextElementSibling?.classList.contains('container'))).toBe(true);

    const none = await page.context().newPage();
    await renderForm(none, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] } });
    await expect(none.locator('.page-wordmark')).toHaveCount(1);

    const broken = await page.context().newPage();
    await broken.route('**/missing-logo.png', route => route.abort());
    await renderForm(broken, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] }, logo: '/missing-logo.png' });
    await expect(broken.locator('.page-brand img')).toHaveCount(0);
    await expect(broken.locator('.page-wordmark')).toHaveCount(1);
  });

  test('the terminal screens carry an icon, so the card is not a bare title', async ({ page }) => {
    const out = await renderForm(page, 'signed-out', { step: { type: 'signed-out', logoutUris: [], redirectUri: 'https://app.example' } });
    await expect(page.locator('.signed-out-icon svg')).toBeVisible();
    expect(out.t.title).toBeTruthy();
    const confirm = await page.context().newPage();
    await renderForm(confirm, 'confirm-logout', { step: { type: 'confirm-logout', csrfToken: 'x' } });
    await expect(confirm.locator('.logout-icon svg')).toBeVisible();
  });

  test('the brand zone is shared by every step, not only the first', async ({ page }) => {
    for (const [id, step] of [
      ['otp', { type: 'otp', length: 6, resendAfter: 0, destination: 'u***@example.com' }],
      ['password', { type: 'password' }],
      ['passkey-enroll', { type: 'passkey-enroll', publicKeyOptions: '{}' }],
    ] as const) {
      const fresh = await page.context().newPage();
      await renderForm(fresh, id, { step });
      await expect(fresh.locator('.page-brand')).toHaveCount(1);
    }
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

  test('renders the application name in bold inside the translated sentence', async ({ page }) => {
    await renderForm(page, 'consent', { step });

    const name = page.locator('.consent-description strong');
    await expect(name).toHaveText('Acme');
    await expect(page.locator('.consent-description')).toHaveText('Acme wants to access your account.');
    expect(await name.evaluate(el => Number(getComputedStyle(el).fontWeight))).toBeGreaterThanOrEqual(600);
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

  test('keeps a scope the client marked required ticked and submitted', async ({ page }) => {
    const required = scopes.map(s => s.scope === 'email' ? { ...s, deselectable: false } : s);
    const { submitted, t } = await renderForm(page, 'consent', { step: { ...step, scopes: required } });

    await expect(page.getByLabel('Read your email address')).toBeDisabled();
    await expect(page.getByLabel('Read your email address')).toBeChecked();
    await page.getByLabel('Read your profile').uncheck();
    await page.getByRole('button', { name: t.allow_button }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].fields.get('scope')).toBe('openid email');
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

test.describe('consent, with the seeded scope catalog', () => {
  // Shaped as ConversationRenderService builds it from BootstrapService.scopeCatalog.
  const row = (scope: string, description: string, claims: string[], deselectable: boolean) => ({
    scope, description, descriptionLocalizations: { en: description }, claims, claimLocalizations: claims.map(en => ({ en })), deselectable,
  });
  const scopes = [
    row('openid', 'OpenID Connect authentication', ['Unique identifier of the user'], false),
    row('address', 'Postal address', ['Postal address'], true),
    row('email', 'Email address', ['Email address', 'Email address verification status'], true),
    row('offline_access', 'Long-lived access', [], false),
    row('phone', 'Phone number', ['Phone number', 'Phone number verification status'], true),
    row('profile', 'Basic profile information', ['Full name', 'Family name', 'Given name', 'Profile picture URL', 'Locale', 'Time zone', 'Gender', 'Date of birth'], true),
  ];
  const step = { type: 'consent', clientName: 'Acme', allowPartial: true, denyUri: '/d', scopes };

  test('shows every requested scope instead of hiding some behind a scroll box', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 640 });
    await renderForm(page, 'consent', { step });

    const box = page.locator('.consent-scopes-container');
    expect(await box.evaluate(el => el.scrollHeight - el.clientHeight)).toBeLessThanOrEqual(0);
    await expect(page.locator('.consent-scope')).toHaveCount(6);
    await page.getByLabel('Basic profile information').scrollIntoViewIfNeeded();
    await expect(page.getByLabel('Basic profile information')).toBeInViewport();
  });

  test('does not repeat a scope\'s own name as its claim', async ({ page }) => {
    await renderForm(page, 'consent', { step });

    await expect(page.locator('.consent-scope', { hasText: 'Postal address' }).locator('.consent-scope-claims')).toHaveCount(0);
    await expect(page.locator('.consent-scope', { hasText: 'Phone number' }).locator('.consent-scope-claims'))
      .toHaveText('Phone number verification status');
  });

  test('says which scopes are required when the user may drop the others', async ({ page }) => {
    const { t } = await renderForm(page, 'consent', { step });

    await expect(page.locator('.consent-scope-required')).toHaveCount(2);
    await expect(page.locator('.consent-scope', { hasText: 'Long-lived access' }).locator('.consent-scope-required')).toHaveText(t.required);

    const all = await page.context().newPage();
    await renderForm(all, 'consent', { step: { ...step, allowPartial: false, scopes: scopes.map(s => ({ ...s, deselectable: false })) } });
    await expect(all.locator('.consent-scope-required')).toHaveCount(0);
  });

  const logo = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40"><rect width="120" height="40" fill="%23c33"/></svg>';

  test('shows the partner\'s icon inside the card, its initial when there is none, and never a text logo there', async ({ page }) => {
    await renderForm(page, 'consent', { step: { ...step, logoUri: logo } });
    await expect(page.locator('.container .consent-icon img')).toBeVisible();

    const bare = await page.context().newPage();
    await renderForm(bare, 'consent', { step });
    await expect(bare.locator('.container .consent-icon-initial')).toHaveText('A');

    const broken = await page.context().newPage();
    await renderForm(broken, 'consent', { step: { ...step, logoUri: '/missing-logo.png' } });
    await expect(broken.locator('.container .consent-icon-initial')).toHaveText('A');

    // The brand above the card is the identity provider's, never the partner's.
    for (const p of [page, bare, broken]) {
      await expect(p.locator('.container .page-wordmark')).toHaveCount(0);
      await expect(p.locator('.page-brand .consent-icon, .page-brand-initial')).toHaveCount(0);
    }
  });

  test('puts the identity provider\'s logo, not the partner\'s, in the brand zone', async ({ page }) => {
    const idp = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="%2336c"/></svg>';
    await renderForm(page, 'consent', { step: { ...step, logoUri: logo }, logo: idp });
    await expect(page.locator('.page-brand img')).toHaveAttribute('src', idp);
    await expect(page.locator('.page-wordmark')).toHaveCount(0);
  });

  test('names the signed-in user, with a picture when there is one', async ({ page }) => {
    const { t } = await renderForm(page, 'consent', { step: { ...step, user: { name: 'Anna Petrova', picture: logo } } });
    await expect(page.getByText(t.signed_in_as)).toBeVisible();
    await expect(page.locator('.consent-user-name')).toHaveText('Anna Petrova');
    await expect(page.locator('img.consent-avatar')).toBeVisible();

    const plain = await page.context().newPage();
    await renderForm(plain, 'consent', { step: { ...step, user: { identifier: 'a***a@example.com' } } });
    await expect(plain.locator('.consent-user-name')).toHaveText('a***a@example.com');
    await expect(plain.locator('.consent-avatar')).toHaveText('A');

    const none = await page.context().newPage();
    await renderForm(none, 'consent', { step });
    await expect(none.locator('.consent-user')).toHaveCount(0);
  });

  for (const scheme of ['light', 'dark'] as const) {
    test(`has no axe violations in the ${scheme} scheme`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await renderForm(page, 'consent', { step });

      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(results.violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
    });
  }
});

test.describe('passkey-enroll', () => {
  const step = { type: 'passkey-enroll', publicKeyOptions: '{}' };

  test('refuses a name the server would reject', async ({ page }) => {
    const { submitted, t } = await renderForm(page, 'passkey-enroll', { step });

    await page.getByLabel(t.name_placeholder, { exact: true }).fill(' leading space');
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

  test('sends the chosen language with the confirmation', async ({ page }) => {
    const { submitted, all } = await renderForm(page, 'confirm-logout', { step: { type: 'confirm-logout', csrfToken: 'logout-csrf' } });

    await page.getByLabel('Language').selectOption('ru');
    await page.getByRole('button', { name: all.ru.confirm_button }).click();

    await expectSubmissions(submitted, 1);
    expect(submitted[0].query.get('ui_locale')).toBe('ru');
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

for (const scheme of ['light', 'dark'] as const) {
test(`credential phone selector has no axe violations in the ${scheme} scheme, closed or open`, async ({ page }) => {
  await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
  // axe reads the colours as painted, so a transition still running would be scanned half way.
  const region = (code: string, dialCode: string) => ({ region: code, dialCode, length: 9, regex: '\\d{9}' });
  const { t } = await renderForm(page, 'credential', {
    step: { type: 'credential', primaryCredentials: ['phone'], phoneRegions: ['AM:+374', 'GE:+995', 'UZ:+998', 'KG:+996', 'TJ:+992', 'AZ:+994'].map(e => region(...(e.split(':') as [string, string]))) },
  });
  const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();

  expect((await scan()).violations.map(v => v.id)).toEqual([]);
  await page.getByRole('button', { name: new RegExp(t.phone_country_code) }).click();
  await expect(page.getByRole('searchbox')).toBeVisible();
  expect((await scan()).violations.map(v => v.id)).toEqual([]);
});
}

test('credential phone field reflows at 320px with the selector open', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  const region = (code: string, dialCode: string) => ({ region: code, dialCode, length: 9, regex: '\\d{9}' });
  const { t } = await renderForm(page, 'credential', {
    step: { type: 'credential', primaryCredentials: ['phone'], phoneRegions: [region('UZ', '+998'), region('KG', '+996')] },
  });
  const overflow = () => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

  expect(await overflow()).toBeLessThanOrEqual(0);
  await page.getByRole('button', { name: new RegExp(t.phone_country_code) }).click();
  expect(await overflow()).toBeLessThanOrEqual(0);
});

for (const [id, config] of accessibilityCases) {
  test(`${id} has no axe violations in the dark colour scheme`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await renderForm(page, id, config);

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();

    expect(results.violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
  });

  test(`${id} reflows at 320px without horizontal scrolling`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 640 });
    await renderForm(page, id, config);

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
}

/**
 * A theme is a full replacement of common.css, so a tenant that forked the previous one keeps it
 * while the forms are updated underneath it. tests/fixtures/previous-common.css is that frozen
 * copy; the forms must stay usable over it.
 */
test.describe('over a theme forked from the previous common.css', () => {
  const previous = { commonCss: 'fixtures/previous-common.css' };

  test('does not flag empty required fields as invalid', async ({ page }) => {
    await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'], inlinePassword: true } }, previous);

    for (const field of await page.locator('.input-field').all()) {
      await expect(field).not.toHaveCSS('border-color', 'rgb(220, 38, 38)');
    }
  });

  test('draws the ticked state of a consent scope', async ({ page }) => {
    await renderForm(page, 'consent', {
      step: { type: 'consent', clientName: 'Acme', allowPartial: true, denyUri: '/d', scopes: [{ scope: 'email', description: 'Read your email address', claims: [], deselectable: true }] },
    }, previous);

    await expect(page.getByLabel('Read your email address')).not.toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
  });

  test('keeps the password toggle next to its field', async ({ page }) => {
    const { t } = await renderForm(page, 'password', { step: { type: 'password' } }, previous);

    const field = await page.getByLabel(t.password_placeholder, { exact: true }).boundingBox();
    const toggle = await page.getByRole('button', { name: t.show_password }).boundingBox();
    expect(toggle!.x).toBeGreaterThan(field!.x + field!.width / 2);
    expect(toggle!.y).toBeGreaterThanOrEqual(field!.y);
    expect(toggle!.y + toggle!.height).toBeLessThanOrEqual(field!.y + field!.height);
  });
});

/**
 * A form version stored before the redesign keeps running over the current common.css (the version
 * was never replaced, or it is previewed in the console). Its markup is reproduced here as stored.
 */
test.describe('older form versions over the current common.css', () => {
  const render = async (page: import('@playwright/test').Page, body: string) => {
    await page.setViewportSize({ width: 375, height: 760 });
    await page.setContent(`<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1"><style>${await readFile(new URL('../forms/common.css', import.meta.url), 'utf8')}</style><div id="versola-form-root">${body}</div>`);
  };

  test('centres the label of a link button that sets display:block itself', async ({ page }) => {
    await render(page, '<div class="container"><h1>Access Denied</h1><a href="#" class="btn btn-primary" style="display:block;text-align:center;text-decoration:none;">Return</a></div>');

    const link = page.getByRole('link', { name: 'Return' });
    await expect(link).toHaveCSS('display', 'flex');
    const [box, text] = await link.evaluate(el => {
      const range = document.createRange();
      range.selectNodeContents(el);
      return [el.getBoundingClientRect().toJSON(), range.getBoundingClientRect().toJSON()];
    });
    expect(Math.abs((text.y + text.height / 2) - (box.y + box.height / 2))).toBeLessThanOrEqual(2);
  });

  test('keeps the logo mark an older sign-in form drew inside the card at its own size', async ({ page }) => {
    await render(page, '<div class="container"><div class="brand-mark"><svg viewBox="0 0 64 64"><path d="M32 6 54 12v18Q54 48 32 58 10 48 10 30V12z" stroke="currentColor" fill="none"/></svg></div><h1>Sign In</h1></div>');

    const box = await page.locator('.brand-mark svg').boundingBox();
    expect(box!.width).toBeLessThanOrEqual(48);
    expect(box!.height).toBeLessThanOrEqual(48);
  });

  test('fills the width of a phone, however little the card holds', async ({ page }) => {
    await render(page, '<div class="container"><h1>Hi</h1></div>');

    const width = await page.locator('.container').evaluate(el => el.getBoundingClientRect().width);
    expect(width).toBe(375);
  });
});
