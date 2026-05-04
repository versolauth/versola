import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const scopesPath = '/?view=scopes&tenant=tenant-alpha';

const openidScope = {
  scope: 'openid',
  description: { en: 'OpenID scope' },
  claims: [{ claim: 'sub', description: { en: 'Subject' } }, { claim: 'email', description: { en: 'Email address' } }],
};

const profileScope = {
  scope: 'profile',
  description: { en: 'Profile data' },
  claims: [{ claim: 'name', description: { en: 'Display name' } }],
};

function scopeCard(page: Page, text: string) {
  return page.locator('.scope-card').filter({ hasText: text }).first();
}

test('renders scope details and filters by scope id', async ({ page }) => {
  await loadAdminApp(page, {
    path: scopesPath,
    state: { scopes: { 'tenant-alpha': [openidScope, profileScope] } },
  });

  const openid = scopeCard(page, 'openid');
  await openid.locator('.scope-header').click();
  await expect(openid).toContainText('Subject');
  await expect(openid).toContainText('email');

  const search = page.getByLabel('Search scopes');
  await search.fill('profile');
  await expect(scopeCard(page, 'profile')).toBeVisible();
  await expect(page.locator('.scope-card').filter({ hasText: 'openid' })).toHaveCount(0);

  await search.fill('missing-scope');
  await expect(page.getByRole('heading', { name: 'No scopes match your search', exact: true })).toBeVisible();
});

test('creates a scope with claims and shows it in the list', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: scopesPath,
    state: { scopes: { 'tenant-alpha': [] } },
  });

  await expect(page.getByRole('heading', { name: 'No OAuth scopes yet', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();

  await page.getByLabel('Scope ID').fill('profile');
  await page.locator('#scope-description').fill('Profile data');
  await page.getByLabel('Claim ID').fill('name');
  await page.getByLabel('Claim Description').fill('Display name');
  await page.getByRole('button', { name: 'Add Claim', exact: true }).click();
  await page.getByRole('button', { name: 'Create Scope', exact: true }).click();

  const created = scopeCard(page, 'profile');
  await expect(created).toContainText('Profile data');
  await expect(created.locator('.claim-name')).toContainText('name');
  await expect(created.locator('.claim-description')).toContainText('Display name');

  expect(findRequest(api.requests, 'POST', '/v1/configuration/scopes').body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'profile',
    description: { en: 'Profile data' },
    claims: [{ id: 'name', description: { en: 'Display name' } }],
  });
});

test('pressing Enter in claim fields adds the claim instead of submitting the scope form', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: scopesPath,
    state: { scopes: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();
  await page.getByLabel('Scope ID').fill('profile');
  await page.locator('#scope-description').fill('Profile data');
  await page.getByLabel('Claim ID').fill('name');
  await page.getByLabel('Claim Description').fill('Display name');
  await page.getByLabel('Claim ID').press('Enter');

  await expect(page.locator('.claim-name')).toContainText('name');
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/scopes')).toBeFalsy();

  await page.getByRole('button', { name: 'Create Scope', exact: true }).click();

  expect(findRequest(api.requests, 'POST', '/v1/configuration/scopes').body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'profile',
    description: { en: 'Profile data' },
    claims: [{ id: 'name', description: { en: 'Display name' } }],
  });
});

test('shows scope validation before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: scopesPath,
    state: { scopes: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: '+ Create Scope', exact: true }).click();
  const scopeIdField = page.getByLabel('Scope ID');
  await scopeIdField.fill('BadScope');
  await page.locator('#scope-description').fill('Broken scope');
  await expect(scopeIdField).toHaveClass(/input-error/);
  await expect(scopeIdField).toHaveCSS('border-top-color', 'rgb(248, 81, 73)');
  await page.getByRole('button', { name: 'Create Scope', exact: true }).click();

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/scopes')).toBeFalsy();
});

test('updates a scope and sends patch-style claim changes', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: scopesPath,
    state: { scopes: { 'tenant-alpha': [openidScope] } },
  });

  await scopeCard(page, 'openid').getByRole('button', { name: 'Edit scope openid' }).click();
  await page.locator('#scope-description').fill('OpenID claims');
  await page.getByRole('button', { name: 'Remove claim sub', exact: true }).click();
  await page.getByLabel('Claim ID').fill('sub');
  await page.getByLabel('Claim Description').fill('Subject identifier');
  await page.getByRole('button', { name: 'Add Claim', exact: true }).click();
  await page.getByRole('button', { name: 'Remove claim email', exact: true }).click();
  await page.getByLabel('Claim ID').fill('name');
  await page.getByLabel('Claim Description').fill('Display name');
  await page.getByRole('button', { name: 'Add Claim', exact: true }).click();
  await page.getByRole('button', { name: 'Update Scope', exact: true }).click();

  const updated = scopeCard(page, 'openid');
  await expect(updated).toContainText('OpenID claims');
  await updated.locator('.scope-header').click();
  await expect(updated).toContainText('Subject identifier');
  await expect(updated).toContainText('name');
  await expect(updated).not.toContainText('Email address');

  expect(findRequest(api.requests, 'PUT', '/v1/configuration/scopes').body).toEqual({
    tenantId: 'tenant-alpha',
    id: 'openid',
    patch: {
      add: [{ id: 'name', description: { en: 'Display name' } }],
      update: [{ id: 'sub', description: { add: { en: 'Subject identifier' }, delete: [] } }],
      delete: ['email'],
      description: { add: { en: 'OpenID claims' }, delete: [] },
    },
  });
});

test('deletes a scope through the confirm dialog and reaches the empty state', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: scopesPath,
    state: { scopes: { 'tenant-alpha': [openidScope] } },
  });

  await scopeCard(page, 'openid').getByRole('button', { name: 'Delete scope openid' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect(findRequest(api.requests, 'DELETE', '/v1/configuration/scopes').searchParams).toEqual({
    tenantId: 'tenant-alpha',
    scopeId: 'openid',
  });
  await expect(page.locator('.scope-card')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No OAuth scopes yet', exact: true })).toBeVisible();
});