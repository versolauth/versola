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
  await expect(card(page)).toContainText('com.example.app · 1 certificate');
  await expect(card(page).getByRole('button', { name: 'Edit' })).toBeVisible();
});

test('sets a policy: Report first, one iOS App ID and one Android app, saved as central keeps it', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');

  await card(page).getByRole('button', { name: 'Set a policy' }).click();
  // Opens on the mode that refuses nothing.
  await expect(card(page).getByRole('button', { name: 'Report', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await card(page).getByLabel('iOS App IDs').fill('ABCDE12345.com.example.app');
  await card(page).getByRole('button', { name: 'Add Android app' }).click();
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
  await expect(card(page)).toContainText('com.example.app · 1 certificate');
});

test('an Android build can be made to require a Play Integrity verdict, and says so on the card', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');

  await card(page).getByRole('button', { name: 'Set a policy' }).click();
  await card(page).getByRole('button', { name: 'Add Android app' }).click();
  await card(page).getByLabel('Package name').fill('com.example.app');
  await card(page).getByLabel('Signing certificate SHA-256').fill(DIGEST);
  await expect(card(page).getByLabel('Require a Play Integrity verdict')).not.toBeChecked();
  await card(page).getByLabel('Require a Play Integrity verdict').check();
  await card(page).getByRole('button', { name: 'Save attestation' }).click();

  const put = await findRequest(api.requests, 'PUT', '/configuration/clients/attestation');
  expect(put.body).toEqual({
    mode: 'report',
    android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST], playIntegrity: true }],
  });
  await expect(card(page)).toContainText('com.example.app · 1 certificate · Play Integrity');
});

test('says what central would refuse before it is asked, and sends nothing', async ({ page }) => {
  const api = await loadAdminApp(page, { path: clientsPath, state: { clients: { 'tenant-alpha': [edgeNativeClient] } } });
  await openClientPage(page, 'acme-mobile');
  await card(page).getByRole('button', { name: 'Set a policy' }).click();

  // Report with nothing to accept.
  await card(page).getByRole('button', { name: 'Save attestation' }).click();
  await expect(card(page).getByRole('alert')).toContainText('at least one iOS App ID or Android app');

  // A malformed App ID, and an Android row with no certificate.
  await card(page).getByLabel('iOS App IDs').fill('com.example.app');
  await card(page).getByRole('button', { name: 'Add Android app' }).click();
  await card(page).getByLabel('Package name').fill('com.example.app');
  await card(page).getByRole('button', { name: 'Save attestation' }).click();
  await expect(card(page).getByRole('alert').filter({ hasText: 'Team ID' })).toBeVisible();
  await expect(card(page).getByRole('alert').filter({ hasText: 'SHA-256 of the certificate' })).toBeVisible();

  expect(api.requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/clients/attestation')).toHaveLength(0);
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
