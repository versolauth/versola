import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { consentForm, consentPreviews } from './consent-preview-fixtures';
import { ftPath, ftState } from './forms-themes-fixtures';
import { loadAdminApp, sidePanel, sidePanelDialog } from './fixtures';
import { ordersApi, shopState } from './resources-fixtures';
import { dana, danaId, quiet, shopManager, shopSupport, shopUser, userClients, userScopes } from './users-fixtures';

/**
 * Automated accessibility scan (axe-core) of the admin console's list screens, in both themes.
 *
 * axe covers only part of WCAG, so a clean run does not mean a screen is accessible: it is a floor
 * that catches missing labels, low contrast and similar, next to looking at the screenshots
 * (see .claude/rules/frontend.md). Tags are the WCAG 2.0/2.1 levels A and AA axe documents.
 */
const views = ['tenants', 'clients', 'scopes', 'permissions', 'resources', 'roles', 'users', 'edges', 'security', 'otp-templates', 'authorization-details', 'keys', 'system-settings'];
const themes = ['light', 'dark'] as const;

/** The stored choice, the OS scheme it is resolved against, and the theme that must be painted. */
const schemes = [
  { pref: 'auto', os: 'dark', theme: 'dark' },
  { pref: 'auto', os: 'light', theme: 'light' },
  { pref: 'light', os: 'dark', theme: 'light' },
  { pref: 'dark', os: 'light', theme: 'dark' },
] as const;

for (const scheme of schemes) {
  for (const view of views) {
    test(`${view} has no axe violations with the ${scheme.pref} choice on a ${scheme.os} OS`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme.os });
      await page.addInitScript(value => localStorage.setItem('versola-theme', value), scheme.pref);
      await loadAdminApp(page, { path: `/?view=${view}` });
      await expect(page.locator('html')).toHaveAttribute('data-theme', scheme.theme);
      await expect(page.locator('html')).toHaveAttribute('data-theme-pref', scheme.pref);

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      expect(
        results.violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`),
      ).toEqual([]);
    });
  }
}

const userState = {
  users: [dana, quiet],
  clients: { 'tenant-alpha': userClients },
  roles: { 'tenant-alpha': [shopManager, shopUser, shopSupport] },
  scopes: { 'tenant-alpha': userScopes },
};

for (const theme of themes) {
  test(`a user's record, its editors and its dialogs have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    const scan = async () => (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze())
      .violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`);
    const base = `/?view=users&tenant=tenant-alpha&id=${danaId}`;

    await loadAdminApp(page, { path: base, state: userState });
    await expect(page.getByRole('region', { name: 'Apps with access' }).locator('.app[data-client]')).toHaveCount(3);
    await expect(page.getByRole('region', { name: 'Signed-in devices' }).locator('.device')).toHaveCount(4);
    expect(await scan()).toEqual([]);

    // The record with nothing to show: no devices, no offline access, no passkeys.
    await page.goto(`/?view=users&tenant=tenant-alpha&id=${quiet.id}`);
    await expect(page.getByRole('region', { name: 'Passkeys' })).toContainText('No passkeys registered.');
    expect(await scan()).toEqual([]);

    // A record that cannot load its user.
    await page.goto(`/?view=users&tenant=tenant-alpha&id=00000000-0000-0000-0000-00000000dead`);
    await expect(page.getByRole('heading', { name: 'User not found' })).toBeVisible();
    expect(await scan()).toEqual([]);

    for (const edit of ['identifiers', 'roles', 'claims']) {
      await page.goto(`${base}&edit=${edit}`);
      await expect(page.getByRole('heading', { level: 1, name: `Edit ${edit}` })).toBeVisible();
      expect(await scan()).toEqual([]);
    }

    await page.goto(base);
    await page.getByRole('button', { name: 'Reset password…' }).click();
    await expect(page.getByRole('dialog', { name: 'Reset password' })).toBeVisible();
    expect(await scan()).toEqual([]);
    await page.keyboard.press('Escape');

    await page.getByRole('button', { name: 'Delete user Dana Reyes' }).click();
    await expect(page.getByRole('dialog')).toContainText('back-channel logout');
    expect(await scan()).toEqual([]);
  });
}

for (const theme of themes) {
  test(`a user's search results and create form have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    const scan = async () => (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze())
      .violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`);
    await loadAdminApp(page, { path: '/?view=users&tenant=tenant-alpha', state: userState });
    await page.getByRole('searchbox', { name: 'Search users' }).fill('dana');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: 'Dana Reyes' })).toBeVisible();
    expect(await scan()).toEqual([]);
    await page.getByRole('button', { name: '+ Create User' }).click();
    await expect(page.getByRole('heading', { name: 'Create New User' })).toBeVisible();
    expect(await scan()).toEqual([]);
  });
}

for (const theme of themes) {
  test(`a resource's list, record, editors and dialogs have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    const scan = async () => (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze())
      .violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`);
    const base = '/?view=resources&tenant=tenant-alpha';

    await loadAdminApp(page, { path: base, state: shopState });
    await expect(page.getByRole('button', { name: 'Open resource orders-api' })).toBeVisible();
    expect(await scan()).toEqual([]);

    await page.goto(`${base}&id=orders-api`);
    await expect(page.getByRole('table', { name: 'Endpoints of orders-api' })).toBeVisible();
    expect(await scan()).toEqual([]);

    // The record with a one-time secret on it, and one with nothing to show.
    await page.getByRole('button', { name: 'Activate new secret' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Activate', exact: true }).click();
    await page.getByRole('region', { name: 'Secret' }).getByRole('button', { name: 'Rotate secret' }).click();
    await expect(page.locator('.secret-value')).toBeVisible();
    expect(await scan()).toEqual([]);
    await page.goto(`${base}&id=loyalty-api`);
    await expect(page.getByRole('region', { name: 'Endpoints' })).toContainText('No endpoints yet.');
    expect(await scan()).toEqual([]);

    for (const edit of ['general', 'audience']) {
      await page.goto(`${base}&id=orders-api&edit=${edit}`);
      await expect(page.getByRole('heading', { level: 1, name: `Edit ${edit}` })).toBeVisible();
      expect(await scan()).toEqual([]);
    }

    await page.goto(`${base}&id=orders-api&edit=endpoints`);
    await page.getByRole('list', { name: 'Endpoints' }).getByRole('button', { name: /^POST \/orders\/\{orderId\}\/refund(?= |$)/ }).click();
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await page.getByLabel('Relative path').fill('bad path');
    await page.getByRole('button', { name: 'Save endpoints' }).click();
    await expect(page.getByRole('alert').first()).toBeVisible();
    expect(await scan()).toEqual([]);
    await page.getByRole('list', { name: 'Endpoints' }).getByRole('button', { name: /^POST \/orders\/\{orderId\}\/refund(?= |$)/ }).click();
    await page.getByRole('button', { name: 'Allow expression info' }).click();
    await expect(page.getByRole('tooltip')).toBeVisible();
    expect(await scan()).toEqual([]);

    await page.goto(`${base}&new=1`);
    await expect(page.getByRole('heading', { level: 1, name: 'Create resource' })).toBeVisible();
    await page.getByRole('button', { name: 'Create resource', exact: true }).click();
    await expect(page.getByText('A resource ID is required.')).toBeVisible();
    expect(await scan()).toEqual([]);

    await page.goto(`${base}&id=${ordersApi.resourceId}`);
    await page.getByRole('button', { name: 'Delete resource orders-api' }).click();
    await expect(page.getByRole('dialog')).toContainText('Delete resource orders-api?');
    expect(await scan()).toEqual([]);
  });
}

for (const theme of themes) {
  test(`a client's page and its section editor have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, { path: '/?view=clients&tenant=tenant-alpha' });
    await page.getByRole('button', { name: /^Open client (.* )?alpha-web$/ }).click();
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
        forms: [consentForm],
        formPreviews: [consentPreviews],
      },
    });
    await page.getByRole('button', { name: /^Open client (.* )?partner-app$/ }).click();
    await page.getByRole('button', { name: 'Edit branding and logout', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Appearance for this app' })).toBeVisible();
    // The preview is part of the block: scan it drawn, not while central is still rendering it.
    await expect(page.locator('versola-client-consent-preview iframe')).toHaveCount(1);

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
    await loadAdminApp(page, { path: ftPath('themes'), state: ftState() });
    const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    const clean = async () => expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);

    await expect(page.getByRole('button', { name: 'Open theme shop-brand', exact: true })).toBeVisible();
    await clean();

    await page.getByRole('button', { name: 'Open theme shop-brand', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'shop-brand' })).toBeVisible();
    await expect(page.locator('versola-preview-pane iframe')).toHaveCount(1);
    await clean();

    // With a background chosen: the card that is pressed, the badge with its Reset and the unsaved bar.
    await page.getByRole('group', { name: 'Background style' }).getByRole('button', { name: 'Flat' }).click();
    await expect(page.locator('.bar-text')).toContainText('Unsaved');
    await clean();

    // The danger card, disabled with its reason.
    await expect(page.getByRole('button', { name: 'Delete theme shop-brand' })).toBeDisabled();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.getByRole('button', { name: 'Discard' }).click();
    // A theme stored with CSS only: the same editor, on the Versola base, with the Custom CSS card open.
    await page.getByRole('button', { name: 'Open theme georgia-2024', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Advanced', exact: true }).getByRole('textbox', { name: 'Custom CSS' })).toBeVisible();
    await clean();
    // Custom CSS added to a theme that had none: the collapsed card opens.
    await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Themes' }).click();
    await page.getByRole('button', { name: 'Discard' }).click();
    await page.getByRole('button', { name: 'Open theme shop-brand', exact: true }).click();
    await page.getByRole('button', { name: 'Add custom CSS' }).click();
    await expect(page.getByRole('textbox', { name: 'Custom CSS' })).toBeVisible();
    await clean();
    // A token override row (colour picker and hex), then one with a length, then a refused value with its message.
    await page.getByRole('button', { name: 'Add token' }).click();
    await expect(page.getByRole('combobox', { name: 'Token for row 1' })).toBeVisible();
    await clean();
    await page.getByRole('combobox', { name: 'Token for row 1' }).selectOption('--rs');
    await clean();
    await page.getByRole('button', { name: 'Add token' }).click();
    await page.getByRole('textbox', { name: '--page-light value' }).fill('url(x)');
    await expect(page.getByRole('alert').filter({ hasText: 'Not allowed' })).toBeVisible();
    await clean();
    // A pair token: a light and a dark colour, each with a picker and a hex, and the "Same for both" toggle; then a shadow and a weight.
    await page.getByRole('combobox', { name: 'Token for row 2' }).selectOption('--btn');
    await expect(page.getByRole('textbox', { name: '--btn light value' })).toBeVisible();
    await expect(page.getByRole('textbox', { name: '--btn dark value' })).toBeVisible();
    await clean();
    await page.getByRole('checkbox', { name: '--btn: same for both schemes' }).check();
    await clean();
    await page.getByRole('button', { name: 'Add token' }).click();
    await page.getByRole('combobox', { name: 'Token for row 3' }).selectOption('--shadow');
    await page.getByRole('textbox', { name: '--shadow value' }).fill('0 0');
    await expect(page.getByRole('alert').filter({ hasText: 'shadow layer' })).toBeVisible();
    await clean();
    await page.getByRole('combobox', { name: 'Token for row 3' }).selectOption('--heading-weight');
    await clean();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.getByRole('button', { name: 'Discard' }).click();

    await page.getByRole('button', { name: '+ Create theme' }).click();
    await clean();
  });

  test(`the forms page and the form editor, tab by tab, have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, { path: ftPath('forms'), state: ftState() });
    const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    const clean = async () => expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);

    await expect(page.getByRole('button', { name: 'Open form credential', exact: true })).toBeVisible();
    await clean();
    await page.getByRole('group', { name: 'Filter forms' }).getByRole('button', { name: 'Missing translations' }).click();
    await clean();

    await page.getByRole('button', { name: 'Open form credential', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'credential' })).toBeVisible();
    await expect(page.locator('versola-preview-pane iframe')).toHaveCount(1);
    await clean();

    // The JSON editor with problems listed under it (a language lacking a key and a spelled-differently placeholder), and the preview in another language.
    const editor = page.getByRole('textbox', { name: 'Form texts, JSON' });
    const shown = JSON.parse(await editor.inputValue());
    delete shown.ru.passkey_button;
    shown.kk.title = 'Кіру, {name}';
    await editor.fill(JSON.stringify(shown, null, 2));
    await expect(page.getByRole('list', { name: 'Problems in the texts' }).getByRole('listitem')).toHaveCount(2);
    await page.getByLabel('Language', { exact: true }).selectOption('ru');
    await clean();

    for (const name of ['Style', 'Script', 'Versions']) {
      await page.getByRole('tab', { name }).click();
      await clean();
    }

    await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Forms' }).click();
    await page.getByRole('button', { name: 'Discard' }).click(); // the texts were edited above
    await page.getByRole('button', { name: '+ Create form' }).click();
    await clean();
  });
}

/**
 * The side panel of each small-entity section (G1): the record in view mode, one group editor
 * (modal) and the create form, in both themes. The panel is slotted light DOM inside a shadow
 * dialog, so axe sees it as the browser does: through the flattened tree.
 */
const panelSections = [
  { view: 'scopes', path: '/?view=scopes&tenant=tenant-alpha', open: /^Open scope (.* )?openid$/, title: 'OpenID scope', edit: 'Edit claims', editor: 'Edit claims', create: '+ Create Scope', creator: 'Create scope' },
  { view: 'permissions', path: '/?view=permissions&tenant=tenant-alpha', open: /^Open permission (.* )?alpha:read$/, title: 'Read alpha resources', edit: 'Edit endpoints', editor: 'Edit endpoints', create: '+ Create Permission', creator: 'Create permission' },
  { view: 'roles', path: '/?view=roles&tenant=tenant-alpha', open: /^Open role (.* )?alpha-admin$/, title: 'Alpha admin', edit: 'Edit permissions', editor: 'Edit permissions', create: '+ Create Role', creator: 'Create role' },
  { view: 'tenants', path: '/?view=tenants&tenant=tenant-alpha', open: /^Open tenant (.* )?tenant-bravo$/, title: 'Bravo Workspace', edit: 'Edit edge', editor: 'Edit edge', create: '+ Create Tenant', creator: 'Create tenant' },
  { view: 'edges', path: '/?view=edges', open: 'Open edge edge-alpha', title: 'edge-alpha', edit: 'Edit dpop nonce', editor: 'Edit DPoP nonce', create: '+ Create Edge', creator: 'Register edge' },
] as const;

for (const theme of themes) {
  for (const section of panelSections) {
    test(`the ${section.view} panel, its group editor and its create form have no axe violations in the ${theme} theme`, async ({ page }) => {
      await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
      await loadAdminApp(page, { path: section.path, state: { edges: [{ id: 'edge-alpha', hasOldKey: true, requireDpopNonce: true }] } });
      const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      const clean = async () => expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);

      await page.getByRole('button', { name: section.open }).click();
      await expect(sidePanelDialog(page, section.title)).toBeVisible();
      await expect(page.getByText('Loading…')).toHaveCount(0);
      await clean();

      await sidePanel(page, section.title).getByRole('button', { name: section.edit, exact: true }).click();
      await expect(sidePanelDialog(page, section.editor)).toBeVisible();
      await clean();

      await sidePanel(page, section.editor).getByRole('button', { name: 'Cancel', exact: true }).click();
      await sidePanel(page, section.title).getByRole('button', { name: 'Close panel' }).click();
      await page.getByRole('button', { name: section.create, exact: true }).click();
      await expect(sidePanelDialog(page, section.creator)).toBeVisible();
      await clean();
    });
  }
}

for (const theme of themes) {
  test(`the partner step of the client wizard has no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, {
      path: '/?view=clients&tenant=tenant-alpha',
      state: {
        themes: [{ id: 'default', css: '', tenantId: null, tokens: { base: 'versola' } }],
        themePresets: [{ id: 'versola', title: { en: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } }],
        forms: [consentForm],
        formPreviews: [consentPreviews],
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
    // The preview is part of the block: scan it drawn, not while central is still rendering it.
    await expect(page.locator('versola-client-consent-preview iframe')).toHaveCount(1);

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect(results.violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
  });
}

/**
 * G2 screens with data: the Security page and one of its group editors, and the side panel of
 * OTP templates, authorization details and keys (view, editor, create), in both themes.
 */
const g2Locales = [{ code: 'en', name: 'English', isDefault: true, active: true }, { code: 'ru', name: 'Russian', isDefault: false, active: true }];
const g2State = {
  locales: g2Locales,
  challengeSettings: {
    'tenant-alpha': {
      tenantId: 'tenant-alpha', securityProfile: 'fapi2' as const,
      phoneRegions: [{ region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}', registration: true }],
      emailRegistration: { mode: 'allowlist' as const, domains: ['example.com'], blockSubaddress: false }, defaultRoles: ['alpha-admin'],
      submissionLimits: { otpRequest: [{ maxAttempts: 5, windowSeconds: 600 }], otpSubmit: [], passwordSubmit: [], passkeyAssertion: [], banDurationSeconds: 3600 },
      otpLength: 6, otpResendAfter: 60, authConversationTtlSeconds: 900, sessionTtlSeconds: 86400, sessionIdleTtlSeconds: null, userAgentTtlSeconds: 15552000, ipHeader: 'X-Real-IP',
      passkeySettings: { rpId: 'example.com', rpName: 'Example', origins: ['https://example.com'], userVerification: 'required' },
      requireDpopNonce: true, signingKeyId: 'es256-a1', clientAssertionMaxLifetimeSeconds: 300, acrVocabulary: { 'urn:example:mfa': ['password', 'otp'] }, postLogoutRedirectUris: [], logoLight: null, logoDark: null,
    },
  } as never,
  otpTemplates: { 'tenant-alpha': [{ id: 'login-otp', tenantId: 'tenant-alpha', purpose: 'otp', channel: 'sms' as const, localizations: { en: 'Your code is {{code}}', ru: 'Ваш код {{code}}' } }] },
  authorizationDetailTypes: { 'tenant-alpha': [{ type: 'payment_initiation', description: { en: 'Payment initiation' }, schema: { type: 'object', required: ['type'] } }] },
  jwksKeys: [{ kid: 'es256-a1', kty: 'EC', alg: 'ES256', use: 'sig', crv: 'P-256', x: 'f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU', y: 'x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0' }] as never,
  jwksKeySummaries: [{ kid: 'es256-a1', algorithm: 'ES256', keyType: 'EC', curve: 'P-256', canSign: true }],
};

const g2Panels = [
  { view: 'otp-templates', open: /^Open template login-otp otp sms$/, title: 'login-otp', edit: 'Edit message', editor: 'Edit message', create: '+ Create Template', creator: 'Create template' },
  { view: 'authorization-details', open: /^Open type .*payment_initiation$/, title: 'Payment initiation', edit: 'Edit json schema', editor: 'Edit JSON Schema', create: '+ Create Type', creator: 'Create type' },
  { view: 'keys', open: 'Open key es256-a1', title: 'es256-a1', edit: 'Edit public key', editor: 'Edit public key', create: 'Generate key', creator: 'Generate key' },
] as const;

for (const theme of themes) {
  test(`the Security page and a group editor have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, { path: '/?view=security&tenant=tenant-alpha', state: g2State });
    await expect(page.getByRole('region', { name: 'Sessions', exact: true })).toBeVisible();
    const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);

    for (const group of ['Registration', 'Tokens & keys', 'Sign-in methods']) {
      await page.getByRole('region', { name: group, exact: true }).getByRole('button', { name: /^Edit/ }).click();
      await expect(page.getByRole('button', { name: /^Save / })).toBeVisible();
      expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
      await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    }
  });

  for (const section of g2Panels) {
    test(`the ${section.view} panel, its editor and its create form have no axe violations in the ${theme} theme`, async ({ page }) => {
      await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
      await loadAdminApp(page, { path: `/?view=${section.view}&tenant=tenant-alpha`, state: g2State });
      // The fixture template is an SMS: axe cannot inject into the sandboxed email preview (scripts
      // are off in it) and waits for it forever, so the iframe is covered by its title, not a scan.
      const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      const clean = async () => expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);

      await page.getByRole('button', { name: section.open }).click();
      await expect(sidePanelDialog(page, section.title)).toBeVisible();
      await clean();
      await sidePanel(page, section.title).getByRole('button', { name: section.edit, exact: true }).click();
      await expect(sidePanelDialog(page, section.editor)).toBeVisible();
      await clean();
      await sidePanel(page, section.editor).getByRole('button', { name: 'Cancel', exact: true }).click();
      await sidePanel(page, section.title).getByRole('button', { name: 'Close panel' }).click();
      await page.getByRole('button', { name: section.create, exact: true }).click();
      await expect(sidePanelDialog(page, section.creator)).toBeVisible();
      await clean();
    });
  }

  test(`System settings with its locale form and password editor have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    await loadAdminApp(page, { path: '/?view=system-settings', state: g2State });
    await page.getByRole('button', { name: '+ Add locale' }).click();
    await expect(page.getByRole('form', { name: 'Add locale' })).toBeVisible();
    const scan = () => new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
    await page.getByRole('button', { name: 'Edit password policy' }).click();
    await expect(page.getByRole('button', { name: 'Save password policy' })).toBeVisible();
    expect((await scan()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`)).toEqual([]);
  });
}

for (const theme of themes) {
  test(`the rate limit editors of an endpoint and of a client have no axe violations in the ${theme} theme`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
    const scan = async () => (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze())
      .violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`);
    const limit = { limits: [{ limit: 30, windowSeconds: 60 }, { limit: 500, windowSeconds: 3600 }], burst: 5, by: 'subject', mode: 'enforce' };
    const client = {
      id: 'alpha-web', clientName: { en: 'Alpha Web' }, redirectUris: ['https://alpha.example/callback'], scope: ['openid'], permissions: [],
      secretRotation: false, accessTokenTtl: 3600, authMethod: 'client_secret', kind: 'web', rateLimit: limit,
      authFlow: { primary: { credentials: ['phone'], inlinePassword: false, factors: [{ type: 'otp', required: true }] }, passkey: null, otpType: 'sms' },
    };
    const state = {
      ...shopState,
      clients: { 'tenant-alpha': [client] },
      resources: { 'tenant-alpha': [{ ...ordersApi, endpoints: ordersApi.endpoints.map(e => (e.id === 5 ? { ...e, rateLimit: limit } : e)) }] },
    };
    await loadAdminApp(page, { path: '/?view=resources&tenant=tenant-alpha&id=orders-api&edit=endpoints', state });
    await page.getByRole('list', { name: 'Endpoints' }).getByRole('button', { name: /^POST \/orders\/\{orderId\}\/refund(?= |$)/ }).click();
    await expect(page.getByRole('region', { name: 'Rate limit' })).toContainText('Mode');
    expect(await scan()).toEqual([]);
    // the same editor with an error shown
    await page.getByLabel('Burst').fill('9999');
    await expect(page.getByText('burst must not exceed the smallest limit')).toBeVisible();
    expect(await scan()).toEqual([]);

    await page.goto('/?view=clients&tenant=tenant-alpha');
    await page.getByRole('button', { name: /^Open client (.* )?alpha-web$/ }).click();
    await expect(page.getByRole('region', { name: 'Rate limit' })).toContainText('Enforce');
    expect(await scan()).toEqual([]);
    await page.getByRole('button', { name: 'Edit rate limit', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Rate limit', exact: true })).toBeVisible();
    expect(await scan()).toEqual([]);
    await page.getByRole('button', { name: 'Remove limit' }).click();
    expect(await scan()).toEqual([]);
  });
}
