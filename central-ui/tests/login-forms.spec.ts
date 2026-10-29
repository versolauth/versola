import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { createHash } from 'node:crypto';
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

  test('a step left with no code sent asks for it by itself, as a resend, and answers the proof it is given', async ({ page }) => {
    const seen: Array<{ path: string; fields: URLSearchParams }> = [];
    const { t } = await renderForm(page, 'otp', { step: { ...step, resendAfter: 60, pending: true } }, {
      beforeRender: async (p) => {
        await p.route('**/challenge/otp/resend*', (route) => {
          const request = route.request();
          seen.push({ path: new URL(request.url()).pathname, fields: new URLSearchParams(request.postData() ?? '') });
          const body = seen.length === 1 ? { challenge: { kind: 'pow', token: 'signed.token', params: { seed: 'abc', difficulty: '12' } } } : { next: '/challenge', otp: true };
          return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
        });
      },
    });

    await expect.poll(() => seen.length).toBe(2);
    expect(seen.map((request) => request.path)).toEqual(['/challenge/otp/resend', '/challenge/otp/resend']);
    expect(seen[0].fields.get('proof_inline')).toBe('1');
    expect(seen[1].fields.get('proof_token')).toBe('signed.token');
    const digest = createHash('sha256').update(`abc:${seen[1].fields.get('proof_solution')}`).digest();
    expect(digest[0]).toBe(0);
    expect(digest[1] >> 4).toBe(0);
    // Shown in place: the page is the same one, with the wait for the next code running, and the code field ready.
    await expect(page.getByText(t.resend_in.replace('{seconds}', '60'))).toBeVisible();
    await expect(page.getByRole('textbox')).toBeFocused();
    await expect(page.getByRole('heading', { name: t.title })).toBeVisible();
  });

  test('a resend that sends a code is shown in place too, with the wait of the step started again', async ({ page }) => {
    const { t } = await renderForm(page, 'otp', { step: { ...step, resendAfter: 0, resendInterval: 45 } });
    await page.route('**/challenge/otp/resend*', (route) =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify({ next: '/challenge', otp: true }) }));
    const navigations: string[] = [];
    page.on('framenavigated', (frame) => frame === page.mainFrame() && navigations.push(frame.url()));

    await page.getByRole('button', { name: t.resend_button }).click();

    await expect(page.getByText(t.resend_in.replace('{seconds}', '45'))).toBeVisible();
    expect(navigations).toEqual([]);
  });

  test('a resend whose answer is not a sent code leaves the page for the step it names', async ({ page }) => {
    const { t } = await renderForm(page, 'otp', { step: { ...step, resendAfter: 0, resendInterval: 45 } });
    await page.route('**/challenge/otp/resend*', (route) =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify({ next: '/challenge', otp: false }) }));

    await page.getByRole('button', { name: t.resend_button }).click();

    await expect(page.getByText('next page')).toBeVisible();
  });

  test('a step with a code sent does not ask for one', async ({ page }) => {
    const { submitted } = await renderForm(page, 'otp', { step });
    await page.waitForTimeout(300);
    expect(submitted).toHaveLength(0);
  });

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
    await page.getByRole('option', { name: 'ru', exact: true }).click();
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

  type Seen = { path: string; query: URLSearchParams; fields: URLSearchParams };

  /** Answers `pattern` with the next of `bodies` as JSON, and keeps what was sent, which the form harness's own route no longer sees. */
  const answerWith = async (page: Page, pattern: string, bodies: object[], seen: Seen[]) =>
    page.route(pattern, (route) => {
      const request = route.request();
      // Only what a script sends is answered here; a plain form post goes on to the harness's route.
      if (!request.postData()?.includes('proof_inline')) return route.fallback();
      const url = new URL(request.url());
      seen.push({ path: url.pathname, query: url.searchParams, fields: new URLSearchParams(request.postData() ?? '') });
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(bodies[Math.min(seen.length, bodies.length) - 1]) });
    });

  const challenge = (params: object, kind = 'pow') => ({ challenge: { kind, token: 'signed.token', params } });

  test('email: a request that must prove itself solves it unseen and makes the same request again with the answer', async ({ page }) => {
    const { t } = await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] } });
    const seen: Seen[] = [];
    await answerWith(page, '**/challenge/email*', [challenge({ seed: 'abc', difficulty: '12' }), { next: '/challenge' }], seen);
    const screens: string[] = [];
    page.on('framenavigated', (frame) => frame === page.mainFrame() && screens.push(new URL(frame.url()).pathname));

    await page.getByLabel(t.email_placeholder, { exact: true }).fill('ada@example.com');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(page.getByText('next page')).toBeVisible();
    // The same POST twice, from the credential page itself, then the one navigation to the next step.
    expect(seen.map((request) => request.path)).toEqual(['/challenge/email', '/challenge/email']);
    expect(screens).not.toContain('/challenge/proof');
    const [asked, answered] = seen.map((request) => request.fields);
    expect(asked.get('proof_inline')).toBe('1');
    expect(asked.has('proof_token')).toBe(false);
    for (const fields of [asked, answered]) {
      expect(fields.get('email')).toBe('ada@example.com');
      expect(fields.get('csrf')).toBe(CSRF);
    }
    expect(answered.get('proof_token')).toBe('signed.token');
    const digest = createHash('sha256').update(`abc:${answered.get('proof_solution')}`).digest();
    expect(digest[0]).toBe(0);
    expect(digest[1] >> 4).toBe(0);
  });

  test('email: a challenge the page cannot answer ends in the plain form post, which the server refuses', async ({ page }) => {
    const { t, submitted } = await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] } });
    const seen: Seen[] = [];
    await answerWith(page, '**/challenge/email*', [challenge({}, 'widget')], seen);

    await page.getByLabel(t.email_placeholder, { exact: true }).fill('ada@example.com');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(page.getByText('next page')).toBeVisible();
    expect(seen).toHaveLength(1);
    expect(submitted.map((request) => request.path)).toEqual(['/challenge/email']);
    expect(submitted[0].fields.has('proof_inline')).toBe(false);
  });

  test('email: a redirect comes back as data, so that a submit sent from script follows it', async ({ page }) => {
    const { t } = await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] } });
    const seen: Seen[] = [];
    await answerWith(page, '**/challenge/email*', [{ next: '/challenge' }], seen);

    await page.getByLabel(t.email_placeholder, { exact: true }).fill('ada@example.com');
    await page.getByRole('button', { name: t.continue }).click();

    await expect(page.getByText('next page')).toBeVisible();
    expect(seen.map((request) => request.fields.get('proof_inline'))).toEqual(['1']);
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

  test('a passkey-only step shows no field and no button while the passkey prompt is open', async ({ page }) => {
    // The options request never answers, so the page stays as the user sees it while the browser prompts.
    await page.addInitScript(() => { window.fetch = () => new Promise(() => {}); });
    const { t, submitted } = await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['phone'], passkey: true, passkeyOnly: true },
    });

    await expect(page.getByRole('heading', { name: t.passkey_only_title })).toBeVisible();
    await expect(page.getByText(t.passkey_only_hint)).toBeVisible();
    await expect(page.locator('form')).toHaveCount(0);
    await expect(page.locator('.btn')).toHaveCount(0);
    await expect(page.getByRole('textbox')).toHaveCount(0);
    expect(submitted).toHaveLength(0);
  });

  test('a passkey-only step ends on the access page when the passkey cannot be produced', async ({ page }) => {
    // The harness answers the options request with a page, not a ceremony: no usable passkey. The answer is held
    // back for a moment so the form can be seen before it moves on.
    await page.addInitScript(() => {
      const real = window.fetch.bind(window);
      window.fetch = (...args) => new Promise(resolve => setTimeout(() => resolve(real(...args)), 800));
    });
    const { submitted } = await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['phone'], passkey: true, passkeyOnly: true },
    });

    await expect.poll(() => submitted.some(r => r.path === '/challenge/passkey/unavailable')).toBe(true);
    const denied = submitted.find(r => r.path === '/challenge/passkey/unavailable')!;
    expect(denied.method).toBe('POST');
    expect(denied.fields.get('csrf')).toBe(CSRF);
  });

  test('shows the client\'s logo above the card, and the Versola wordmark when there is none or it cannot load', async ({ page }) => {
    const logo = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="%23c33"/></svg>';
    await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] }, logo: { light: logo, dark: logo } });
    await expect(page.locator('.page-brand img').first()).toBeAttached();
    await expect(page.locator('.page-wordmark')).toHaveCount(0);
    expect(await page.locator('.page-brand').evaluate(el => el.nextElementSibling?.classList.contains('container'))).toBe(true);

    const none = await page.context().newPage();
    await renderForm(none, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] } });
    await expect(none.locator('.page-wordmark')).toHaveCount(1);

    const broken = await page.context().newPage();
    await broken.route('**/missing-logo.png', route => route.abort());
    await renderForm(broken, 'credential', { step: { type: 'credential', primaryCredentials: ['email'] }, logo: { light: '/missing-logo.png', dark: '/missing-logo.png' } });
    await expect(broken.locator('.page-brand img')).toHaveCount(0);
    await expect(broken.locator('.page-wordmark')).toHaveCount(1);
  });

  test('shows the logo made for the current colour scheme, bare, and follows the scheme toggle', async ({ page }) => {
    const svg = (fill: string) => `data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40"><rect width="120" height="40" fill="%23${fill}"/></svg>`;
    await renderForm(page, 'credential', {
      step: { type: 'credential', primaryCredentials: ['email'] },
      logo: { light: svg('111111'), dark: svg('eeeeee') },
    });
    const light = page.locator('.page-brand img.page-logo-light');
    const dark = page.locator('.page-brand img.page-logo-dark');
    const maskOf = (locator: typeof light) => locator.evaluate(el => getComputedStyle(el).maskImage);

    await page.evaluate(() => { document.documentElement.style.colorScheme = 'light'; });
    expect(await maskOf(light)).toContain('rgb(0, 0, 0)');
    expect(await maskOf(dark)).toContain('rgba(0, 0, 0, 0)');

    await page.evaluate(() => { document.documentElement.style.colorScheme = 'dark'; });
    expect(await maskOf(dark)).toContain('rgb(0, 0, 0)');
    expect(await maskOf(light)).toContain('rgba(0, 0, 0, 0)');

    // Bare on the page: no tile, no padding or background around the artwork.
    const frame = await page.locator('.page-logo').evaluate(el => {
      const style = getComputedStyle(el);
      return { background: style.backgroundColor, padding: style.padding, shadow: style.boxShadow };
    });
    expect(frame).toEqual({ background: 'rgba(0, 0, 0, 0)', padding: '0px', shadow: 'none' });
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

  test('draws a scope the user cannot drop as a plain ticked one, not dimmed', async ({ page }) => {
    await renderForm(page, 'consent', { step });

    const required = page.getByLabel('OpenID Connect authentication');
    await expect(required).toBeDisabled();
    await expect(required).toBeChecked();
    await expect(required).toHaveCSS('opacity', '1');
    await expect(page.getByLabel('Email address')).toHaveCSS('background-color', await required.evaluate(el => getComputedStyle(el).backgroundColor));
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
    await renderForm(page, 'consent', { step: { ...step, logoUri: logo }, logo: { light: idp, dark: idp } });
    await expect(page.locator('.page-brand img.page-logo-light')).toHaveAttribute('src', idp);
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

    await page.getByLabel(t.name_label, { exact: true }).fill(' leading space');
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

    await page.locator('.locale-trigger').click();
    await page.getByRole('option', { name: 'ru', exact: true }).click();
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

test('the card fills the width of a phone, however little it holds', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 760 });
  await page.setContent(`<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1"><style>${await readFile(new URL('../forms/common.css', import.meta.url), 'utf8')}</style><div id="versola-form-root"><div class="container"><h1>Hi</h1></div></div>`);

  const width = await page.locator('.container').evaluate(el => el.getBoundingClientRect().width);
  expect(width).toBe(375);
});

test.describe('the partner\'s app and the languages, as the server tells the form', () => {
  const logo = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="%23b4348a"/></svg>';
  const partner = { name: 'Storefront', logo, partner: true };
  const signIn = ['credential', 'otp', 'password', 'set-password', 'passkey-enroll'] as const;
  const stepOf = (id: string) => ({
    credential: { type: 'credential', primaryCredentials: ['email'] },
    otp: { type: 'otp', length: 6, resendAfter: 0, destination: 'u***@example.com' },
    password: { type: 'password' },
    'set-password': { type: 'set-password' },
    'passkey-enroll': { type: 'passkey-enroll', publicKeyOptions: '{}' },
  } as Record<string, Record<string, unknown>>)[id];

  for (const id of signIn) {
    test(`${id}: names a partner\'s app inside the card, with its icon`, async ({ page }) => {
      await renderForm(page, id, { step: stepOf(id), client: partner });

      const badge = page.locator('.container .client-badge');
      await expect(badge.locator('.client-badge-name')).toHaveText('Storefront');
      await expect(badge.locator('img')).toBeVisible();
      // Inside the card, directly above its heading, and nowhere in the brand zone over the card.
      const card = page.locator('.container');
      expect(await badge.evaluate(el => el.parentElement?.classList.contains('container'))).toBe(true);
      const badgeBox = (await badge.boundingBox())!;
      const cardBox = (await card.boundingBox())!;
      const headingBox = (await card.locator('h1').first().boundingBox())!;
      expect(badgeBox.y).toBeGreaterThanOrEqual(cardBox.y);
      expect(badgeBox.y + badgeBox.height).toBeLessThanOrEqual(headingBox.y);
      expect(await badge.locator('img').getAttribute('referrerpolicy')).toBe('no-referrer');
      await expect(page.locator('.page-brand .client-badge')).toHaveCount(0);
    });
  }

  test('an app of the identity provider\'s own, or no client at all, is not announced', async ({ page }) => {
    await renderForm(page, 'credential', { step: stepOf('credential'), client: { name: 'Storefront', logo, partner: false } });
    await expect(page.locator('.client-badge')).toHaveCount(0);

    const none = await page.context().newPage();
    await renderForm(none, 'credential', { step: stepOf('credential') });
    await expect(none.locator('.client-badge')).toHaveCount(0);
  });

  test('shows the name\'s initial on a tile when the logo is missing or does not load', async ({ page }) => {
    await renderForm(page, 'credential', { step: stepOf('credential'), client: { name: 'Storefront', partner: true } });
    await expect(page.locator('.client-badge-initial')).toHaveText('S');

    const broken = await page.context().newPage();
    await broken.route('**/missing.png', route => route.abort());
    await renderForm(broken, 'credential', { step: stepOf('credential'), client: { name: 'Storefront', logo: '/missing.png', partner: true } });
    await expect(broken.locator('.client-badge-initial')).toHaveText('S');
  });

  test('draws nothing when the logo does not load and the client has no name', async ({ page }) => {
    await page.route('**/missing.png', route => route.abort());
    await renderForm(page, 'credential', { step: stepOf('credential'), client: { logo: '/missing.png', partner: true } });
    await expect(page.locator('.container h1').first()).toBeVisible();
    await expect(page.locator('.client-badge')).toHaveCount(0);
  });

  test('a long unbroken client name wraps inside the card at 320px', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 640 });
    await renderForm(page, 'credential', { step: stepOf('credential'), client: { name: 'A'.repeat(80), logo, partner: true } });

    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
  });

  for (const scheme of ['light', 'dark'] as const) {
    test(`the badge has no axe violations in the ${scheme} scheme`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await renderForm(page, 'credential', { step: stepOf('credential'), client: partner });

      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(results.violations.map(v => `${v.id}: ${v.help}`)).toEqual([]);
    });
  }

  test('the language switcher shows the names configured under Locales, and the bare code where one is missing', async ({ page }) => {
    await renderForm(page, 'password', { step: stepOf('password'), localeNames: { en: 'English (US)', ru: 'Русский язык' } });
    await expect(page.locator('.locale-trigger')).toHaveText('English (US)');
    await page.locator('.locale-trigger').click();
    await expect(page.getByRole('option', { name: 'Русский язык', exact: true })).toBeVisible();

    const partly = await page.context().newPage();
    await renderForm(partly, 'password', { step: stepOf('password'), localeNames: { en: 'English (US)' } });
    await partly.locator('.locale-trigger').click();
    await expect(partly.getByRole('option', { name: 'ru', exact: true })).toBeVisible();
  });

  test('without localeNames the switcher shows the bare codes, with them the names', async ({ page }) => {
    await renderForm(page, 'password', { step: stepOf('password') });
    await expect(page.locator('.locale-trigger')).toHaveText('en');
    await page.locator('.locale-trigger').click();
    await expect(page.getByRole('option', { name: 'ru', exact: true })).toBeVisible();

    const named = await page.context().newPage();
    await renderForm(named, 'password', { step: stepOf('password'), localeNames: { en: 'English', ru: 'Русский' } });
    await expect(named.locator('.locale-trigger')).toHaveText('English');
    await named.locator('.locale-trigger').click();
    await expect(named.getByRole('option', { name: 'Русский', exact: true })).toBeVisible();
  });

  test('the badge shows the client\'s name in the form\'s current language', async ({ page }) => {
    const client = { name: 'Storefront', names: { en: 'Storefront', ru: 'Витрина' }, partner: true };
    const { all } = await renderForm(page, 'password', { step: stepOf('password'), client });
    await expect(page.locator('.client-badge-name')).toHaveText('Storefront');

    await page.locator('.locale-trigger').click();
    await page.getByRole('option', { name: 'ru', exact: true }).click();
    await expect(page.getByRole('heading', { name: all.ru.title })).toBeVisible();
    await expect(page.locator('.client-badge-name')).toHaveText('Витрина');
    await expect(page.locator('.client-badge-initial')).toHaveText('В');
  });

  test('the consent sentence names the client in the current language too', async ({ page }) => {
    const step = {
      type: 'consent', clientName: 'Storefront', clientNames: { en: 'Storefront', ru: 'Витрина' }, allowPartial: true, denyUri: '/d',
      scopes: [{ scope: 'openid', description: 'Know who you are', claims: [], deselectable: true }],
    };
    const { all } = await renderForm(page, 'consent', { step });
    await expect(page.locator('.consent-client')).toHaveText('Storefront');

    await page.locator('.locale-trigger').click();
    await page.getByRole('option', { name: 'ru', exact: true }).click();
    await expect(page.getByRole('heading', { name: all.ru.title })).toBeVisible();
    await expect(page.locator('.consent-client')).toHaveText('Витрина');
  });

  test('a configured logo that does not load and has no name leaves no empty badge; images send no referrer', async ({ page }) => {
    await page.route('**/missing.png', route => route.abort());
    await renderForm(page, 'credential', { step: stepOf('credential'), client: { logo: '/missing.png', partner: true } });
    await expect(page.locator('.client-badge')).toHaveCount(0);

    const shown = await page.context().newPage();
    await renderForm(shown, 'credential', { step: stepOf('credential'), client: { name: 'Storefront', logo, partner: true } });
    await expect(shown.locator('.client-badge img')).toHaveAttribute('referrerpolicy', 'no-referrer');
  });
});

test.describe('the language dropdown, from the keyboard', () => {
  const localeNames = { en: 'English', ru: 'Русский' };

  test('opens with the arrows, moves, and picks with Enter; focus returns to the button', async ({ page }) => {
    const { submitted, all } = await renderForm(page, 'password', { step: { type: 'password' }, localeNames });
    const trigger = page.getByRole('button', { name: 'English' });
    await expect(trigger).toHaveAttribute('aria-haspopup', 'listbox');
    await expect(trigger).toHaveAttribute('aria-expanded', 'false');

    await trigger.focus();
    await page.keyboard.press('ArrowDown');
    await expect(trigger).toHaveAttribute('aria-expanded', 'true');
    const listbox = page.getByRole('listbox');
    await expect(listbox.getByRole('option', { name: 'English' })).toHaveAttribute('aria-selected', 'true');
    await expect(listbox.getByRole('option', { name: 'English' })).toBeFocused();

    await page.keyboard.press('ArrowDown');
    await expect(listbox.getByRole('option', { name: 'Русский' })).toBeFocused();
    await page.keyboard.press('Home');
    await expect(listbox.getByRole('option', { name: 'English' })).toBeFocused();
    await page.keyboard.press('End');
    await page.keyboard.press('Enter');

    await expect(page.getByRole('heading', { name: all.ru.title })).toBeVisible();
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Русский' })).toBeFocused();

    await page.getByLabel(all.ru.password_placeholder, { exact: true }).fill(STRONG_PASSWORD);
    await page.getByRole('button', { name: all.ru.continue }).click();
    await expectSubmissions(submitted, 1);
    expect(submitted[0].query.get('ui_locale')).toBe('ru');
  });

  test('Space picks, Escape closes without picking, an outside click closes', async ({ page }) => {
    const { all } = await renderForm(page, 'password', { step: { type: 'password' }, localeNames });
    const trigger = page.getByRole('button', { name: 'English' });

    await trigger.press('Enter');
    await expect(page.getByRole('listbox')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(trigger).toBeFocused();
    await expect(page.getByRole('heading', { name: all.en.title })).toBeVisible();

    await trigger.click();
    await expect(trigger).toHaveAttribute('aria-expanded', 'true');
    await page.getByRole('heading').click();
    await expect(page.getByRole('listbox')).toHaveCount(0);

    await trigger.click();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press(' ');
    await expect(page.getByRole('heading', { name: all.ru.title })).toBeVisible();
  });

  test('confirm-logout: the language can be changed by keyboard alone and is sent', async ({ page }) => {
    const { submitted, all } = await renderForm(page, 'confirm-logout', { step: { type: 'confirm-logout', csrfToken: 'x' }, localeNames });

    await page.getByRole('button', { name: 'English' }).press('ArrowDown');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: all.ru.title })).toBeVisible();

    await page.getByRole('button', { name: all.ru.confirm_button }).press('Enter');
    await expectSubmissions(submitted, 1);
    expect(submitted[0].query.get('ui_locale')).toBe('ru');
  });

  for (const scheme of ['light', 'dark'] as const) {
    test(`the open dropdown has no axe violations in the ${scheme} scheme`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
      await renderForm(page, 'confirm-logout', { step: { type: 'confirm-logout', csrfToken: 'x' }, localeNames });
      await page.getByRole('button', { name: 'English' }).press('ArrowDown');
      await expect(page.getByRole('listbox')).toBeVisible();

      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(results.violations.map(v => `${v.id}: ${v.help}`)).toEqual([]);
    });
  }
});

test.describe('states the forms keep drawing', () => {
  test('an empty required field is not flagged as invalid before the user typed', async ({ page }) => {
    await renderForm(page, 'credential', { step: { type: 'credential', primaryCredentials: ['email'], inlinePassword: true } });

    const fields = await page.locator('.input-field').all();
    expect(fields.length).toBeGreaterThan(0);
    for (const field of fields) {
      await expect(field).not.toHaveAttribute('aria-invalid', 'true');
      await expect(field).not.toHaveCSS('border-color', 'rgb(220, 38, 38)');
    }
  });

  test('a ticked consent scope is drawn as ticked, an unticked one is not', async ({ page }) => {
    await renderForm(page, 'consent', {
      step: { type: 'consent', clientName: 'Acme', allowPartial: true, denyUri: '/d', scopes: [{ scope: 'email', description: 'Read your email address', claims: [], deselectable: true }] },
    });
    const box = page.getByLabel('Read your email address');

    await expect(box).toBeChecked();
    const ticked = await box.evaluate(el => getComputedStyle(el).backgroundColor);
    expect(ticked).not.toBe('rgba(0, 0, 0, 0)');
    await box.uncheck();
    expect(await box.evaluate(el => getComputedStyle(el).backgroundColor)).not.toBe(ticked);
  });

  test('the password toggle sits inside its field, on the right', async ({ page }) => {
    const { t } = await renderForm(page, 'password', { step: { type: 'password' } });

    const field = await page.getByLabel(t.password_placeholder, { exact: true }).boundingBox();
    const toggle = await page.getByRole('button', { name: t.show_password }).boundingBox();
    expect(toggle!.x).toBeGreaterThan(field!.x + field!.width / 2);
    expect(toggle!.y).toBeGreaterThanOrEqual(field!.y);
    expect(toggle!.y + toggle!.height).toBeLessThanOrEqual(field!.y + field!.height);
  });
});
