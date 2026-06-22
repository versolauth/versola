import { expect, test } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';
import type { MockConfigState } from './mocks';

const challengesPath = '/?view=challenges&tenant=tenant-alpha';

const locales = [{ code: 'en', name: 'English', isDefault: true, active: true }];

const otpTemplate = {
  id: 'login-otp',
  tenantId: 'tenant-alpha',
  localizations: { en: 'Your code is {{code}}' },
};

const settingsWithoutPasskey = {
  tenantId: 'tenant-alpha',
  allowedPrefixes: ['+77'],
  passwordRegex: null,
  submissionLimits: { otpRequest: [], otpSubmit: [], passwordSubmit: [], banDurationSeconds: 0 },
  otpLength: 6,
  otpResendAfter: 60,
  passkeySettings: null,
};

const baseState: Partial<MockConfigState> = {
  locales,
  otpTemplates: { 'tenant-alpha': [otpTemplate] },
  challengeSettings: { 'tenant-alpha': settingsWithoutPasskey },
};

test('renders OTP templates and challenge settings sections', async ({ page }) => {
  await loadAdminApp(page, { path: challengesPath, state: baseState });

  await expect(page.getByRole('heading', { name: 'OTP Templates', exact: true })).toBeVisible();
  await expect(page.locator('.template-id').filter({ hasText: 'login-otp' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Challenge Settings', exact: true })).toBeVisible();
  await expect(page.locator('.template-text').filter({ hasText: '6 digits' })).toBeVisible();
  await expect(page.locator('.prefix-tag').filter({ hasText: '+77' })).toBeVisible();
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

test('deletes an OTP template through the shared confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, { path: challengesPath, state: baseState });

  await page.locator('.template-card').filter({ hasText: 'login-otp' })
    .locator('.template-actions .icon-action.danger').click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();
  await page.waitForTimeout(300);

  findRequest(api.requests, 'DELETE', '/configuration/challenges/otp-templates');
  await expect(page.locator('.template-id').filter({ hasText: 'login-otp' })).toHaveCount(0);
});
