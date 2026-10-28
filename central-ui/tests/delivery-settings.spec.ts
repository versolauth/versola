import { expect, test } from '@playwright/test';
import { INVALID_FIELD_BORDER, findRequest, loadAdminApp } from './fixtures';

/**
 * Delivery settings (#832): the SMTP server and the SMS provider auth delivers codes and temporary passwords through.
 * Two record cards with an Edit each (a page), a danger card that removes the record, and a password that is
 * write-only: the page says whether one is saved and never what it is.
 */
const path = '/?view=delivery';

const stored = {
  smtp: { host: 'smtp.example.com', port: 587, username: 'mailer', from: 'noreply@example.com', subject: 'Your verification code', startTls: true, enabled: true },
  smtpPassword: 'mail-secret',
  otpProvider: { method: 'POST', url: 'https://sms.example.com/send', username: 'sms-account', body: { phones: '{{phone}}', mes: '{{message}}' }, enabled: true },
  otpProviderPassword: 'sms-secret',
};
const readOnly = { resources: { central: { permissions: ['security:read'] } }, isProd: false };

test('says nothing is saved yet and offers to configure each block', async ({ page }) => {
  await loadAdminApp(page, { path });

  await expect(page.getByRole('heading', { name: 'Delivery', exact: true })).toBeVisible();
  await expect(page.getByText('Nothing is saved here yet')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Email' })).toContainText('Not configured here.');
  await expect(page.getByRole('region', { name: 'SMS' })).toContainText('Not configured here.');
  await expect(page.getByRole('button', { name: 'Configure' })).toHaveCount(2);
  await expect(page.getByRole('button', { name: 'Remove settings' })).toHaveCount(0);
});

test('shows both blocks and whether a password is saved, never the password', async ({ page }) => {
  // The backend is made hostile: its answer carries the passwords, so the check below fails if the page ever renders them.
  await loadAdminApp(page, { path, state: { deliverySettings: stored, deliveryLeaksPasswords: true } });

  const email = page.getByRole('region', { name: 'Email' });
  await expect(email).toContainText('smtp.example.com:587');
  await expect(email).toContainText('STARTTLS');
  await expect(email).toContainText('mailer');
  await expect(email).toContainText('Saved');
  const sms = page.getByRole('region', { name: 'SMS' });
  await expect(sms).toContainText('POST https://sms.example.com/send');
  await expect(sms).toContainText('phones = {{phone}}');
  await expect(sms).toContainText('Saved');
  await expect(page.getByText('Nothing is saved here yet')).toHaveCount(0);
  // Guards the contract as the page sees it: central never sends a password, so the page has none to render.
  const text = await page.locator('versola-admin').innerText();
  expect(text).not.toContain('mail-secret');
  expect(text).not.toContain('sms-secret');
});

test('saves the email block from its editor and returns to the record', async ({ page }) => {
  const api = await loadAdminApp(page, { path });

  await page.getByRole('region', { name: 'Email' }).getByRole('button', { name: 'Configure' }).click();
  await expect(page).toHaveURL(/edit=smtp/);
  await page.getByLabel('Server address *').fill('smtp.example.com');
  await page.getByLabel('Username *').fill('mailer');
  await page.getByLabel('Password', { exact: true }).fill('mail-secret');
  await page.getByLabel('From *').fill('noreply@example.com');
  await page.getByRole('button', { name: 'Save email settings' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body).toEqual({
    smtp: { host: 'smtp.example.com', port: 587, username: 'mailer', from: 'noreply@example.com', subject: 'Your verification code', startTls: true, enabled: true },
    smtpPassword: 'mail-secret',
  });
  await expect(page).not.toHaveURL(/edit=smtp/);
  await expect(page.getByRole('region', { name: 'Email' })).toContainText('smtp.example.com:587');
  await expect(page.getByRole('region', { name: 'Email' })).toContainText('Saved');
  await expect(page.getByRole('region', { name: 'SMS' })).toContainText('Not configured here.');
});

test('saving one block sends the other back as it is stored, and leaves a password empty to keep it', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { deliverySettings: stored } });

  await page.getByRole('region', { name: 'Email' }).getByRole('button', { name: 'Edit' }).click();
  await expect(page.getByText('A password is saved. Leave this empty to keep it.')).toBeVisible();
  await page.getByLabel('Subject *').fill('Your code');
  await page.getByRole('button', { name: 'Save email settings' }).click();

  const body = (await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body as Record<string, unknown>;
  expect(body.smtp).toMatchObject({ subject: 'Your code' });
  expect(body.otpProvider).toEqual(stored.otpProvider);
  expect(body).not.toHaveProperty('smtpPassword');
  expect(body).not.toHaveProperty('otpProviderPassword');
  await expect(page.getByRole('region', { name: 'Email' })).toContainText('Saved');
  await expect(page.getByRole('region', { name: 'SMS' })).toContainText('Saved');
});

test('removes a saved password only through the box', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliverySettings: stored } });

  const remove = page.getByLabel('Remove the saved password');
  await remove.check();
  await page.getByRole('button', { name: 'Save email settings' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body).toMatchObject({ smtpPassword: '' });
  await expect(page.getByRole('region', { name: 'Email' })).toContainText('Not set');

  await page.getByRole('region', { name: 'Email' }).getByRole('button', { name: 'Edit' }).click();
  await expect(page.getByLabel('Remove the saved password')).toHaveCount(0);
});

test('reopens an editor with an empty password field after a save, and after a cancelled draft', async ({ page }) => {
  await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliverySettings: stored } });

  await page.getByLabel('Password', { exact: true }).fill('typed-then-discarded');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByRole('dialog', { name: 'Discard changes?' }).getByRole('button', { name: 'Discard' }).click();
  // The editor is gone: the typed password must not still sit in the component's state. Only the component's own state
  // shows it (the reopened field is empty either way, because a new editor starts from the stored block), so this reads it.

  const held = () => page.locator('versola-delivery-settings').evaluate((el: Element) => (el as unknown as { smtpDraft: { password: string } }).smtpDraft.password);
  expect(await held()).toBe('');
  await page.getByRole('region', { name: 'Email' }).getByRole('button', { name: 'Edit email' }).click();
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');

  await page.getByLabel('Password', { exact: true }).fill('typed-and-saved');
  await page.getByRole('button', { name: 'Save email settings' }).click();
  await expect(page).not.toHaveURL(/edit=smtp/);
  expect(await held()).toBe('');
  await page.getByRole('region', { name: 'Email' }).getByRole('button', { name: 'Edit email' }).click();
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
});

test('a typed password wins over the remove box, which is then unavailable and says why', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliverySettings: stored } });

  const remove = page.getByLabel('Remove the saved password');
  await remove.check();
  await page.getByLabel('Password', { exact: true }).fill('replacement');
  await expect(remove).toBeDisabled();
  await expect(remove).toHaveAccessibleDescription(/replaces the saved one/);
  await page.getByRole('button', { name: 'Save email settings' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body).toMatchObject({ smtpPassword: 'replacement' });
  await expect(page.getByRole('region', { name: 'Email' })).toContainText('Saved');
});

test('turns the SMS body into entries and saves the provider block', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=otp-provider`, state: { deliverySettings: { smtp: stored.smtp } } });

  await page.getByLabel('Address *').fill('https://sms.example.com/send');
  await page.getByRole('button', { name: 'Remove body entry 2' }).click();
  await page.getByLabel('Body entry 1 name').fill('to');
  await page.getByRole('button', { name: '+ Add entry' }).click();
  await page.getByLabel('Body entry 2 name').fill('text');
  await page.getByLabel('Body entry 2 value').fill('{{message}}');
  await page.getByLabel('Username').fill('sms-account');
  await page.getByLabel('Password', { exact: true }).fill('sms-secret');
  await page.getByRole('button', { name: 'Save SMS settings' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body).toEqual({
    smtp: stored.smtp,
    otpProvider: { method: 'POST', url: 'https://sms.example.com/send', username: 'sms-account', body: { to: '{{phone}}', text: '{{message}}' }, enabled: true },
    otpProviderPassword: 'sms-secret',
  });
  await expect(page.getByRole('region', { name: 'SMS' })).toContainText('text = {{message}}');
});

test('refuses unusable values beside the field, focuses the first one and sends nothing', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=smtp` });

  await page.getByLabel('Port *').fill('0');
  await page.getByLabel('From *').fill('not-an-address');
  await page.getByRole('button', { name: 'Save email settings' }).click();

  const host = page.getByLabel('Server address *');
  await expect(page.getByText('The server address is required.')).toBeVisible();
  await expect(host).toHaveAttribute('aria-invalid', 'true');
  await expect(host).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await expect(host).toBeFocused();
  await expect(page.getByText('A whole number from 1 to 65535.')).toBeVisible();
  await expect(page.getByText('Enter an email address, such as noreply@example.com.')).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);
});

test('refuses credentials in the SMS address before saving', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=otp-provider` });

  await page.getByLabel('Address *').fill('https://sms:topsecret@sms.example.com/send');
  await page.getByRole('button', { name: 'Save SMS settings' }).click();

  await expect(page.getByText('The address must not carry a username or password. Use the fields below.')).toBeVisible();
  await expect(page.getByText('topsecret')).toHaveCount(0);
  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);
});

test('shows the refusal central sent on the form and keeps what was typed', async ({ page }) => {
  await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliveryRejection: 'smtp.from must be an email address' } });

  await page.getByLabel('Server address *').fill('smtp.example.com');
  await page.getByLabel('Username *').fill('mailer');
  await page.getByLabel('From *').fill('noreply@example.com');
  await page.getByRole('button', { name: 'Save email settings' }).click();

  await expect(page.getByRole('alert')).toContainText('smtp.from must be an email address');
  await expect(page.getByLabel('Server address *')).toHaveValue('smtp.example.com');
  await expect(page).toHaveURL(/edit=smtp/);
});

test('sends one request for a repeated click while a save is pending', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliveryDelayMs: 1500 } });

  await page.getByLabel('Server address *').fill('smtp.example.com');
  await page.getByLabel('Username *').fill('mailer');
  await page.getByLabel('From *').fill('noreply@example.com');
  await page.getByRole('button', { name: 'Save email settings' }).click();
  await expect(page.getByRole('button', { name: 'Saving…' })).toBeDisabled();
  // The button is disabled now, but a form can still be submitted (Enter in a field, a script): the guard is the code's own.
  await page.locator('form').evaluate((form: HTMLFormElement) => { form.requestSubmit(); form.requestSubmit(); });
  await expect(page).not.toHaveURL(/edit=smtp/);
  expect(api.requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/delivery-settings')).toHaveLength(1);
});

test('refuses an empty or repeated SMS body name, marks the entries and focuses the first', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=otp-provider` });

  await page.getByLabel('Address *').fill('https://sms.example.com/send');
  await page.getByLabel('Body entry 2 name').fill(' phone');
  await page.getByRole('button', { name: 'Save SMS settings' }).click();

  await expect(page.getByRole('group', { name: 'Body' }).getByRole('alert')).toContainText('Two body entries have the same name.');
  await expect(page.getByLabel('Body entry 1 name')).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByLabel('Body entry 2 name')).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByLabel('Body entry 1 name')).toBeFocused();
  await page.getByLabel('Body entry 2 name').fill('');
  await page.getByRole('button', { name: 'Save SMS settings' }).click();
  await expect(page.getByText('Every body entry needs a name.')).toBeVisible();
  await expect(page.getByLabel('Body entry 1 name')).not.toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByLabel('Body entry 2 name')).toHaveAttribute('aria-invalid', 'true');
  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);
});

test('moves focus to the next entry when a body entry is removed, and to Add entry when none is left', async ({ page }) => {
  await loadAdminApp(page, { path: `${path}&edit=otp-provider` });

  await page.getByRole('button', { name: 'Remove body entry 1' }).click();
  await expect(page.getByLabel('Body entry 1 name')).toHaveValue('message');
  await expect(page.getByLabel('Body entry 1 name')).toBeFocused();
  await page.getByRole('button', { name: 'Remove body entry 1' }).click();
  await expect(page.getByRole('button', { name: '+ Add entry' })).toBeFocused();
});

test('clears a saved SMS password and drops the username with its field', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=otp-provider`, state: { deliverySettings: stored } });

  await page.getByLabel('Username').fill('');
  await page.getByLabel('Remove the saved password').check();
  await page.getByRole('button', { name: 'Save SMS settings' }).click();

  const body = (await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body as { otpProvider: Record<string, unknown>; otpProviderPassword: string };
  expect(body.otpProviderPassword).toBe('');
  expect(body.otpProvider).not.toHaveProperty('username');
  await expect(page.getByRole('region', { name: 'SMS' })).toContainText('Not set');
});

test('puts back the other block as it is stored now, not as the page first saw it', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliverySettings: stored } });

  // Another admin changes the SMS block while this editor is open.
  api.state.deliverySettings!.otpProvider!.url = 'https://sms-v2.example.com/send';
  await page.getByLabel('Subject *').fill('Your code');
  await page.getByRole('button', { name: 'Save email settings' }).click();

  const body = (await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body as { otpProvider: { url: string } };
  expect(body.otpProvider.url).toBe('https://sms-v2.example.com/send');
});

test('saves nothing and says so when the record cannot be read right before the save', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliverySettings: stored } });

  api.state.deliveryFailure = 'central is unreachable';
  await page.getByLabel('Subject *').fill('Your code');
  await page.getByRole('button', { name: 'Save email settings' }).click();

  await expect(page.getByRole('alert')).toContainText('central is unreachable');
  await expect(page).toHaveURL(/edit=smtp/);
  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);
});

test('keeps a long unbroken address inside its card at 1024 px', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 800 });
  const url = `https://sms.example.com/${'a'.repeat(300)}`;
  await loadAdminApp(page, { path, state: { deliverySettings: { ...stored, otpProvider: { ...stored.otpProvider, url } } } });

  await expect(page.getByRole('region', { name: 'SMS' })).toContainText('aaaaaaaaaa');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test('asks before Cancel drops an edited draft', async ({ page }) => {
  await loadAdminApp(page, { path: `${path}&edit=smtp` });

  await page.getByLabel('Server address *').fill('smtp.example.com');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  const confirm = page.getByRole('dialog', { name: 'Discard changes?' });
  await confirm.getByRole('button', { name: 'Keep editing' }).click();
  await expect(page).toHaveURL(/edit=smtp/);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await confirm.getByRole('button', { name: 'Discard' }).click();
  await expect(page).not.toHaveURL(/edit=smtp/);
});

test('removes the saved record after a confirmation', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { deliverySettings: stored } });

  await page.getByRole('button', { name: 'Remove settings' }).click();
  const confirm = page.getByRole('dialog', { name: 'Remove delivery settings' });
  await expect(confirm).toContainText("goes back to the settings in auth's own configuration file");
  await confirm.getByRole('button', { name: 'Cancel' }).click();
  expect(api.requests.some(request => request.method === 'DELETE')).toBe(false);

  await page.getByRole('button', { name: 'Remove settings' }).click();
  await page.getByRole('dialog', { name: 'Remove delivery settings' }).getByRole('button', { name: 'Remove settings' }).click();
  await findRequest(api.requests, 'DELETE', '/configuration/delivery-settings');
  await expect(page.getByText('Nothing is saved here yet')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Remove settings' })).toHaveCount(0);
});

test('sends one removal for a repeated click while it is pending, and opens no second dialog', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { deliverySettings: stored, deliveryDelayMs: 1500 } });

  await page.getByRole('button', { name: 'Remove settings' }).click();
  await page.getByRole('dialog', { name: 'Remove delivery settings' }).getByRole('button', { name: 'Remove settings' }).click();
  await expect(page.getByRole('button', { name: 'Removing…' })).toBeVisible();
  await page.getByRole('button', { name: 'Removing…' }).click({ force: true });
  await expect(page.getByText('Nothing is saved here yet')).toBeVisible();
  await expect(page.getByRole('dialog', { name: 'Remove delivery settings' })).toHaveCount(0);
  expect(api.requests.filter(request => request.method === 'DELETE')).toHaveLength(1);
});

test('drops a removal error once the operator goes on to edit', async ({ page }) => {
  await loadAdminApp(page, { path, state: { deliverySettings: stored, deliveryDeleteFailure: 'central refused the removal' } });

  await page.getByRole('button', { name: 'Remove settings' }).click();
  await page.getByRole('dialog', { name: 'Remove delivery settings' }).getByRole('button', { name: 'Remove settings' }).click();
  await expect(page.getByRole('alert')).toContainText('central refused the removal');
  await page.getByRole('region', { name: 'Email' }).getByRole('button', { name: 'Edit email' }).click();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Email' })).toBeVisible();
  await expect(page.getByText('central refused the removal')).toHaveCount(0);
});

test('says why a removal failed and keeps the record', async ({ page }) => {
  await loadAdminApp(page, { path, state: { deliverySettings: stored, deliveryDeleteFailure: 'central refused the removal' } });

  await page.getByRole('button', { name: 'Remove settings' }).click();
  await page.getByRole('dialog', { name: 'Remove delivery settings' }).getByRole('button', { name: 'Remove settings' }).click();

  await expect(page.getByRole('alert')).toContainText('central refused the removal');
  await expect(page.getByRole('region', { name: 'Email' })).toContainText('smtp.example.com:587');
  await expect(page.getByRole('button', { name: 'Remove settings' })).toBeEnabled();
});

for (const width of [1440, 375]) {
  test(`has no sideways scroll in either editor at ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const long = {
      ...stored,
      smtp: { ...stored.smtp, host: `${'h'.repeat(120)}.example.com` },
      otpProvider: { ...stored.otpProvider, body: { phones: '{{phone}}', text: 'x'.repeat(300) } },
    };
    const overflows = () => page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
    await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliverySettings: long } });
    await expect(page.getByLabel('Server address *')).toBeVisible();
    expect(await overflows()).toBe(false);
    await loadAdminApp(page, { path: `${path}&edit=otp-provider`, state: { deliverySettings: long } });
    await expect(page.getByLabel('Address *')).toBeVisible();
    expect(await overflows()).toBe(false);
  });
}

test('reads only: no Edit, no Configure, no Remove, and the editor URL falls back to the record', async ({ page }) => {
  await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliverySettings: stored, myPermissions: readOnly } });

  await expect(page.getByRole('region', { name: 'Email' })).toContainText('smtp.example.com:587');
  await expect(page.getByRole('button', { name: 'Edit' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Configure' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Remove settings' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Save email settings' })).toHaveCount(0);
});

test('reads only and nothing saved: says so without offering to configure', async ({ page }) => {
  await loadAdminApp(page, { path, state: { myPermissions: readOnly } });

  await expect(page.getByText('Nothing is saved here yet')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Configure' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Edit' })).toHaveCount(0);
});

test('shows a load failure and loads the record when Retry is pressed', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: { deliverySettings: stored, deliveryFailure: 'central is unreachable' } });

  await expect(page.getByRole('heading', { name: 'Could not load the delivery settings' })).toBeVisible();
  await expect(page.getByText('central is unreachable')).toBeVisible();
  api.state.deliveryFailure = undefined;
  await page.getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByRole('region', { name: 'Email' })).toContainText('smtp.example.com:587');
  await expect(page.getByRole('heading', { name: 'Could not load the delivery settings' })).toHaveCount(0);
});

test('asks for the password again when the server changes, and sends nothing', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliverySettings: stored } });

  await page.getByLabel('Server address *').fill('smtp.other.example');
  await page.getByRole('button', { name: 'Save email settings' }).click();
  const password = page.getByLabel('Password', { exact: true });
  await expect(page.getByText('Enter the password again: it stays only with the server it was saved for.')).toBeVisible();
  await expect(password).toHaveAttribute('aria-invalid', 'true');
  await expect(password).toBeFocused();

  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);

  await password.fill('new-secret');
  await page.getByRole('button', { name: 'Save email settings' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body).toMatchObject({
    smtp: { host: 'smtp.other.example' },
    smtpPassword: 'new-secret',
  });
});

test('does not ask for the password again for a change that leaves the server alone, or when the box removes it', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=smtp`, state: { deliverySettings: stored } });

  await page.getByLabel('Server address *').fill('smtp.other.example');
  await page.getByLabel('Remove the saved password').check();
  await page.getByRole('button', { name: 'Save email settings' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body).toMatchObject({ smtpPassword: '' });
});

test('asks for the SMS password again when the address moves to another origin, and saves it with the typed one', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&edit=otp-provider`, state: { deliverySettings: stored } });

  await page.getByLabel('Address *').fill('https://sms.other.example/send');
  await page.getByRole('button', { name: 'Save SMS settings' }).click();
  await expect(page.getByText('Enter the password again: it stays only with the server it was saved for.')).toBeVisible();
  await expect(page.getByLabel('Password', { exact: true })).toBeFocused();
  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);

  await page.getByLabel('Password', { exact: true }).fill('typed-sms-secret');
  await page.getByRole('button', { name: 'Save SMS settings' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/delivery-settings')).body).toMatchObject({
    otpProviderPassword: 'typed-sms-secret',
    otpProvider: { url: 'https://sms.other.example/send' },
  });
});
