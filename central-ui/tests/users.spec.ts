import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const usersPath = '/?view=users&tenant=tenant-alpha';

const alice = {
  id: '00000000-0000-0000-0000-000000000001',
  email: 'alice@example.com',
  phone: '+14155551234',
  login: 'alice',
  claims: { name: 'Alice Doe', custom_flag: true },
  rolesByTenant: { 'tenant-alpha': ['alpha_admin'], 'tenant-bravo': ['bravo_reader'] },
};

const bob = {
  id: '00000000-0000-0000-0000-000000000002',
  email: 'bob@example.com',
  login: 'bob',
  claims: {},
  rolesByTenant: {},
};

const alicePasskey = {
  id: 'cred-abc',
  name: 'Alice MacBook',
  deviceType: 'multiDevice',
  transports: ['internal'],
  backedUp: true,
  backupEligible: true,
  lastUsedAt: '2024-01-15T10:00:00.000Z',
  createdAt: '2024-01-01T00:00:00.000Z',
};

const aliceWithPasskey = { ...alice, passkeys: [alicePasskey] };

async function searchAlice(page: Page) {
  await page.getByPlaceholder('Search users…').fill('alice');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(userCard(page, 'Alice Doe')).toBeVisible();
}

const profileScope = {
  scope: 'profile',
  description: { en: 'Profile' },
  claims: [
    { claim: 'name', description: { en: 'Full name' } },
    { claim: 'nickname', description: { en: 'Nickname' } },
  ],
};
const emailScope = {
  scope: 'email',
  description: { en: 'Email' },
  claims: [{ claim: 'email', description: { en: 'Email address' } }],
};
const openidScope = {
  scope: 'openid',
  description: { en: 'OpenID' },
  claims: [{ claim: 'sub', description: { en: 'Subject' } }],
};

const alphaAdmin = { id: 'alpha_admin', description: { en: 'Alpha admin' }, permissions: ['alpha.read'], active: true };
const alphaReader = { id: 'alpha_reader', description: { en: 'Alpha reader' }, permissions: [], active: true };

function userCard(page: Page, text: string) {
  return page.locator('.user-card').filter({ hasText: text }).first();
}

test('shows empty state, then searches and renders matching user', async ({ page }) => {
  await loadAdminApp(page, { path: usersPath, state: { users: [alice] } });

  await expect(page.getByRole('heading', { name: 'Search for a user', exact: true })).toBeVisible();
  await page.getByPlaceholder('Search users…').fill('alice');
  await page.getByRole('button', { name: 'Search', exact: true }).click();

  const card = userCard(page, 'Alice Doe');
  await expect(card).toContainText(alice.id);
  await expect(card).toContainText('alice@example.com');
  await expect(card).toContainText(alice.phone);
});

test('shows "No users found" when search returns nothing', async ({ page }) => {
  await loadAdminApp(page, { path: usersPath, state: { users: [] } });

  await page.getByPlaceholder('Search users…').fill('missing');
  await page.getByRole('button', { name: 'Search', exact: true }).click();

  await expect(page.getByRole('heading', { name: 'No users found', exact: true })).toBeVisible();
});

test('detects search field from query format', async ({ page }) => {
  await loadAdminApp(page, { path: usersPath, state: { users: [alice] } });

  const input = page.getByPlaceholder('Search users…');
  await input.fill(alice.email);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(userCard(page, 'Alice Doe')).toBeVisible();

  await input.fill(alice.phone);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(userCard(page, 'Alice Doe')).toBeVisible();
});

test('creates a new user via the create form', async ({ page }) => {
  const api = await loadAdminApp(page, { path: usersPath, state: { users: [] } });

  await page.getByRole('button', { name: '+ Create User', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create New User', exact: true })).toBeVisible();
  await page.getByLabel('Email').fill('new@example.com');
  await page.getByLabel('Login').fill('newbie');
  await page.getByRole('button', { name: 'Create User', exact: true }).click();

  await expect(page.getByPlaceholder('Search users…')).toBeVisible();
  expect(findRequest(api.requests, 'POST', '/users').body).toEqual({
    email: 'new@example.com',
    phone: undefined,
    login: 'newbie',
  });
});

test('edit user form shows only basic fields without roles section', async ({ page }) => {
  await loadAdminApp(page, { path: usersPath, state: { users: [alice], roles: { 'tenant-alpha': [alphaAdmin] } } });

  await page.getByPlaceholder('Search users…').fill('alice');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await userCard(page, 'Alice Doe').locator('.user-actions .icon-action[title="Edit"]').click();

  await expect(page.getByRole('heading', { name: 'Edit User', exact: true })).toBeVisible();
  await expect(page.getByLabel('Email')).toHaveValue('alice@example.com');
  await expect(page.getByLabel('Login')).toHaveValue('alice');
  await expect(page.getByRole('button', { name: 'Add', exact: true })).toHaveCount(0);
});

test('edits user basic fields and sends PATCH', async ({ page }) => {
  const api = await loadAdminApp(page, { path: usersPath, state: { users: [alice] } });

  await page.getByPlaceholder('Search users…').fill('alice');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await userCard(page, 'Alice Doe').locator('.user-actions .icon-action[title="Edit"]').click();
  await expect(page.getByLabel('Login')).toHaveValue('alice');
  await page.getByLabel('Login').fill('alice-updated');
  await page.getByRole('button', { name: 'Update User', exact: true }).click();
  await page.waitForTimeout(500);

  expect(findRequest(api.requests, 'PATCH', '/users').body).toEqual({
    id: alice.id,
    login: 'alice-updated',
  });
});

test('Get Claims expands the claims section with all user claims', async ({ page }) => {
  await loadAdminApp(page, { path: usersPath, state: { users: [alice] } });
  await searchAlice(page);

  const card = userCard(page, 'Alice Doe');
  await card.getByRole('button', { name: 'Get Claims', exact: true }).click();

  await expect(card.locator('.expand-section-title', { hasText: 'Claims' })).toBeVisible();
  await expect(card.locator('.prop-label', { hasText: 'name' })).toBeVisible();
  await expect(card.locator('.claim-value', { hasText: 'Alice Doe' })).toBeVisible();
  await expect(card.locator('.prop-label', { hasText: 'custom_flag' })).toBeVisible();
  await expect(card.locator('.claim-value.bool-true', { hasText: 'true' })).toBeVisible();
});

test('Get Roles fetches and displays user roles for the selected tenant', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: usersPath,
    state: { users: [alice], roles: { 'tenant-alpha': [alphaAdmin, alphaReader] } },
  });
  await searchAlice(page);

  const card = userCard(page, 'Alice Doe');
  await card.getByRole('button', { name: 'Get Roles', exact: true }).click();
  await page.waitForTimeout(300);

  const req = findRequest(api.requests, 'GET', '/users/roles');
  expect(req.searchParams['id']).toBe(alice.id);
  expect(req.searchParams['tenantId']).toBe('tenant-alpha');
  await expect(card.locator('.role-tag', { hasText: 'alpha_admin' })).toBeVisible();
});

test('Reset Limits sends POST and updates button label', async ({ page }) => {
  const api = await loadAdminApp(page, { path: usersPath, state: { users: [alice] } });
  await searchAlice(page);

  const card = userCard(page, 'Alice Doe');
  await card.getByRole('button', { name: 'Reset Limits', exact: true }).click();
  await page.waitForTimeout(300);

  expect(findRequest(api.requests, 'POST', '/users/limits/reset').body).toMatchObject({
    userId: alice.id,
  });
  await expect(card.getByRole('button', { name: 'Limits Reset ✓', exact: true })).toBeVisible();
});

test('Get Passkeys fetches and lists passkeys for the user', async ({ page }) => {
  const api = await loadAdminApp(page, { path: usersPath, state: { users: [aliceWithPasskey] } });
  await searchAlice(page);

  const card = userCard(page, 'Alice Doe');
  await card.getByRole('button', { name: 'Get Passkeys', exact: true }).click();
  await page.waitForTimeout(300);

  const req = findRequest(api.requests, 'GET', '/users/passkeys');
  expect(req.searchParams['id']).toBe(alice.id);

  await expect(card.locator('.passkey-card-name', { hasText: 'Alice MacBook' })).toBeVisible();
  await expect(card.locator('.passkey-prop-value', { hasText: 'cred-abc' })).toBeVisible();
  await expect(card.locator('.passkey-prop-value', { hasText: 'multiDevice' })).toBeVisible();
  await expect(card.locator('.passkey-badge.synced', { hasText: 'synced' })).toBeVisible();
});

test('renames a passkey via the prompt and sends PATCH', async ({ page }) => {
  const api = await loadAdminApp(page, { path: usersPath, state: { users: [aliceWithPasskey] } });
  await searchAlice(page);

  const card = userCard(page, 'Alice Doe');
  await card.getByRole('button', { name: 'Get Passkeys', exact: true }).click();
  await page.waitForTimeout(300);

  page.on('dialog', async dialog => {
    await dialog.accept('My Renamed Key');
  });
  await card.locator('.passkey-card-actions button[title="Rename"]').click();
  await page.waitForTimeout(300);

  expect(findRequest(api.requests, 'PATCH', '/users/passkeys').body).toMatchObject({
    userId: alice.id,
    credentialId: alicePasskey.id,
    name: 'My Renamed Key',
  });
  await expect(card.locator('.passkey-card-name', { hasText: 'My Renamed Key' })).toBeVisible();
});

test('deletes a passkey via the confirm dialog and removes it from the list', async ({ page }) => {
  const api = await loadAdminApp(page, { path: usersPath, state: { users: [aliceWithPasskey] } });
  await searchAlice(page);

  const card = userCard(page, 'Alice Doe');
  await card.getByRole('button', { name: 'Get Passkeys', exact: true }).click();
  await page.waitForTimeout(300);

  await card.locator('.passkey-card-actions button[title="Delete"]').click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();
  await page.waitForTimeout(300);

  const req = findRequest(api.requests, 'DELETE', '/users/passkeys');
  expect(req.searchParams['id']).toBe(alice.id);
  expect(req.searchParams['credentialId']).toBe(alicePasskey.id);
  await expect(card.locator('.passkey-card-name', { hasText: 'Alice MacBook' })).toHaveCount(0);
  await expect(card.locator('.no-data', { hasText: 'No passkeys registered.' })).toBeVisible();
});
