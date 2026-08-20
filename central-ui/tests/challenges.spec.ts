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
  allowedPrefixes: ['+77'],
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
  await expect(page.locator('.prefix-tag').filter({ hasText: '+77' })).toBeVisible();

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
  expect(findRequest(api.requests, 'POST', '/configuration/authorization-detail-types').body).toEqual({
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

  const body = findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings').body as {
    passkeySettings: { rpId: string; rpName: string; origins: string[]; userVerification: string } | null;
  };
  expect(body.passkeySettings).toEqual({
    rpId: 'example.com',
    rpName: 'Example Inc.',
    origins: ['https://example.com'],
    userVerification: 'preferred',
  });
});

test('validates that passkeys require a relying party id', async ({ page }) => {
  await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();

  await expect(page.locator('.error-msg')).toHaveText('Passkey Relying Party ID is required.');
});

test('shows the configured default country code in view mode', async ({ page }) => {
  await loadAdminApp(page, {
    path: challengesPath,
    state: { ...baseState, challengeSettings: { 'tenant-alpha': { ...settingsWithoutPasskey, defaultPhonePrefix: '+77' } } },
  });

  await expect(page.locator('.template-text').filter({ hasText: '+77' })).toBeVisible();
});

test('derives default country code options from allowed phone prefixes and sends the selection', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Edit Challenge Settings', exact: true })).toBeVisible();

  const defaultPrefixSelect = page.getByLabel('Default Phone Prefix');
  await expect(defaultPrefixSelect.locator('option')).toHaveText(['None', '+77']);
  await defaultPrefixSelect.selectOption('+77');

  await page.getByPlaceholder('example.com', { exact: true }).fill('example.com');
  await page.getByPlaceholder('Example Inc.', { exact: true }).fill('Example Inc.');
  await page.getByRole('button', { name: '+ Add Origin', exact: true }).click();
  await page.getByPlaceholder('https://example.com', { exact: true }).fill('https://example.com');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.waitForTimeout(300);

  const body = findRequest(api.requests, 'PUT', '/configuration/challenges/challenge-settings').body as { defaultPhonePrefix: string | null };
  expect(body.defaultPhonePrefix).toBe('+77');
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

  expect(findRequest(api.requests, 'PUT', '/configuration/challenges/otp-templates').body).toMatchObject({
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

  expect(findRequest(api.requests, 'PUT', '/configuration/challenges/otp-templates').body).toMatchObject({
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

  expect(findRequest(api.requests, 'PUT', '/configuration/challenges/otp-templates').body).toMatchObject({
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

  findRequest(api.requests, 'DELETE', '/configuration/challenges/otp-templates');
  await expect(page.locator('.template-id').filter({ hasText: 'login-otp' })).toHaveCount(0);
});
