import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const resourcesPath = '/?view=resources&tenant=tenant-alpha';
const emptyAcl = { kind: 'any', children: [] } as const;

const alphaApi = {
  id: 1,
  resource: 'https://alpha.example',
  endpoints: [
    { id: 101, method: 'GET', path: '/alpha/items', fetchUserInfo: false, allowRules: emptyAcl, denyRules: emptyAcl, injectHeaders: {} },
    { id: 102, method: 'POST', path: '/alpha/items', fetchUserInfo: false, allowRules: emptyAcl, denyRules: emptyAcl, injectHeaders: {} },
  ],
};

const reportsApi = {
  id: 2,
  resource: 'https://reports.example',
  endpoints: [{ id: 201, method: 'GET', path: '/reports', fetchUserInfo: false, allowRules: emptyAcl, denyRules: emptyAcl, injectHeaders: {} }],
};

function resourceCard(page: Page, text: string) {
  return page.locator('.resource-shell').filter({ hasText: text }).first();
}

test('renders resources with endpoints and filters by resource uri', async ({ page }) => {
  await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [alphaApi, reportsApi] } },
  });

  const alpha = resourceCard(page, 'alpha.example');
  await alpha.click();
  await expect(alpha).toContainText('/alpha/items');
  await expect(alpha).toContainText('POST');

  const search = page.getByLabel('Search resources');
  await search.fill('reports');
  await expect(resourceCard(page, 'reports.example')).toBeVisible();
  await expect(page.locator('.resource-shell').filter({ hasText: 'alpha.example' })).toHaveCount(0);

  await search.fill('missing-resource');
  await expect(page.getByRole('heading', { name: 'No resources match your search', exact: true })).toBeVisible();
});

test('creates a resource with endpoints', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [] } },
  });

  await expect(page.getByRole('heading', { name: 'No resources yet', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '+ Create Resource', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Create Resource', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Resources', exact: true })).toHaveCount(0);
  const resourceUriField = page.getByLabel('Absolute resource URI');
  await expect(resourceUriField).toHaveValue('https://');
  await resourceUriField.fill('https://service.example');
  await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  const newEndpointEditor = page.locator('.endpoint-editor').last();
  await newEndpointEditor.locator('select').first().selectOption('POST');
  await newEndpointEditor.getByPlaceholder('/users').fill('/service/items');
  await page.getByRole('button', { name: 'Create Resource', exact: true }).click();

  const created = resourceCard(page, 'service.example');
  await expect(created).toContainText('/service/items');
  await expect(created).toContainText('POST');

  const createRequest = findRequest(api.requests, 'POST', '/v1/configuration/resources').body as {
    tenantId: string;
    resource: string;
    endpoints: Array<{ id?: string; method: string; path: string; fetchUserInfo: boolean; allowRules: unknown; denyRules: unknown; injectHeaders: Record<string, string> }>;
  };

  expect(createRequest.tenantId).toBe('tenant-alpha');
  expect(createRequest.resource).toBe('https://service.example');
  expect(createRequest.endpoints).toHaveLength(1);
  expect(createRequest.endpoints[0]).toEqual({
    id: expect.any(String),
    method: 'POST',
    path: '/service/items',
    fetchUserInfo: false,
    allowRules: emptyAcl,
    denyRules: emptyAcl,
    injectHeaders: {},
  });
  expect(api.requests.filter(request => request.method === 'GET' && request.pathname === '/v1/configuration/resources')).toHaveLength(1);
});

test('shows resource validation errors before saving', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: '+ Create Resource', exact: true }).click();
  const resourceUriField = page.getByLabel('Absolute resource URI');
  await resourceUriField.fill('https://alpha.example/api');
  await expect(resourceUriField).toHaveClass(/input-error/);
  await expect(resourceUriField).toHaveCSS('border-top-color', 'rgb(248, 81, 73)');
  await page.getByRole('button', { name: 'Create Resource', exact: true }).click();
  await expect(page.getByText('Resource URI path must be empty', { exact: true })).toBeVisible();

  await page.getByLabel('Absolute resource URI').fill('https://alpha.example');
  await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  const relativePathField = page.locator('.endpoint-editor').last().getByPlaceholder('/users');
  await relativePathField.fill('users');
  await expect(relativePathField).toHaveClass(/input-error/);
  await expect(relativePathField).toHaveCSS('border-top-color', 'rgb(248, 81, 73)');
  await page.getByRole('button', { name: 'Create Resource', exact: true }).click();
  await expect(page.getByText('Endpoint path must start with “/”: GET users', { exact: true })).toBeVisible();

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/resources')).toBeFalsy();
});

test('updates a resource and saves batched endpoint changes in one request', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [alphaApi] } },
  });

  await resourceCard(page, 'alpha.example').getByRole('button', { name: 'Edit resource alpha.example' }).click();
  await page.getByLabel('Absolute resource URI').fill('https://alpha-v2.example');
  await page.getByRole('button', { name: 'Remove endpoint GET /alpha/items', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Remove endpoint', exact: true }).click();
  await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  const newEndpointEditor = page.locator('.endpoint-editor').last();
  await newEndpointEditor.locator('select').first().selectOption('DELETE');
  await newEndpointEditor.getByPlaceholder('/users').fill('/alpha/items/:id');
  await page.getByRole('button', { name: 'Save Resource', exact: true }).click();

  const updated = resourceCard(page, 'alpha-v2.example');
  await expect(updated).toContainText('/alpha/items');
  await expect(updated).toContainText('/alpha/items/:id');
  await expect(updated).toContainText('DELETE');

  expect(findRequest(api.requests, 'PUT', '/v1/configuration/resources').body).toEqual({
    id: 1,
    resource: 'https://alpha-v2.example',
    deleteEndpoints: [101],
    createEndpoints: [
      { id: 102, method: 'POST', path: '/alpha/items', fetchUserInfo: false, allowRules: emptyAcl, denyRules: emptyAcl, injectHeaders: {} },
      { id: expect.any(String), method: 'DELETE', path: '/alpha/items/:id', fetchUserInfo: false, allowRules: emptyAcl, denyRules: emptyAcl, injectHeaders: {} },
    ],
  });
  expect(api.requests.filter(request => request.method === 'GET' && request.pathname === '/v1/configuration/resources')).toHaveLength(1);
});

test('deletes a resource through the shared confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [alphaApi] } },
  });

  await resourceCard(page, 'alpha.example').getByRole('button', { name: 'Delete resource alpha.example' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect(findRequest(api.requests, 'DELETE', '/v1/configuration/resources').searchParams).toEqual({ id: '1' });
  await expect(page.locator('.resource-shell')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No resources yet', exact: true })).toBeVisible();
});