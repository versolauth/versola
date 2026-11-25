import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

/** The App attestation card of a client's page (#423): what an operator sets for a native client edge fronts. */

const clientsPath = '/?view=clients&tenant=tenant-alpha';
const DIGEST = 'ab'.repeat(32);

/** A native client edge fronts: `device` by kind, authenticating with a certificate. */
const edgeNativeClient = {
  id: 'acme-mobile',
  clientName: { en: 'Acme Mobile' },
  redirectUris: ['https://app.example.com/callback'],
  scope: ['openid'],
  permissions: [],
  secretRotation: false,
  accessTokenTtl: 3600,
  kind: 'device',
  authMethod: 'tls_client_auth',
  dpopBoundAccessTokens: true,
  certificateBoundAccessTokens: false,
  requirePushedAuthorizationRequests: true,
  requireSignedRequestObject: false,
  template: { kind: 'device' },
  createdAt: '2026-02-01T09:00:00Z',
};

const publicNativeClient = { ...edgeNativeClient, id: 'acme-public', clientName: { en: 'Acme Public' }, authMethod: 'none' };

const webClient = {
  id: 'acme-web',
  clientName: { en: 'Acme Web' },
  redirectUris: ['https://acme.example.com/callback'],
  scope: ['openid'],
  permissions: [],
  secretRotation: false,
  accessTokenTtl: 3600,
};

const reporting = {
  mode: 'report' as const,
  ios: { appIds: ['ABCDE12345.com.example.app'], environment: 'production' as const },
  android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST] }],
};

async function openClientPage(page: Page, id: string) {
  await page.getByRole('button', { name: new RegExp(`^Open client (.* )?${id}$`) }).click();
  await expect(page.locator('versola-clients-list .view-title')).toBeVisible();
}

const card = (page: Page) => page.getByRole('region', { name: 'App attestation' });

test('a native client edge fronts shows the card, off until a policy is set', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');

  await expect(card(page)).toBeVisible();
  await expect(card(page)).toContainText('Off');
  await expect(card(page)).toContainText('edge does not ask the app for anything');
  await expect(card(page).getByRole('button', { name: 'Set a policy' })).toBeVisible();
});

test('a client that edge does not front has no card: a web client, and a public native one', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [webClient, publicNativeClient] } } });

  await openClientPage(page, 'acme-web');
  await expect(card(page)).toHaveCount(0);

  await page.goto(clientsPath);
  await openClientPage(page, 'acme-public');
  await expect(card(page)).toHaveCount(0);
});

test('shows the policy central holds: the mode, the builds and what each does', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [edgeNativeClient] }, attestation: { 'acme-mobile': reporting } },
  });
  await openClientPage(page, 'acme-mobile');

  await expect(card(page)).toContainText('Report');
  await expect(card(page)).toContainText('never refuses');
  await expect(card(page)).toContainText('ABCDE12345.com.example.app · production');
  await expect(card(page)).toContainText('com.example.app');
  await expect(card(page).getByText('abababab…abababab')).toHaveAttribute('title', DIGEST);
  await expect(card(page).getByRole('button', { name: 'Edit' })).toBeVisible();
});

test('sets a policy: Report first, one iOS App ID and one Android app, saved as central keeps it', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');

  await card(page).getByRole('button', { name: 'Set a policy' }).click();
  // Opens on the mode that refuses nothing.
  await expect(card(page).getByRole('button', { name: 'Report', exact: true })).toHaveAttribute('aria-pressed', 'true');
  // Both platforms start off; each section appears when its platform is switched on, and Android starts with a row to fill.
  await expect(card(page).getByLabel('iOS App IDs')).toHaveCount(0);
  await expect(card(page).getByLabel('Package name')).toHaveCount(0);
  await card(page).getByLabel('iOS', { exact: true }).check();
  await card(page).getByLabel('iOS App IDs').fill('ABCDE12345.com.example.app');
  await card(page).getByLabel('Android', { exact: true }).check();
  await card(page).getByLabel('Package name').fill('com.example.app');
  // As `keytool` prints it: upper case, with colons.
  await card(page).getByLabel('Signing certificate SHA-256').fill(DIGEST.toUpperCase().match(/../g)!.join(':'));
  await card(page).getByRole('button', { name: 'Save attestation' }).click();

  const put = await findRequest(api.requests, 'PUT', '/configuration/clients/attestation');
  expect(put.searchParams).toEqual({ clientId: 'acme-mobile' });
  expect(put.body).toEqual({
    mode: 'report',
    ios: { appIds: ['ABCDE12345.com.example.app'], environment: 'production' },
    android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST.toUpperCase().match(/../g)!.join(':').replace(/:/g, '').toLowerCase()] }],
  });
  await expect(card(page).getByRole('button', { name: 'Save attestation' })).toHaveCount(0);
  await expect(card(page)).toContainText('Report');
  await expect(card(page)).toContainText('com.example.app');
});

test('an Android build can be made to require a Play Integrity verdict, and says so on the card', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');

  await card(page).getByRole('button', { name: 'Set a policy' }).click();
  await card(page).getByLabel('Android', { exact: true }).check();
  await card(page).getByLabel('Package name').fill('com.example.app');
  await card(page).getByLabel('Signing certificate SHA-256').fill(DIGEST);
  // Its label is a sentence, not one of the form's upper-case headings.
  await expect(card(page).locator('label.check-row').last()).toHaveCSS('text-transform', 'none');
  await expect(card(page).getByLabel('Require a Play Integrity verdict')).not.toBeChecked();
  await card(page).getByLabel('Require a Play Integrity verdict').check();
  await card(page).getByRole('button', { name: 'Save attestation' }).click();

  const put = await findRequest(api.requests, 'PUT', '/configuration/clients/attestation');
  expect(put.body).toEqual({
    mode: 'report',
    android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST], playIntegrity: true }],
  });
  await expect(card(page)).toContainText('Play Integrity');
});

test('says what central would refuse before it is asked, where the problem is, and sends nothing', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');
  await card(page).getByRole('button', { name: 'Set a policy' }).click();

  // Report with no platform: said next to the mode, not under a platform.
  await card(page).getByRole('button', { name: 'Save attestation' }).click();
  await expect(card(page).getByRole('alert')).toContainText('at least one platform');
  await expect(card(page).locator('.form-group', { hasText: 'Mode' }).getByRole('alert')).toContainText('at least one platform');

  // A malformed App ID, and an Android row with a bad package name and no certificate: each at its own field.
  await card(page).getByLabel('iOS', { exact: true }).check();
  await card(page).getByLabel('iOS App IDs').fill('com.example.app');
  await card(page).getByLabel('Android', { exact: true }).check();
  await card(page).getByLabel('Package name').fill('example');
  await card(page).getByRole('button', { name: 'Save attestation' }).click();
  await expect(card(page).getByRole('alert').filter({ hasText: 'Team ID' })).toBeVisible();
  await expect(card(page).getByLabel('Package name')).toHaveAttribute('aria-invalid', 'true');
  await expect(card(page).getByLabel('Signing certificate SHA-256')).toHaveAttribute('aria-invalid', 'true');
  await expect(card(page).getByRole('alert').filter({ hasText: 'is not a package name' })).toBeVisible();
  await expect(card(page).getByRole('alert').filter({ hasText: 'SHA-256 of the certificate' })).toBeVisible();

  // Fixing the name leaves the certificates' message alone, and the name's gone.
  await card(page).getByLabel('Package name').fill('com.example.app');
  await expect(card(page).getByLabel('Package name')).not.toHaveAttribute('aria-invalid', 'true');
  await expect(card(page).getByLabel('Signing certificate SHA-256')).toHaveAttribute('aria-invalid', 'true');

  expect(api.requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/clients/attestation')).toHaveLength(0);
});

test('Enforce is not sent until it is confirmed, and the confirmation says what it does', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');
  const puts = () => api.requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/clients/attestation');

  await card(page).getByRole('button', { name: 'Set a policy' }).click();
  await card(page).getByRole('button', { name: 'Enforce', exact: true }).click();
  await card(page).getByLabel('Android', { exact: true }).check();
  await card(page).getByLabel('Package name').fill('com.example.app');
  await card(page).getByLabel('Signing certificate SHA-256').fill(DIGEST);
  await card(page).getByLabel('Require a Play Integrity verdict').check();
  await card(page).getByRole('button', { name: 'Save attestation' }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('turn away every sign-in of acme-mobile');
  await expect(dialog).toContainText('apps that do not send one yet');
  // The client was never in Report, and one build needs Play Integrity: both are said.
  await expect(dialog).toContainText('has not been in Report');
  await expect(dialog).toContainText('Play Integrity');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  expect(puts()).toHaveLength(0);
  await expect(card(page).getByRole('button', { name: 'Save attestation' })).toBeVisible();

  await card(page).getByRole('button', { name: 'Save attestation' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Enforce' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/clients/attestation')).body).toMatchObject({ mode: 'enforce' });
});

test('a client that was in Report is not told it has not been, and Report itself is saved without asking', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [edgeNativeClient] }, attestation: { 'acme-mobile': reporting } },
  });
  await openClientPage(page, 'acme-mobile');

  await card(page).getByRole('button', { name: 'Edit' }).click();
  await card(page).getByRole('button', { name: 'Save attestation' }).click();
  await expect(card(page).getByRole('button', { name: 'Save attestation' })).toHaveCount(0);
  expect(await findRequest(api.requests, 'PUT', '/configuration/clients/attestation')).toBeTruthy();
  await expect(page.getByRole('dialog')).toHaveCount(0);

  await card(page).getByRole('button', { name: 'Edit' }).click();
  await card(page).getByRole('button', { name: 'Enforce', exact: true }).click();
  await card(page).getByRole('button', { name: 'Save attestation' }).click();
  await expect(page.getByRole('dialog')).toContainText('turn away every sign-in');
  await expect(page.getByRole('dialog')).not.toContainText('has not been in Report');
});

test('narrowing what is enforced asks too, since the apps that carry what is dropped are turned away', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [edgeNativeClient] }, attestation: { 'acme-mobile': { ...reporting, mode: 'enforce' as const } } },
  });
  await openClientPage(page, 'acme-mobile');
  const puts = () => api.requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/clients/attestation');

  await card(page).getByRole('button', { name: 'Edit' }).click();
  await card(page).getByLabel('iOS', { exact: true }).uncheck();
  await card(page).getByRole('button', { name: 'Save attestation' }).click();
  await expect(page.getByRole('dialog')).toContainText('already enforces');
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
  expect(puts()).toHaveLength(0);

  // Adding a build, with nothing dropped, is not asked about.
  await card(page).getByLabel('iOS', { exact: true }).check();
  await card(page).getByRole('button', { name: 'Add Android app' }).click();
  await card(page).getByLabel('Package name').last().fill('com.example.other');
  await card(page).getByLabel('Signing certificate SHA-256').last().fill(DIGEST);
  await card(page).getByRole('button', { name: 'Save attestation' }).click();
  expect(await findRequest(api.requests, 'PUT', '/configuration/clients/attestation')).toBeTruthy();
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('Android switched on with no app says so at the Android apps, and the page goes to it', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');
  await card(page).getByRole('button', { name: 'Set a policy' }).click();
  await card(page).getByLabel('Android', { exact: true }).check();
  await card(page).getByRole('button', { name: 'Remove Android app 1' }).click();
  await card(page).getByRole('button', { name: 'Save attestation' }).click();

  await expect(card(page).getByRole('alert').filter({ hasText: 'Add an Android app' })).toBeFocused();
});

test('the card shows the platforms that are set, and says nothing of the ones that are not', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [edgeNativeClient] },
      attestation: { 'acme-mobile': { mode: 'report', android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST] }] } },
    },
  });
  await openClientPage(page, 'acme-mobile');

  await expect(card(page)).toContainText('com.example.app');
  await expect(card(page)).not.toContainText('None accepted');
  await expect(card(page).locator('versola-record-row', { hasText: 'iOS' })).toHaveCount(0);
  await expect(card(page)).toContainText('Applies to sign-ins that start from now on');
});

test('the Report text says where the outcomes are counted', async ({ page }) => {
  await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');
  await card(page).getByRole('button', { name: 'Set a policy' }).click();

  await expect(card(page)).toContainText('attestation_requests_total');
});

test('Cancel leaves the policy as it was', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [edgeNativeClient] }, attestation: { 'acme-mobile': reporting } },
  });
  await openClientPage(page, 'acme-mobile');

  await card(page).getByRole('button', { name: 'Edit' }).click();
  await card(page).getByRole('button', { name: 'Enforce', exact: true }).click();
  await card(page).getByRole('button', { name: 'Cancel' }).click();

  await expect(card(page).locator('.chip')).toHaveText('Report');
});

test('removes the policy through the confirm dialog, and the client reads as off again', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: clientsPath,
    state: { clients: { 'tenant-alpha': [edgeNativeClient] }, attestation: { 'acme-mobile': reporting } },
  });
  await openClientPage(page, 'acme-mobile');

  await card(page).getByRole('button', { name: 'Edit' }).click();
  await card(page).getByRole('button', { name: 'Remove policy' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Remove policy' }).click();

  expect((await findRequest(api.requests, 'DELETE', '/configuration/clients/attestation')).searchParams).toEqual({ clientId: 'acme-mobile' });
  await expect(card(page)).toContainText('Off');
  await expect(card(page).getByRole('button', { name: 'Set a policy' })).toBeVisible();
});

test('the client list shows the mode of a policy that is on, and follows a change made on the client page', async ({ page }) => {
  await loadAdminApp(page, {
    path: clientsPath,
    state: {
      clients: { 'tenant-alpha': [edgeNativeClient, { ...edgeNativeClient, id: 'acme-other', clientName: { en: 'Acme Other' } }, webClient] },
      attestation: { 'acme-mobile': { ...reporting, mode: 'enforce' as const } },
    },
  });

  const row = (id: string) => page.getByRole('row').filter({ has: page.getByRole('button', { name: new RegExp(`${id}$`) }) });
  await expect(row('acme-mobile')).toContainText('Attestation: enforce');
  await expect(row('acme-other')).not.toContainText('Attestation');
  await expect(row('acme-web')).not.toContainText('Attestation');

  // A policy removed on the client's page leaves the list as it is shown when the page is closed.
  await openClientPage(page, 'acme-mobile');
  await expect(page.locator('versola-record-header')).toContainText('Attestation: enforce');
  await card(page).getByRole('button', { name: 'Edit' }).click();
  await card(page).getByRole('button', { name: 'Remove policy' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Remove policy' }).click();
  await expect(page.locator('versola-record-header')).not.toContainText('Attestation');
});
