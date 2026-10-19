import { expect, test, type Page } from '@playwright/test';
import { INVALID_FIELD_BORDER, findRequest, loadAdminApp } from './fixtures';
import type { MockConfigState } from './mocks';

/**
 * Sign-in and security: one page of anchored cards under a section rail, each card with its own
 * Edit opening that group on a page of its own. The record is a whole-record PUT, so every Save
 * sends the other groups' current values back unchanged.
 */
const tenant = 'tenant-alpha';
const base = `/?view=security&tenant=${tenant}`;
const settings = {
  tenantId: tenant,
  securityProfile: 'fapi2' as const,
  phoneRegions: [
    { region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}', registration: true },
    { region: 'RU', dialCode: '+7', length: 10, regex: '9\\d{9}', registration: false },
  ],
  emailRegistration: { mode: 'allowlist' as const, domains: ['example.com', 'partner.example.org'], blockSubaddress: false },
  defaultRoles: ['user'],
  submissionLimits: {
    otpRequest: [{ maxAttempts: 5, windowSeconds: 600 }],
    otpSubmit: [{ maxAttempts: 5, windowSeconds: 600 }],
    passwordSubmit: [{ maxAttempts: 10, windowSeconds: 900 }],
    passkeyAssertion: [{ maxAttempts: 10, windowSeconds: 900 }],
    banDurationSeconds: 3600,
  },
  otpLength: 6,
  otpResendAfter: 60,
  authConversationTtlSeconds: 900,
  sessionTtlSeconds: 86400,
  sessionIdleTtlSeconds: null,
  userAgentTtlSeconds: 15552000,
  ipHeader: 'X-Real-IP',
  passkeySettings: { rpId: 'shop.example.com', rpName: 'Example Shop', origins: ['https://shop.example.com'], userVerification: 'required' },
  requireDpopNonce: true,
  mtlsCertificateHeader: null,
  mtlsCertificateEncoding: null,
  signingKeyId: 'es256-a1',
  clientAssertionMaxLifetimeSeconds: 300,
  acrVocabulary: { 'urn:example:mfa': ['password', 'otp'] },
  postLogoutRedirectUris: ['https://shop.example.com/bye'],
  logoLight: null,
  logoDark: null,
};
const keySummaries = [
  { kid: 'es256-a1', algorithm: 'ES256', keyType: 'EC', curve: 'P-256', canSign: true },
  { kid: 'rs256-old', algorithm: 'RS256', keyType: 'RSA', curve: null, canSign: true },
  { kid: 'partner-ps256', algorithm: 'PS256', keyType: 'RSA', curve: null, canSign: false },
];
const roles = [
  { id: 'user', description: { en: 'Self-registered user' }, permissions: [], active: true },
  { id: 'shop-manager', description: { en: 'Shop manager' }, permissions: [], active: true },
];
const state = (extra: Partial<MockConfigState> = {}): Partial<MockConfigState> => ({
  challengeSettings: { [tenant]: settings } as MockConfigState['challengeSettings'],
  roles: { [tenant]: roles },
  jwksKeySummaries: keySummaries,
  ...extra,
});
const readOnly = { resources: { central: { permissions: ['security:read'] } }, isProd: false };
const put = '/configuration/challenges/challenge-settings';

async function lastSaved(requests: Parameters<typeof findRequest>[0]) {
  return (await findRequest(requests, 'PUT', put)).body as Record<string, unknown>;
}

function card(page: Page, name: string) {
  return page.getByRole('region', { name, exact: true });
}

test('reads the whole record as cards with the tenant and its profile in the header', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: state() });

  await expect(page.getByRole('heading', { name: 'Sign-in and security', level: 1 })).toBeVisible();
  await expect(page.locator('versola-record-header').getByText(tenant, { exact: true })).toBeVisible();
  await expect(page.locator('versola-record-header').getByText('FAPI 2.0')).toBeVisible();
  await expect(card(page, 'Sign-in methods')).toContainText('KZ +7 · 10 digits');
  await expect(card(page, 'Sign-in methods')).toContainText('sign-up: KZ');
  await expect(card(page, 'One-time codes')).toContainText('6 digits');
  await expect(card(page, 'Passkeys (WebAuthn)')).toContainText('shop.example.com');
  await expect(card(page, 'Registration')).toContainText('Allowlist');
  await expect(card(page, 'Registration')).toContainText('partner.example.org');
  await expect(card(page, 'Step-up (ACR vocabulary)')).toContainText('urn:example:mfa');
  await expect(card(page, 'Sessions')).toContainText('Disabled');
  await expect(card(page, 'Tokens & keys')).toContainText('es256-a1');
  await expect(card(page, 'Tokens & keys')).toContainText('Required on every proof');
  await expect(card(page, 'Logout')).toContainText('https://shop.example.com/bye');
  await expect(card(page, 'Submission limits')).toContainText('5 per 10m');
  await expect(card(page, 'Security profile')).toContainText('Chosen when the tenant was created');
  await expect(page.getByRole('button', { name: /^Edit/ })).toHaveCount(10);
});

test('a standard tenant says Standard and an unconfigured group says so', async ({ page }) => {
  await loadAdminApp(page, {
    path: base,
    state: state({ challengeSettings: { [tenant]: { ...settings, securityProfile: 'standard', passkeySettings: null, acrVocabulary: {}, signingKeyId: null } } as MockConfigState['challengeSettings'] }),
  });

  await expect(page.locator('versola-record-header').getByText('Standard', { exact: true })).toBeVisible();
  await expect(card(page, 'Passkeys (WebAuthn)')).toContainText('Passkeys are not configured for this tenant');
  await expect(card(page, 'Step-up (ACR vocabulary)')).toContainText('No ACR vocabulary configured.');
  await expect(card(page, 'Tokens & keys')).toContainText("No key selected: auth signs with its own configured private key.");
});

test('the section rail jumps to a card and the narrow select does the same', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: state() });

  const rail = page.getByRole('navigation', { name: 'On this page' });
  await expect(rail.getByRole('link')).toHaveText(['Sign-in methods', 'One-time codes', 'Passkeys', 'Registration', 'Step-up (ACR)', 'Branding', 'Sessions', 'Tokens & keys', 'Logout', 'Submission limits', 'Security profile']);
  await rail.getByRole('link', { name: 'Sessions' }).click();
  await expect(rail.getByRole('link', { name: 'Sessions' })).toHaveAttribute('aria-current', 'true');
  await expect(page).toHaveURL(/#sessions$/);
  await expect(card(page, 'Sessions')).toBeInViewport();
});

test('the rail items look like the sidebar nav items: active fill and bar, neutral hover', async ({ page }) => {
  // Hover colours are transitioned; under reduced motion the design system zeroes every transition,
  // so a computed colour read after hover is the final one, not a frame on the way there.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await loadAdminApp(page, { path: base, state: state() });

  const look = (locator: ReturnType<typeof page.locator>) => locator.evaluate(node => {
    const style = getComputedStyle(node);
    return { background: style.backgroundColor, color: style.color, shadow: style.boxShadow, radius: style.borderRadius, weight: style.fontWeight, size: style.fontSize };
  });
  const rail = page.getByRole('navigation', { name: 'On this page' });
  const navActive = page.locator('versola-navigation .nav-item.active');
  expect(await look(rail.locator('a[aria-current="true"]'))).toEqual(await look(navActive));

  const navIdle = page.locator('versola-navigation .nav-item:not(.active)').first();
  const railIdle = rail.locator('a:not([aria-current="true"])').first();
  await navIdle.hover();
  await expect.poll(async () => (await look(navIdle)).background).not.toBe('rgba(0, 0, 0, 0)');
  await expect.poll(async () => (await look(navIdle)).color).toBe('rgb(28, 27, 25)');
  const navHover = await look(navIdle);
  await railIdle.hover();
  await expect.poll(async () => (await look(railIdle)).background).toBe(navHover.background);
  await expect.poll(async () => (await look(railIdle)).color).toBe(navHover.color);
});

test('below 1200px the rail is a select and nothing scrolls sideways', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: base, state: state() });

  await expect(page.getByRole('navigation', { name: 'On this page' })).toBeHidden();
  await page.getByRole('combobox', { name: 'On this page' }).selectOption('sessions');
  await expect(card(page, 'Sessions')).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test('with no settings it shows the empty state and Set up opens the first editor', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: state() });
  await page.route('**/configuration/challenges/challenge-settings?*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ settings: null }) }));
  await page.reload();

  await expect(page.getByRole('heading', { name: 'No security settings yet' })).toBeVisible();
  await page.getByRole('button', { name: 'Set up settings' }).click();
  await expect(page).toHaveURL(/edit=passkeys/);
  await expect(page.getByRole('heading', { name: 'Edit passkeys' })).toBeVisible();
});

test('every group opens its own editor from its Edit and the URL follows', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: state() });

  await card(page, 'Sessions').getByRole('button', { name: /^Edit/ }).click();
  await expect(page).toHaveURL(/edit=sessions/);
  await expect(page.getByRole('heading', { name: 'Edit sessions', level: 1 })).toBeVisible();
  await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Sign-in and security' }).click();
  await expect(page).not.toHaveURL(/edit=/);
  await expect(card(page, 'Sessions')).toBeVisible();
});

test('restores a group editor from the URL', async ({ page }) => {
  await loadAdminApp(page, { path: `${base}&edit=codes`, state: state() });

  await expect(page.getByLabel('Code length')).toHaveValue('6');
});

test('Save in one group sends the whole record with the other groups unchanged', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=sessions`, state: state() });

  await page.getByLabel('SSO session', { exact: true }).fill('2');
  await page.getByLabel('SSO session unit').selectOption('days');
  await page.getByRole('button', { name: 'Save sessions' }).click();

  const body = await lastSaved(api.requests);
  expect(body).toMatchObject({
    tenantId: tenant,
    sessionTtlSeconds: 172800,
    authConversationTtlSeconds: 900,
    otpLength: 6,
    phoneRegions: settings.phoneRegions,
    emailRegistration: settings.emailRegistration,
    defaultRoles: ['user'],
    passkeySettings: settings.passkeySettings,
    requireDpopNonce: true,
    signingKeyId: 'es256-a1',
    acrVocabulary: settings.acrVocabulary,
    postLogoutRedirectUris: settings.postLogoutRedirectUris,
    submissionLimits: settings.submissionLimits,
  });
  await expect(page).not.toHaveURL(/edit=/);
  await expect(card(page, 'Sessions')).toContainText('2d');
});

test('an idle timeout longer than nothing can be switched on and is sent in seconds', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=sessions`, state: state() });

  await page.getByRole('checkbox', { name: 'Idle timeout' }).check();
  await page.locator('#sessionIdleTtlSeconds').fill('30');
  await page.getByLabel('Idle timeout unit').selectOption('minutes');
  await page.getByRole('button', { name: 'Save sessions' }).click();

  expect(await lastSaved(api.requests)).toMatchObject({ sessionIdleTtlSeconds: 1800 });
});

test('refuses a zero TTL and sends nothing', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=sessions`, state: state() });

  const sso = page.getByLabel('SSO session', { exact: true });
  await sso.fill('0');
  await page.getByRole('button', { name: 'Save sessions' }).click();
  await expect(sso).toHaveAttribute('aria-invalid', 'true');
  await expect(sso).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await expect(page.getByText('At least one second.')).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === put)).toBe(false);
});

test('edits the one-time codes and refuses a resend of zero', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=codes`, state: state() });

  await page.getByLabel('Resend after', { exact: true }).fill('0');
  await page.getByRole('button', { name: 'Save one-time codes' }).click();
  await expect(page.getByText('At least one second.')).toBeVisible();
  await page.getByLabel('Resend after', { exact: true }).fill('2');
  await page.getByLabel('Resend after unit').selectOption('minutes');
  await page.getByLabel('Code length').fill('8');
  await page.getByRole('button', { name: 'Save one-time codes' }).click();

  expect(await lastSaved(api.requests)).toMatchObject({ otpLength: 8, otpResendAfter: 120 });
});

test('edits phone regions: add, reorder, validate and send them in order', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=methods`, state: state() });

  const rows = page.getByTestId('region-row');
  await expect(rows).toHaveCount(2);
  await page.getByRole('button', { name: '+ Add region' }).click();
  await page.getByRole('button', { name: 'Save sign-in methods' }).click();
  await expect(page.getByText('Fix the highlighted fields.')).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === put)).toBe(false);

  const added = rows.nth(2);
  await added.getByLabel('Region').fill('uz');
  await added.getByLabel('Dial code').fill('+998');
  await added.getByLabel('Length').fill('9');
  await added.getByLabel('Regex').fill('9\\d{8}');
  await added.getByLabel('Registration').check();
  await added.getByRole('button', { name: 'Move up' }).click();
  await page.getByRole('button', { name: 'Save sign-in methods' }).click();

  expect((await lastSaved(api.requests)).phoneRegions).toEqual([
    settings.phoneRegions[0],
    { region: 'UZ', dialCode: '+998', length: 9, regex: '9\\d{8}', registration: true },
    settings.phoneRegions[1],
  ]);
});

test('adds countries from the presets with registration on and skips those already listed', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=methods`, state: state() });

  await page.getByRole('button', { name: 'Add from presets' }).click();
  const picker = page.getByRole('dialog', { name: 'Add from presets' });
  await expect(picker.getByLabel('Kazakhstan')).toBeDisabled();
  await expect(picker.getByLabel('Kazakhstan')).toBeChecked();
  await picker.getByLabel('Search presets').fill('belarus');
  await picker.getByLabel('Belarus').check();
  await picker.getByRole('button', { name: 'Add 1 selected' }).click();
  await expect(picker).toHaveCount(0);
  await expect(page.getByTestId('region-row')).toHaveCount(3);
  await page.getByRole('button', { name: 'Save sign-in methods' }).click();

  expect((await lastSaved(api.requests)).phoneRegions).toMatchObject([{ region: 'KZ' }, { region: 'RU' }, { region: 'BY', registration: true }]);
});

test('Escape closes the preset picker without adding anything', async ({ page }) => {
  await loadAdminApp(page, { path: `${base}&edit=methods`, state: state() });

  await page.getByRole('button', { name: 'Add from presets' }).click();
  await page.getByLabel('Search presets').focus();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: 'Add from presets' })).toHaveCount(0);
  await expect(page.getByTestId('region-row')).toHaveCount(2);
});

test('edits the client IP header, choosing a custom one', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=methods`, state: state() });

  await page.getByLabel('Header', { exact: true }).selectOption('custom');
  await page.getByRole('button', { name: 'Save sign-in methods' }).click();
  await expect(page.getByText('The client IP header is required.')).toBeVisible();
  await page.locator('#ipHeader').fill('X-Client-IP');
  await page.getByRole('button', { name: 'Save sign-in methods' }).click();
  expect(await lastSaved(api.requests)).toMatchObject({ ipHeader: 'X-Client-IP' });
});

test('edits passkeys and requires a relying party and an origin', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=passkeys`, state: state() });

  await page.getByLabel('Relying party ID').fill('');
  await page.getByRole('button', { name: 'Save passkeys' }).click();
  await expect(page.getByText('The relying party ID is required.')).toBeVisible();
  await page.getByLabel('Relying party ID').fill('shop.example.com');
  await page.getByLabel('User verification').selectOption('preferred');
  await page.getByRole('button', { name: '+ Add origin' }).click();
  await page.getByLabel('Origin', { exact: true }).last().fill('https://app.example.com');
  await page.getByRole('button', { name: 'Save passkeys' }).click();

  expect((await lastSaved(api.requests)).passkeySettings).toEqual({
    rpId: 'shop.example.com',
    rpName: 'Example Shop',
    origins: ['https://shop.example.com', 'https://app.example.com'],
    userVerification: 'preferred',
  });
});

test('another group cannot be saved while passkeys are not configured, and says so', async ({ page }) => {
  await loadAdminApp(page, {
    path: `${base}&edit=sessions`,
    state: state({ challengeSettings: { [tenant]: { ...settings, passkeySettings: null } } as MockConfigState['challengeSettings'] }),
  });

  await expect(page.getByRole('alert').first()).toContainText('Passkeys are not configured for this tenant');
  await expect(page.getByRole('button', { name: 'Save sessions' })).toBeDisabled();
});

test('registration: the domain list is labelled by mode, validated, and the roles are required', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=registration`, state: state() });

  await expect(page.getByLabel('Allowed domains')).toHaveValue('example.com\npartner.example.org');
  await page.getByLabel('Email registration', { exact: true }).selectOption('open');
  await expect(page.getByLabel('Blocked domains')).toBeVisible();
  await page.getByLabel('Blocked domains').fill('Not A Domain');
  await page.getByRole('button', { name: 'Save registration' }).click();
  await expect(page.locator('#domains-error')).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === put)).toBe(false);

  await page.getByLabel('Blocked domains').fill('spam.example\n*.junk.example');
  await page.getByLabel('Block addresses with + = # (sub-addresses)').check();
  await page.getByRole('button', { name: 'Save registration' }).click();
  expect((await lastSaved(api.requests)).emailRegistration).toEqual({ mode: 'open', domains: ['spam.example', '*.junk.example'], blockSubaddress: true });
});

test('registration: at least one default role, chosen from the role picker', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=registration`, state: state() });

  const picker = page.locator('versola-role-picker');
  await picker.getByRole('checkbox', { name: /user/ }).first().uncheck();
  await page.getByRole('button', { name: 'Save registration' }).click();
  await expect(page.getByText('Select at least one default role.')).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === put)).toBe(false);

  await picker.getByRole('checkbox', { name: /shop-manager/ }).first().check();
  await page.getByRole('button', { name: 'Save registration' }).click();
  expect((await lastSaved(api.requests)).defaultRoles).toEqual(['shop-manager']);
});

test('a default role in the card opens that role', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: state() });

  await card(page, 'Registration').getByRole('button', { name: 'user', exact: true }).click();
  await expect(page).toHaveURL(/view=roles/);
  await expect(page).toHaveURL(/id=user/);
});

test('step-up: adds an ACR with factors, refusing one without a factor', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=stepup`, state: state() });

  await page.getByRole('button', { name: '+ Add ACR' }).click();
  await page.getByLabel('ACR value').last().fill('urn:example:passkey');
  await page.getByRole('button', { name: 'Save step-up' }).click();
  await expect(page.getByText('Pick at least one factor.')).toBeVisible();
  await page.locator('.acr-row').last().getByRole('checkbox', { name: 'passkey' }).check();
  await page.getByRole('button', { name: 'Save step-up' }).click();

  expect((await lastSaved(api.requests)).acrVocabulary).toEqual({ 'urn:example:mfa': ['password', 'otp'], 'urn:example:passkey': ['passkey'] });
});

test('tokens: lists only signable keys, hides RS256 under FAPI 2.0 and clears the key as an explicit null', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=tokens`, state: state() });

  const select = page.getByLabel('Signing key', { exact: true });
  await expect(select.locator('option')).toHaveText(["Auth service's configured key", 'es256-a1 · ES256']);
  await select.selectOption('');
  await page.getByRole('button', { name: 'Save tokens & keys' }).click();

  const body = await lastSaved(api.requests);
  expect(body).toHaveProperty('signingKeyId', null);
});

test('tokens: a standard tenant may pick an RS256 key; DPoP nonce and mutual TLS are saved', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: `${base}&edit=tokens`,
    state: state({ challengeSettings: { [tenant]: { ...settings, securityProfile: 'standard' } } as MockConfigState['challengeSettings'] }),
  });

  const select = page.getByLabel('Signing key', { exact: true });
  await expect(select.locator('option')).toHaveText(["Auth service's configured key", 'es256-a1 · ES256', 'rs256-old · RS256']);
  await select.selectOption('rs256-old');
  await page.getByLabel('Require a DPoP nonce on every proof').uncheck();
  await page.getByLabel("This tenant's reverse proxy terminates mutual TLS").check();
  await page.getByLabel('Certificate header').selectOption({ label: 'X-Forwarded-Tls-Client-Cert (Traefik)' });
  await page.getByLabel('Client assertion lifetime', { exact: true }).fill('10');
  await page.getByLabel('Client assertion lifetime unit').selectOption('minutes');
  await page.getByRole('button', { name: 'Save tokens & keys' }).click();

  expect(await lastSaved(api.requests)).toMatchObject({
    signingKeyId: 'rs256-old',
    requireDpopNonce: false,
    mtlsCertificateHeader: 'X-Forwarded-Tls-Client-Cert',
    mtlsCertificateEncoding: 'base64Der',
    clientAssertionMaxLifetimeSeconds: 600,
  });
});

test('tokens: a custom mTLS header is required once mutual TLS is on', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=tokens`, state: state() });

  await page.getByLabel("This tenant's reverse proxy terminates mutual TLS").check();
  await page.getByLabel('Certificate header').selectOption('custom');
  await page.getByRole('button', { name: 'Save tokens & keys' }).click();
  await expect(page.getByText('The certificate header is required when mutual TLS is terminated.')).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === put)).toBe(false);
});

test('the signing key in the card opens that key', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: state() });

  await card(page, 'Tokens & keys').getByRole('button', { name: 'es256-a1' }).click();
  await expect(page).toHaveURL(/view=keys/);
  await expect(page).toHaveURL(/id=es256-a1/);
});

test('logout: adds and removes redirect URIs and refuses a malformed one', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=logout`, state: state() });

  await page.getByRole('button', { name: '+ Add URI' }).click();
  await page.getByLabel('Post-logout redirect URI', { exact: true }).last().fill('not a url');
  await page.getByRole('button', { name: 'Save logout' }).click();
  await expect(page.getByRole('alert').filter({ hasText: /URL|URI|http/i }).first()).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === put)).toBe(false);

  await page.getByLabel('Post-logout redirect URI', { exact: true }).last().fill('https://shop.example.com/signed-out');
  await page.getByRole('button', { name: 'Remove post-logout redirect uri https://shop.example.com/bye' }).click();
  await page.getByRole('button', { name: 'Save logout' }).click();
  expect((await lastSaved(api.requests)).postLogoutRedirectUris).toEqual(['https://shop.example.com/signed-out']);
});

test('submission limits: edits a window, adds one and the ban duration', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=limits`, state: state() });

  await page.getByLabel('OTP request max attempts').fill('3');
  await page.getByRole('button', { name: '+ Add window' }).first().click();
  await page.getByLabel('OTP request max attempts').last().fill('20');
  await page.getByLabel('OTP request window in seconds').last().fill('3600');
  await page.getByLabel('Ban duration', { exact: true }).fill('2');
  await page.getByLabel('Ban duration unit').selectOption('hours');
  await page.getByRole('button', { name: 'Save submission limits' }).click();

  const limits = (await lastSaved(api.requests)).submissionLimits as typeof settings.submissionLimits;
  expect(limits.otpRequest).toEqual([{ maxAttempts: 3, windowSeconds: 600 }, { maxAttempts: 20, windowSeconds: 3600 }]);
  expect(limits.banDurationSeconds).toBe(7200);
  expect(limits.otpSubmit).toEqual(settings.submissionLimits.otpSubmit);
});

test('branding: saves a logo pair, previews each on its own scheme, refuses a single file and clears both as nulls', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&edit=branding`, state: state() });

  await page.getByLabel(/^Logo for the light scheme/).fill('https://example.com/logo-light.svg');
  await page.getByRole('button', { name: 'Save branding' }).click();
  await expect(page.getByText('The logo needs both a light and a dark file, or neither.')).toBeVisible();
  await page.getByLabel(/^Logo for the dark scheme/).fill('https://example.com/logo-dark.svg');
  await expect(page.getByTestId('logo-preview-light').locator('img')).toHaveAttribute('src', 'https://example.com/logo-light.svg');
  await expect(page.getByTestId('logo-preview-dark').locator('img')).toHaveAttribute('src', 'https://example.com/logo-dark.svg');
  await page.getByRole('button', { name: 'Save branding' }).click();
  expect(await lastSaved(api.requests)).toMatchObject({ logoLight: 'https://example.com/logo-light.svg', logoDark: 'https://example.com/logo-dark.svg' });
  await expect(card(page, 'Branding')).toContainText('https://example.com/logo-dark.svg');

  await card(page, 'Branding').getByRole('button', { name: /^Edit/ }).click();
  await page.getByLabel(/^Logo for the light scheme/).fill('');
  await page.getByLabel(/^Logo for the dark scheme/).fill('');
  await page.getByRole('button', { name: 'Save branding' }).click();
  const cleared = await lastSaved(api.requests);
  expect(cleared).toHaveProperty('logoLight', null);
  expect(cleared).toHaveProperty('logoDark', null);
});

test('asks before Cancel drops an edited draft, and leaves at once when nothing changed', async ({ page }) => {
  await loadAdminApp(page, { path: `${base}&edit=codes`, state: state() });

  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page).not.toHaveURL(/edit=/);
  await card(page, 'One-time codes').getByRole('button', { name: /^Edit/ }).click();
  await page.getByLabel('Code length').fill('9');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  const confirm = page.getByRole('dialog', { name: 'Discard changes?' });
  await confirm.getByRole('button', { name: 'Keep editing' }).click();
  await expect(page).toHaveURL(/edit=codes/);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await confirm.getByRole('button', { name: 'Discard' }).click();
  await expect(page).not.toHaveURL(/edit=/);
});

test('a server error stays in the editor with the draft intact', async ({ page }) => {
  await loadAdminApp(page, { path: `${base}&edit=codes`, state: state() });
  await page.route(`**${put}`, route => route.request().method() === 'PUT' ? route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ message: 'otpLength must be between 4 and 10' }) }) : route.fallback());

  await page.getByLabel('Code length').fill('9');
  await page.getByRole('button', { name: 'Save one-time codes' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'otpLength must be between 4 and 10' })).toBeVisible();
  await expect(page.getByLabel('Code length')).toHaveValue('9');
  await expect(page).toHaveURL(/edit=codes/);
});

test('read-only console: no Edit buttons and no Set up', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: state({ myPermissions: readOnly }) });

  await expect(card(page, 'Sessions')).toBeVisible();
  await expect(page.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
});

test('read-only console ignores an edit link in the URL', async ({ page }) => {
  await loadAdminApp(page, { path: `${base}&edit=sessions`, state: state({ myPermissions: readOnly }) });

  await expect(page.getByRole('heading', { name: 'Sign-in and security', level: 1 })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save sessions' })).toHaveCount(0);
});

test('without a tenant it asks for one', async ({ page }) => {
  await loadAdminApp(page, { path: '/?view=security', state: { tenants: [] } });
  await expect(page.getByRole('heading', { name: 'Select a tenant' })).toBeVisible();
});

test('switching the tenant reloads that tenant\'s settings', async ({ page }) => {
  await loadAdminApp(page, {
    path: base,
    state: state({
      tenants: [{ id: tenant, description: 'Alpha', edgeId: null, securityProfile: 'fapi2' }, { id: 'tenant-bravo', description: 'Bravo', edgeId: null, securityProfile: 'standard' }],
      challengeSettings: { [tenant]: settings, 'tenant-bravo': { ...settings, tenantId: 'tenant-bravo', securityProfile: 'standard', otpLength: 8 } } as MockConfigState['challengeSettings'],
    }),
  });

  await expect(card(page, 'One-time codes')).toContainText('6 digits');
  await page.goto('/?view=security&tenant=tenant-bravo');
  await expect(card(page, 'One-time codes')).toContainText('8 digits');
});
