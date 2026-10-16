import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { loadAdminApp } from './fixtures';

/**
 * Automated accessibility scan (axe-core) of the admin console's list screens, in both themes.
 *
 * axe covers only part of WCAG, so a clean run does not mean a screen is accessible: it is a floor
 * that catches missing labels, low contrast and similar, next to looking at the screenshots
 * (see .claude/rules/frontend.md). Tags are the WCAG 2.0/2.1 levels A and AA axe documents.
 */
const views = ['tenants', 'clients', 'scopes', 'permissions', 'resources', 'roles', 'users'];
const themes = ['light', 'dark'] as const;

for (const theme of themes) {
  for (const view of views) {
    test(`${view} has no axe violations in the ${theme} theme`, async ({ page }) => {
      await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
      await loadAdminApp(page, { path: `/?view=${view}` });
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      expect(
        results.violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`),
      ).toEqual([]);
    });
  }
}

for (const theme of themes) {
  test(`a client's page and its section editor have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, { path: '/?view=clients&tenant=tenant-alpha' });
    await page.getByRole('button', { name: 'Open client alpha-web', exact: true }).click();
    await expect(page.locator('versola-clients-list .view-title')).toBeVisible();

    const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);

    await page.getByRole('button', { name: 'Edit tokens', exact: true }).click();
    await expect(page.getByRole('button', { name: /^Save tokens/ })).toBeVisible();
    expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
  });
}

for (const theme of themes) {
  test(`a partner app's appearance editor has no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, {
      path: '/?view=clients&tenant=tenant-alpha',
      state: {
        clients: {
          'tenant-alpha': [{
            id: 'partner-app',
            clientName: { en: 'Partner App' },
            redirectUris: ['https://partner.example/callback'],
            scope: ['openid'],
            permissions: [],
            secretRotation: false,
            accessTokenTtl: 3600,
            authFlow: { primary: { credentials: ['phone'], inlinePassword: false, factors: [{ type: 'otp', required: true }] }, passkey: null, otpType: 'sms' },
            consentFlow: { allowPartial: false, rememberDuration: 15552000 },
            themeOverrides: { brand: '#b4348a' },
          }],
        },
        themes: [{ id: 'default', css: '', tenantId: null, tokens: { base: 'versola' } }],
        themePresets: [{ id: 'versola', title: { en: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } }],
      },
    });
    await page.getByRole('button', { name: 'Open client partner-app', exact: true }).click();
    await page.getByRole('button', { name: 'Edit branding and logout', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Appearance for this app' })).toBeVisible();

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect(results.violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
  });
}

for (const theme of themes) {
  test(`every step of creating a client has no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, { path: '/?view=clients&tenant=tenant-alpha' });
    const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    const clean = async () => expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);

    await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
    await page.getByRole('button', { name: /Web app/ }).click();
    await clean();
    await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
    await page.locator('versola-client-form #client-id').fill('wizard-axe');
    await page.locator('versola-client-form input#client-name').fill('Wizard Axe');
    await page.locator('versola-client-form #redirect-uri').fill('https://wizard.example/callback');
    await page.locator('versola-client-form #redirect-uri').press('Enter');
    await clean();
    await page.getByRole('button', { name: 'Continue to sign-in', exact: true }).click();
    await clean();
    await page.getByRole('button', { name: 'Continue to review', exact: true }).click();
    await clean();
  });
}

const themePresets = [
  { id: 'versola', title: { en: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } },
  { id: 'studio', title: { en: 'Studio' }, tokens: { brand: '#ff6b4a', shape: 'round', surface: 'bold', mode: 'auto', background: 'glow' } },
];

for (const theme of themes) {
  test(`the themes page and the theme editor have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, {
      path: '/?view=themes&tenant=tenant-alpha',
      state: {
        themePresets,
        themes: [
          { id: 'default', css: '', tenantId: null },
          { id: 'brand', css: '', tenantId: null, tokens: { base: 'studio', overrides: { brand: '#112233' } } },
        ],
        forms: [{ id: 'access-denied', version: 1, active: true, style: '', jsSource: null, jsCompiled: 'document.getElementById("versola-form-root").innerHTML = "<h1>Access denied</h1>";', localizations: { en: {} }, properties: [] }],
        formPreviews: [{ formId: 'access-denied', scenarios: [{ id: 'access-denied', title: { en: 'Access denied' }, step: { type: 'access-denied' } }] }],
        locales: [{ code: 'en', name: 'English', active: true, isDefault: true }],
      },
    });
    const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    const clean = async () => expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);

    await expect(page.getByRole('button', { name: 'Edit theme brand', exact: true })).toBeVisible();
    await clean();

    await page.getByRole('button', { name: 'Edit theme brand', exact: true }).click();
    await expect(page.getByText('1 override on Studio')).toBeVisible();
    await clean();

    // With a background chosen: the card that is pressed and the badge with its Reset.
    await page.getByRole('group', { name: 'Background style' }).getByRole('button', { name: 'Horizon' }).click();
    await expect(page.getByText('2 overrides on Studio')).toBeVisible();
    await clean();

    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.getByRole('button', { name: '+ New Theme', exact: true }).click();
    await clean();
  });
}

for (const theme of themes) {
  test(`the partner step of the client wizard has no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, {
      path: '/?view=clients&tenant=tenant-alpha',
      state: {
        themes: [{ id: 'default', css: '', tenantId: null, tokens: { base: 'versola' } }],
        themePresets: [{ id: 'versola', title: { en: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } }],
      },
    });
    await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
    await page.getByRole('button', { name: /Web app/ }).click();
    await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
    await page.locator('versola-client-form #client-id').fill('wizard-a11y');
    await page.locator('versola-client-form input#client-name').fill('Wizard A11y');
    await page.locator('#redirect-uri').fill('https://a11y.example/callback');
    await page.locator('#redirect-uri').press('Enter');
    await page.getByRole('button', { name: /^Continue to sign-in$/ }).click();
    await page.getByRole('button', { name: 'Partner app', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Appearance for this app' })).toBeVisible();

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect(results.violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
  });
}
