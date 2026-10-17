import { expect, test } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';
import type { MockConfigState } from './mocks';

const challengesPath = '/?view=challenges&tenant=tenant-alpha';

const locales = [{ code: 'en', name: 'English', isDefault: true, active: true }];

const otpTemplate = {
  id: 'login-otp',
  tenantId: 'tenant-alpha',
  localizations: { en: 'Your code is {{code}}' },
  purpose: 'otp',
  channel: 'sms' as const,
};

const authorizationDetailType = {
  type: 'payment_initiation',
  description: { en: 'Payment initiation' },
  schema: { type: 'object', required: ['type'] },
};

const settingsWithoutPasskey = {
  tenantId: 'tenant-alpha',
  phoneRegions: [
    { region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}', registration: true },
    { region: 'RU', dialCode: '+7', length: 10, regex: '9\\d{9}', registration: false },
  ],
  passwordRegex: null,
  submissionLimits: { otpRequest: [], otpSubmit: [], passwordSubmit: [], passkeyAssertion: [], banDurationSeconds: 0 },
  otpLength: 6,
  otpResendAfter: 60,
  passwordHistorySize: 0,
  passwordNumDifferent: 0,
  authConversationTtlSeconds: 900,
  sessionTtlSeconds: 86400,
  userAgentTtlSeconds: 15552000,
  passkeySettings: null,
};

const baseState: Partial<MockConfigState> = {
  locales,
  otpTemplates: { 'tenant-alpha': [otpTemplate] },
  challengeSettings: { 'tenant-alpha': settingsWithoutPasskey },
};

test('renders OTP templates and challenge settings sections', async ({ page }) => {
  await loadAdminApp(page, { path: challengesPath, state: baseState });

  await expect(page.getByRole('heading', { name: 'Templates', exact: true })).toBeVisible();
  await expect(page.locator('.template-id').filter({ hasText: 'login-otp' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Challenge Settings', exact: true })).toBeVisible();
  await expect(page.locator('.template-text').filter({ hasText: '6 digits' })).toBeVisible();
  const chips = page.locator('.region-chip');
  await expect(chips).toHaveCount(2);
  await expect(chips.nth(0)).toHaveText('KZ +7 · 10 digits · 7\\d{9}sign-up');
  await expect(chips.nth(1)).toHaveText('RU +7 · 10 digits · 9\\d{9}');
  await expect(chips.nth(0).locator('.signup-mark')).toHaveCount(1);
  await expect(chips.nth(1).locator('.signup-mark')).toHaveCount(0);

  const headerButtonSizes = await page.locator('.section-header .btn').evaluateAll(buttons =>
    buttons.map(button => {
      const rect = button.getBoundingClientRect();
      return {
        width: rect.width,
        height: rect.height,
      };
    }),
  );
  expect(new Set(headerButtonSizes.map(size => size.width)).size).toBe(1);
  expect(new Set(headerButtonSizes.map(size => size.height)).size).toBe(1);
});

test('renders authorization details in Challenges & Security', async ({ page }) => {
  await loadAdminApp(page, {
    path: challengesPath,
    state: { ...baseState, authorizationDetailTypes: { 'tenant-alpha': [authorizationDetailType] } },
  });

  await expect(page.getByRole('heading', { name: 'Authorization Details', exact: true })).toBeVisible();
  await expect(page.locator('.settings-section').filter({ hasText: 'Authorization Details' }).getByRole('button', { name: 'Create Type', exact: true })).toBeVisible();
  const typeCard = page.locator('.type-card').filter({ hasText: 'payment_initiation' });
  await expect(typeCard).toBeVisible();
  await typeCard.locator('.type-header').click();
  await expect(typeCard.locator('.schema-preview')).toContainText('required');
});

test('creates an authorization detail type from Challenges & Security', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: challengesPath,
    state: { ...baseState, authorizationDetailTypes: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: 'Create Type', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create Authorization Detail Type', exact: true })).toBeVisible();
  await page.getByLabel('Type *').fill('payment_initiation');
  await page.getByLabel('Description *').fill('Payment initiation');
  await page.locator('form').getByRole('button', { name: 'Create Type', exact: true }).click();

  await expect(page.locator('.type-card').filter({ hasText: 'payment_initiation' })).toBeVisible();
  expect((await findRequest(api.requests, 'POST', '/configuration/authorization-detail-types')).body).toEqual({
    tenantId: 'tenant-alpha',
    type: 'payment_initiation',
    description: { en: 'Payment initiation' },
    schema: {
      '$schema': 'https://json-schema.org/draft/2020-12/schema',
      type: 'object',
      properties: {
        type: { type: 'string' },
        locations: { type: 'array', items: { type: 'string' } },
        actions: { type: 'array', items: { type: 'string' } },
      },
      required: ['type'],
      unevaluatedProperties: false,
    },
  });
});

test('validates authorization detail JSON inline', async ({ page }) => {
  await loadAdminApp(page, {
    path: challengesPath,
    state: { ...baseState, authorizationDetailTypes: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: 'Create Type', exact: true }).click();
  const editor = page.locator('versola-code-editor');
  const schemaInput = editor.locator('textarea');

  await schemaInput.fill('{"type":');
  await expect(editor.locator('.wrapper')).toHaveClass(/invalid/);
  await expect(page.getByRole('alert')).toBeVisible();

  await schemaInput.fill('{"type":"object"}');
  await expect(editor.locator('.wrapper')).not.toHaveClass(/invalid/);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('selects SMS by default and switches the default template type', async ({ page }) => {
  const defaultOtpSms = { ...otpTemplate, id: 'default' };
  const defaultOtpEmail = {
    ...defaultOtpSms,
    localizations: { en: '<html><body>Code: {{code}}</body></html>' },
    channel: 'email' as const,
  };
  const defaultPasswordEmail = {
    ...defaultOtpEmail,
    localizations: { en: '<html><body>Password: {{password}}; expires in {{expiresHours}}</body></html>' },
    purpose: 'password',
  };
  const defaultPasswordSms = {
    ...defaultPasswordEmail,
    localizations: { en: 'Password: {{password}}; expires in {{expiresHours}} hours.' },
    channel: 'sms' as const,
  };
  await loadAdminApp(page, {
    path: challengesPath,
    state: {
      ...baseState,
      otpTemplates: { 'tenant-alpha': [defaultOtpEmail, defaultOtpSms, defaultPasswordEmail, defaultPasswordSms] },
    },
  });

  const card = page.locator('.template-card').filter({ hasText: 'default' });
  await expect(card.getByLabel('Type')).toHaveValue('otp');
  await expect(card.getByLabel('Channel')).toHaveValue('sms');

  await card.getByLabel('Channel').selectOption('email');
  await expect(card.getByLabel('Channel')).toHaveValue('email');
  await expect(card.locator('iframe.email-preview')).toBeVisible();

  await card.getByLabel('Type').selectOption('password');
  await expect(card.getByLabel('Channel')).toHaveValue('email');
  await expect(card.locator('iframe.email-preview')).toBeVisible();

  await page.reload();
  const reloadedCard = page.locator('.template-card').filter({ hasText: 'default' });
  await expect(reloadedCard.getByLabel('Type')).toHaveValue('otp');
  await expect(reloadedCard.getByLabel('Channel')).toHaveValue('sms');
});

test('renders an empty state when a default template purpose is missing', async ({ page }) => {
  const defaultOtpSms = { ...otpTemplate, id: 'default' };
  const defaultOtpEmail = {
    ...defaultOtpSms,
    localizations: { en: '<html><body>Code: {{code}}</body></html>' },
    channel: 'email' as const,
  };
  await loadAdminApp(page, {
    path: challengesPath,
    state: {
      ...baseState,
      otpTemplates: { 'tenant-alpha': [defaultOtpEmail, defaultOtpSms] },
    },
  });

  const card = page.locator('.template-card').filter({ hasText: 'default' });
  await card.getByLabel('Type').selectOption('password');
  await expect(card.locator('.empty-state')).toContainText('No password template exists');
  await expect(card.locator('.template-id')).toHaveText('default');
});

test('shows passkeys as not configured when absent', async ({ page }) => {
  await loadAdminApp(page, { path: challengesPath, state: baseState });

  await expect(page.getByText('Passkeys are not configured for this tenant.')).toBeVisible();
});

test('shows configured passkey settings in view mode', async ({ page }) => {
  await loadAdminApp(page, {
    path: challengesPath,
    state: {
      ...baseState,
      challengeSettings: {
        'tenant-alpha': {
          ...settingsWithoutPasskey,
          passkeySettings: {
            rpId: 'example.com',
            rpName: 'Example Inc.',
            origins: ['https://example.com'],
            userVerification: 'required',
          },
        },
      },
    },
  });

  await expect(page.locator('.prop-value').filter({ hasText: 'example.com' }).first()).toBeVisible();
  await expect(page.locator('.prop-value').filter({ hasText: 'Example Inc.' })).toBeVisible();
  await expect(page.locator('.prop-value').filter({ hasText: 'required' })).toBeVisible();
  await expect(page.locator('.prefix-tag').filter({ hasText: 'https://example.com' })).toBeVisible();
});

test('edits passkeys and sends them in the save payload', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Edit Challenge Settings', exact: true })).toBeVisible();

  await page.getByPlaceholder('example.com', { exact: true }).fill('example.com');
  await page.getByPlaceholder('Example Inc.', { exact: true }).fill('Example Inc.');
  await page.getByRole('button', { name: '+ Add Origin', exact: true }).click();
  await page.getByPlaceholder('https://example.com', { exact: true }).fill('https://example.com');

  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = (await findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings')).body as {
    passkeySettings: { rpId: string; rpName: string; origins: string[]; userVerification: string } | null;
  };
  expect(body.passkeySettings).toEqual({
    rpId: 'example.com',
    rpName: 'Example Inc.',
    origins: ['https://example.com'],
    userVerification: 'preferred',
  });
});

test('lists only the keys central can sign with, and sends the selected kid', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  const picker = page.locator('select').filter({ hasText: "Auth service's configured key" });

  // The bootstrap key has no private half in central, so nothing could sign with it --
  // offering it would let an operator pick a key that silently falls back at sign time.
  await expect(picker.locator('option')).toHaveCount(2);
  await expect(picker.locator('option').nth(1)).toHaveText(/ps-kid · PS256/);

  await picker.selectOption('ps-kid');
  await page.getByPlaceholder('example.com', { exact: true }).fill('example.com');
  await page.getByPlaceholder('Example Inc.', { exact: true }).fill('Example Inc.');
  await page.getByRole('button', { name: '+ Add Origin', exact: true }).click();
  await page.getByPlaceholder('https://example.com', { exact: true }).fill('https://example.com');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = (await findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings')).body as {
    signingKeyId: string | null;
  };
  expect(body.signingKeyId).toBe('ps-kid');
});

// Clearing the selection is the deliberate move back to auth's own key, so it has to reach
// central as an explicit null rather than as an omitted field central would read as "keep".
test('clears the signing key selection as an explicit null', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: challengesPath,
    state: {
      ...baseState,
      challengeSettings: { 'tenant-alpha': { ...settingsWithoutPasskey, signingKeyId: 'ps-kid' } },
    },
  });

  await expect(page.locator('.prop-value').filter({ hasText: 'ps-kid' })).toBeVisible();
  await expect(page.locator('.prop-value').filter({ hasText: 'PS256' })).toBeVisible();

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.locator('select').filter({ hasText: "Auth service's configured key" }).selectOption('');
  await page.getByPlaceholder('example.com', { exact: true }).fill('example.com');
  await page.getByPlaceholder('Example Inc.', { exact: true }).fill('Example Inc.');
  await page.getByRole('button', { name: '+ Add Origin', exact: true }).click();
  await page.getByPlaceholder('https://example.com', { exact: true }).fill('https://example.com');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = (await findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings')).body as {
    signingKeyId: string | null;
  };
  expect(body.signingKeyId).toBeNull();
});

test('validates that passkeys require a relying party id', async ({ page }) => {
  await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  await expect(page.locator('.error-msg')).toHaveText('Passkey Relying Party ID is required.');
});

test('adds a new OTP template', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: challengesPath,
    state: { ...baseState, otpTemplates: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: 'Add Template', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Add OTP Template', exact: true })).toBeVisible();
  await page.getByPlaceholder('e.g. login-otp').fill('signup-otp');
  await page.getByPlaceholder('Your verification code is: {{code}}').fill('Your code is {{code}}');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  expect((await findRequest(api.requests, 'PUT', '/configuration/challenges/otp-templates')).body).toMatchObject({
    id: 'signup-otp',
    tenantId: 'tenant-alpha',
    localizations: { en: 'Your code is {{code}}' },
  });
});

test('rejects an OTP template localization without the code placeholder', async ({ page }) => {
  await loadAdminApp(page, {
    path: challengesPath,
    state: { ...baseState, otpTemplates: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: 'Add Template', exact: true }).click();
  await page.getByPlaceholder('e.g. login-otp').fill('signup-otp');
  await page.getByPlaceholder('Your verification code is: {{code}}').fill('No placeholder here');
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  await expect(page.locator('.error-msg')).toContainText('{{code}}');
});

test('validates and previews an email OTP template', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: challengesPath,
    state: { ...baseState, otpTemplates: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: 'Add Template', exact: true }).click();
  await page.getByPlaceholder('e.g. login-otp').fill('email-otp');
  await page.getByLabel('Channel').selectOption('email');
  const editor = page.getByPlaceholder('<html><body><p>Your verification code is {{code}}</p></body></html>');
  const codeEditor = page.locator('versola-code-editor');
  await editor.fill('not html {{code}}');
  await expect(page.getByText('HTML must start with an element', { exact: true })).toBeVisible();
  await expect(codeEditor.locator('.wrapper')).toHaveClass(/invalid/);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.locator('.error-msg')).toContainText('valid HTML');

  await editor.fill('<html><body><p>Your verification code is {{code}}</p></body></html>');
  await expect(codeEditor.locator('.wrapper')).not.toHaveClass(/invalid/);
  await editor.fill('<html><body><p>Your verification code is {{code}}</p></body>');
  await expect(codeEditor.locator('.wrapper')).toHaveClass(/invalid/);
  await expect(page.locator('.html-validation-error')).toContainText('Unclosed tag');
  await editor.fill('<html><body><p>Your verification code is {{code}}</p></body></html>');
  await expect(codeEditor.locator('.wrapper')).not.toHaveClass(/invalid/);
  await page.getByRole('button', { name: 'Preview', exact: true }).click();
  await expect(page.locator('iframe.email-preview')).toBeVisible();
  await expect(page.frameLocator('iframe.email-preview').locator('body')).toContainText('{{code}}');
  await page.getByRole('button', { name: 'Edit HTML', exact: true }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  expect((await findRequest(api.requests, 'PUT', '/configuration/challenges/otp-templates')).body).toMatchObject({
    id: 'email-otp',
    channel: 'email',
    localizations: { en: '<html><body><p>Your verification code is {{code}}</p></body></html>' },
  });
});

test('edits both OTP channels from the template editor', async ({ page }) => {
  const emailTemplate = {
    ...otpTemplate,
    localizations: { en: '<html><body>{{code}}</body></html>' },
    channel: 'email' as const,
  };
  const api = await loadAdminApp(page, {
    path: challengesPath,
    state: { ...baseState, otpTemplates: { 'tenant-alpha': [otpTemplate, emailTemplate] } },
  });

  const card = page.locator('.template-card').filter({ hasText: 'login-otp' });
  await card.getByLabel('Channel').selectOption('email');
  await card.locator('.template-actions .icon-action[title="Edit"]').click();
  await expect(page.getByRole('heading', { name: 'Edit OTP Template', exact: true })).toBeVisible();
  await expect(page.locator('textarea').first()).toHaveValue('<html><body>{{code}}</body></html>');

  await page.locator('#template-channel').selectOption('sms');
  await expect(page.locator('textarea').first()).toHaveValue('Your code is {{code}}');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  expect((await findRequest(api.requests, 'PUT', '/configuration/challenges/otp-templates')).body).toMatchObject({
    id: 'login-otp',
    channel: 'sms',
    localizations: { en: 'Your code is {{code}}' },
  });
});

test('deletes an OTP template through the shared confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.locator('.template-card').filter({ hasText: 'login-otp' })
    .locator('.template-actions .icon-action.danger').click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();
  await page.waitForTimeout(300);

  (await findRequest(api.requests, 'DELETE', '/configuration/challenges/otp-templates'));
  await expect(page.locator('.template-id').filter({ hasText: 'login-otp' })).toHaveCount(0);
});

test('edits phone regions: add, reorder, validate, and sends phoneRegions in the PUT body', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  const rows = page.getByTestId('region-row');
  await expect(rows).toHaveCount(2);

  await page.getByRole('button', { name: '+ Add Region', exact: true }).click();
  await expect(rows).toHaveCount(3);
  await rows.nth(2).getByLabel('Region').fill('us');
  await rows.nth(2).getByLabel('Dial code').fill('+1');
  await rows.nth(2).getByLabel('Length').fill('3');
  await rows.nth(2).getByLabel('Regex').fill('\\d{10}');

  // Invalid length is refused client-side with an inline error, and nothing is sent.
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(rows.nth(2).locator('.region-error')).toContainText('between 4 and 14');
  expect(api.requests.some(r => r.method === 'PUT' && r.pathname === '/configuration/challenges/challenge-settings')).toBe(false);

  // A duplicate region is refused too.
  await rows.nth(2).getByLabel('Length').fill('10');
  await rows.nth(2).getByLabel('Region').fill('KZ');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(rows.nth(2).locator('.region-error')).toContainText('more than once');
  await rows.nth(2).getByLabel('Region').fill('US');

  // Move US to the top.
  await rows.nth(2).getByRole('button', { name: 'Move up' }).click();
  await rows.nth(1).getByRole('button', { name: 'Move up' }).click();
  await expect(rows.nth(0).getByLabel('Region')).toHaveValue('US');

  // Passkeys are required by the form regardless of phone regions.
  await page.getByPlaceholder('example.com', { exact: true }).fill('example.com');
  await page.getByPlaceholder('Example Inc.', { exact: true }).fill('Example Inc.');
  await page.getByRole('button', { name: '+ Add Origin', exact: true }).click();
  await page.getByPlaceholder('https://example.com', { exact: true }).fill('https://example.com');

  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = (await findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings')).body as {
    phoneRegions: unknown[];
  };
  expect(body.phoneRegions).toEqual([
    { region: 'US', dialCode: '+1', length: 10, regex: '\\d{10}', registration: false },
    { region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}', registration: true },
    { region: 'RU', dialCode: '+7', length: 10, regex: '9\\d{9}', registration: false },
  ]);
  expect(body).not.toHaveProperty('allowedPrefixes');
});

test('ticks Registration per region and sends it in the PUT body', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  const rows = page.getByTestId('region-row');
  await expect(rows.nth(0).getByLabel('Registration')).toBeChecked();
  await expect(rows.nth(1).getByLabel('Registration')).not.toBeChecked();

  await rows.nth(0).getByLabel('Registration').uncheck();
  await rows.nth(1).getByLabel('Registration').check();

  await page.getByPlaceholder('example.com', { exact: true }).fill('example.com');
  await page.getByPlaceholder('Example Inc.', { exact: true }).fill('Example Inc.');
  await page.getByRole('button', { name: '+ Add Origin', exact: true }).click();
  await page.getByPlaceholder('https://example.com', { exact: true }).fill('https://example.com');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = (await findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings')).body as {
    phoneRegions: Array<{ region: string; registration: boolean }>;
  };
  expect(body.phoneRegions.map(r => [r.region, r.registration])).toEqual([['KZ', false], ['RU', true]]);
});

test('adds countries from presets with registration on and skips ones already listed', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  const rows = page.getByTestId('region-row');
  await expect(rows).toHaveCount(2);

  await page.getByRole('button', { name: 'Add from presets', exact: true }).click();
  const picker = page.getByRole('dialog', { name: 'Add from presets' });
  await expect(picker).toBeVisible();
  await expect(picker.getByRole('button', { name: 'Add selected' })).toBeDisabled();

  // KZ is already in the list, so it cannot be picked again.
  await expect(picker.getByRole('checkbox', { name: 'Kazakhstan' })).toBeDisabled();

  await picker.getByRole('searchbox', { name: 'Search presets' }).fill('uz');
  await picker.getByRole('checkbox', { name: 'Uzbekistan' }).check();
  await picker.getByRole('searchbox', { name: 'Search presets' }).fill('');
  await picker.getByRole('checkbox', { name: 'Georgia' }).check();
  await picker.getByRole('button', { name: 'Add 2 selected' }).click();

  await expect(picker).toHaveCount(0);
  await expect(rows).toHaveCount(4);
  await expect(rows.nth(2).getByLabel('Region')).toHaveValue('UZ');
  await expect(rows.nth(3).getByLabel('Region')).toHaveValue('GE');
  await expect(rows.nth(2).getByLabel('Registration')).toBeChecked();

  // Registration can still be unticked per row.
  await rows.nth(3).getByLabel('Registration').uncheck();

  await page.getByPlaceholder('example.com', { exact: true }).fill('example.com');
  await page.getByPlaceholder('Example Inc.', { exact: true }).fill('Example Inc.');
  await page.getByRole('button', { name: '+ Add Origin', exact: true }).click();
  await page.getByPlaceholder('https://example.com', { exact: true }).fill('https://example.com');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = (await findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings')).body as {
    phoneRegions: unknown[];
  };
  expect(body.phoneRegions).toEqual([
    { region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}', registration: true },
    { region: 'RU', dialCode: '+7', length: 10, regex: '9\\d{9}', registration: false },
    { region: 'UZ', dialCode: '+998', length: 9, regex: '\\d{9}', registration: true },
    { region: 'GE', dialCode: '+995', length: 9, regex: '5\\d{8}', registration: false },
  ]);
});

const registrationState: Partial<MockConfigState> = {
  ...baseState,
  roles: {
    'tenant-alpha': [
      { id: 'user', description: { en: 'User' }, permissions: [], active: true },
      { id: 'member', description: { en: 'Member' }, permissions: [], active: true },
    ],
  },
  challengeSettings: {
    'tenant-alpha': {
      ...settingsWithoutPasskey,
      passkeySettings: { rpId: 'example.com', rpName: 'Example Inc.', origins: ['https://example.com'], userVerification: 'preferred' },
    },
  },
};

const challengeSettingsPath = '/configuration/challenges/challenge-settings';

async function putBody(api: Awaited<ReturnType<typeof loadAdminApp>>) {
  return (await findRequest(api.requests, 'PUT', challengeSettingsPath)).body as {
    emailRegistration: { mode: string; domains: string[]; blockSubaddress: boolean };
    defaultRoles: string[];
  };
}

test('shows email registration as closed with the user role when the tenant has not set it', async ({ page }) => {
  await loadAdminApp(page, { path: challengesPath, state: registrationState });

  const view = page.getByTestId('email-registration-view');
  await expect(view).toContainText('Closed');
  await expect(view).toContainText('Nobody can create an account with an email address');
  await expect(view.locator('.prefix-tag')).toHaveText(['user']);
});

test('saving unchanged settings sends the closed default and the user role', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: registrationState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.getByLabel('Email registration', { exact: true })).toHaveValue('closed');
  // Closed mode has no domain list to edit.
  await expect(page.getByLabel('Blocked domains')).toHaveCount(0);
  await expect(page.getByLabel('Allowed domains')).toHaveCount(0);
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  const body = await putBody(api);
  expect(body.emailRegistration).toEqual({ mode: 'closed', domains: [], blockSubaddress: false });
  expect(body.defaultRoles).toEqual(['user']);
});

test('explains the registration modes in an info popover that closes with Escape or a click outside', async ({ page }) => {
  await loadAdminApp(page, { path: challengesPath, state: registrationState });
  await page.getByRole('button', { name: 'Edit', exact: true }).click();

  const info = page.getByRole('button', { name: 'About email registration modes', exact: true });
  const tooltip = page.getByRole('tooltip').filter({ hasText: 'Allowlist.' });
  await expect(info).toHaveAttribute('aria-expanded', 'false');
  await expect(tooltip).toBeHidden();

  await info.focus();
  await page.keyboard.press('Enter');
  await expect(info).toHaveAttribute('aria-expanded', 'true');
  await expect(tooltip).toBeVisible();
  await expect(tooltip).toContainText('Nobody can create an account with an email address. Users who already have an account still sign in.');
  await expect(tooltip).toContainText('Anyone can register with an email address, except from the domains you list');
  await expect(tooltip).toContainText('Only addresses from the domains you list can register.');
  await expect(tooltip).toContainText('also applied when an existing user changes their email address');

  await page.keyboard.press('Escape');
  await expect(tooltip).toBeHidden();
  await expect(info).toHaveAttribute('aria-expanded', 'false');
  await expect(info).toBeFocused();

  await info.click();
  await expect(tooltip).toBeVisible();
  await page.getByRole('heading', { name: 'Registration', exact: true }).click();
  await expect(tooltip).toBeHidden();
});

test('labels the domain list by mode and sends the registration policy in the PUT body', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: registrationState });
  await page.getByRole('button', { name: 'Edit', exact: true }).click();

  const mode = page.getByLabel('Email registration', { exact: true });
  await mode.selectOption('open');
  await expect(page.getByLabel('Blocked domains')).toBeVisible();
  await mode.selectOption('allowlist');
  await expect(page.getByLabel('Allowed domains')).toBeVisible();
  await expect(page.getByLabel('Blocked domains')).toHaveCount(0);

  await page.getByLabel('Allowed domains').fill('Example.com\n\n  *.corp.example  \n');
  await page.getByLabel('Block addresses with + = # (sub-addresses)').check();
  const defaultRoles = page.getByRole('group', { name: 'Default roles' });
  await defaultRoles.getByRole('checkbox', { name: 'member', exact: true }).check();
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  const body = await putBody(api);
  expect(body.emailRegistration).toEqual({
    mode: 'allowlist',
    domains: ['Example.com', '*.corp.example'],
    blockSubaddress: true,
  });
  expect(body.defaultRoles).toEqual(['user', 'member']);

  const view = page.getByTestId('email-registration-view');
  await expect(view).toContainText('Allowlist');
  await expect(view.locator('.prefix-tag')).toHaveText(['Example.com', '*.corp.example', 'user', 'member']);
});

test('refuses a malformed or repeated registration domain and sends nothing', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: registrationState });
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByLabel('Email registration', { exact: true }).selectOption('open');

  const domains = page.getByLabel('Blocked domains');
  await domains.fill('example.com\nnodot');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'nodot' })).toBeVisible();

  await domains.fill('Example.com\nexample.COM');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'more than once' })).toBeVisible();

  expect(api.requests.some(r => r.method === 'PUT' && r.pathname === challengeSettingsPath)).toBe(false);

  await domains.fill('example.com\n*.example.org');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await putBody(api)).emailRegistration).toMatchObject({ mode: 'open', domains: ['example.com', '*.example.org'] });
});

test('requires at least one default role', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: registrationState });
  await page.getByRole('button', { name: 'Edit', exact: true }).click();

  const defaultRoles = page.getByRole('group', { name: 'Default roles' });
  await expect(defaultRoles.getByRole('checkbox', { name: 'user', exact: true })).toBeChecked();
  await defaultRoles.getByRole('checkbox', { name: 'user', exact: true }).uncheck();
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  await expect(page.getByRole('alert').filter({ hasText: 'Select at least one default role' })).toBeVisible();
  expect(api.requests.some(r => r.method === 'PUT' && r.pathname === challengeSettingsPath)).toBe(false);

  await defaultRoles.getByRole('checkbox', { name: 'member', exact: true }).check();
  await expect(page.getByRole('alert').filter({ hasText: 'Select at least one default role' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await putBody(api)).defaultRoles).toEqual(['member']);
});

test('keeps the stored domains when the mode is closed', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: challengesPath,
    state: {
      ...registrationState,
      challengeSettings: {
        'tenant-alpha': {
          ...registrationState.challengeSettings!['tenant-alpha'],
          emailRegistration: { mode: 'closed', domains: ['example.com'], blockSubaddress: false },
          defaultRoles: ['member'],
        },
      },
    },
  });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.getByLabel('Allowed domains')).toHaveCount(0);
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  const body = await putBody(api);
  expect(body.emailRegistration).toEqual({ mode: 'closed', domains: ['example.com'], blockSubaddress: false });
  expect(body.defaultRoles).toEqual(['member']);
});


async function fillRequiredPasskey(page: import('@playwright/test').Page) {
  await page.getByPlaceholder('example.com', { exact: true }).fill('example.com');
  await page.getByPlaceholder('Example Inc.', { exact: true }).fill('Example Inc.');
  await page.getByRole('button', { name: '+ Add Origin', exact: true }).click();
  await page.getByPlaceholder('https://example.com', { exact: true }).fill('https://example.com');
}

test('shows a tenant without a logo as the Versola wordmark', async ({ page }) => {
  await loadAdminApp(page, { path: challengesPath, state: baseState });

  await expect(page.getByText('The login pages of this tenant show the Versola wordmark.')).toBeVisible();
  await expect(page.getByTestId('logo-preview-light')).toHaveCount(0);
});

test('saves a logo pair and previews each file on a swatch of its own scheme', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByTestId('logo-light-input').fill('https://assets.example/logo-light.svg');
  await page.getByTestId('logo-dark-input').fill('https://assets.example/logo-dark.svg');
  await expect(page.getByTestId('logo-preview-light').locator('img')).toHaveAttribute('src', 'https://assets.example/logo-light.svg');
  await expect(page.getByTestId('logo-preview-dark').locator('img')).toHaveAttribute('src', 'https://assets.example/logo-dark.svg');
  await expect(page.getByTestId('logo-preview-light')).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  await expect(page.getByTestId('logo-preview-dark')).toHaveCSS('background-color', 'rgb(31, 31, 32)');
  await fillRequiredPasskey(page);
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings')).body).toMatchObject({
    logoLight: 'https://assets.example/logo-light.svg',
    logoDark: 'https://assets.example/logo-dark.svg',
  });
  await expect(page.locator('.prop-value').filter({ hasText: 'https://assets.example/logo-dark.svg' })).toBeVisible();
});

test('refuses a single logo file and a malformed URL before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await fillRequiredPasskey(page);
  await page.getByTestId('logo-light-input').fill('https://assets.example/logo-light.svg');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.locator('.error-msg')).toHaveText('The logo needs both a light and a dark file, or neither.');

  await page.getByTestId('logo-dark-input').fill('/logo-dark.svg');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.locator('.error-msg')).toHaveText('Logo must be an absolute http(s) URL.');
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === '/configuration/challenges/challenge-settings')).toBeFalsy();
});

test('clears both logo files as explicit nulls', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: challengesPath,
    state: {
      ...baseState,
      challengeSettings: {
        'tenant-alpha': { ...settingsWithoutPasskey, logoLight: 'https://assets.example/l.svg', logoDark: 'https://assets.example/d.svg' },
      },
    },
  });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByTestId('logo-light-input').fill('');
  await page.getByTestId('logo-dark-input').fill('');
  await fillRequiredPasskey(page);
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings')).body).toMatchObject({
    logoLight: null,
    logoDark: null,
  });
});
