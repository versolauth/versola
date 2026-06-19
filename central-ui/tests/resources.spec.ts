import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const resourcesPath = '/?view=resources&tenant=tenant-alpha';

const alphaApi = {
  id: 1,
  alias: 'alpha',
  resource: 'https://alpha.example',
  endpoints: [
    { id: 101, method: 'GET', path: '/alpha/items', fetchUserInfo: false, allow: 'true', inject: [] },
    { id: 102, method: 'POST', path: '/alpha/items', fetchUserInfo: false, allow: 'true', inject: [] },
  ],
};

const reportsApi = {
  id: 2,
  alias: 'reports',
  resource: 'https://reports.example',
  endpoints: [{ id: 201, method: 'GET', path: '/reports', fetchUserInfo: false, allow: 'true', inject: [] }],
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
  await page.getByRole('textbox', { name: 'Resource alias' }).fill('service');
  await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  const newEndpointEditor = page.locator('.endpoint-editor').last();
  await newEndpointEditor.locator('select').first().selectOption('POST');
  await newEndpointEditor.getByPlaceholder('/users').fill('/service/items');
  await page.getByRole('button', { name: 'Create Resource', exact: true }).click();

  const created = resourceCard(page, 'service.example');
  await expect(created).toContainText('/service/items');
  await expect(created).toContainText('POST');

  const createRequest = findRequest(api.requests, 'POST', '/configuration/resources').body as {
    tenantId: string;
    alias: string;
    resource: string;
    endpoints: Array<{ id?: string; method: string; path: string; fetchUserInfo: boolean; allow: string | null; inject: unknown[] }>;
  };

  expect(createRequest.tenantId).toBe('tenant-alpha');
  expect(createRequest.alias).toBe('service');
  expect(createRequest.resource).toBe('https://service.example');
  expect(createRequest.endpoints).toHaveLength(1);
  expect(createRequest.endpoints[0]).toEqual({
    id: expect.any(String),
    method: 'POST',
    path: '/service/items',
    fetchUserInfo: false,
    allow: null,
    inject: [],
  });
  expect(api.requests.filter(request => request.method === 'GET' && request.pathname === '/configuration/resources')).toHaveLength(1);
});

test('shows resource validation errors before saving', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [] } },
  });

  await page.getByRole('button', { name: '+ Create Resource', exact: true }).click();
  await page.getByRole('textbox', { name: 'Resource alias' }).fill('alpha');
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
  await expect(page.getByText('Endpoint path must start with “/” and contain no “*”, “:” or “{}”: GET users', { exact: true })).toBeVisible();

  await relativePathField.fill('/users/{id}');
  await page.getByRole('button', { name: 'Create Resource', exact: true }).click();
  await expect(page.getByText('Endpoint path must start with “/” and contain no “*”, “:” or “{}”: GET /users/{id}', { exact: true })).toBeVisible();

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/resources')).toBeFalsy();
});

test('updates a resource and saves batched endpoint changes in one request', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [alphaApi] } },
  });

  await resourceCard(page, 'alpha.example').getByRole('button', { name: 'Edit resource alpha' }).click();
  await page.getByLabel('Absolute resource URI').fill('https://alpha-v2.example');
  await page.getByRole('button', { name: 'Remove endpoint GET /alpha/items', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Remove endpoint', exact: true }).click();
  await page.getByRole('button', { name: 'Add endpoint', exact: true }).click();
  const newEndpointEditor = page.locator('.endpoint-editor').last();
  await newEndpointEditor.locator('select').first().selectOption('DELETE');
  await newEndpointEditor.getByPlaceholder('/users').fill('/alpha/items/archive');
  await page.getByRole('button', { name: 'Save Resource', exact: true }).click();

  const updated = resourceCard(page, 'alpha-v2.example');
  await expect(updated).toContainText('/alpha/items');
  await expect(updated).toContainText('/alpha/items/archive');
  await expect(updated).toContainText('DELETE');

  expect(findRequest(api.requests, 'PUT', '/configuration/resources').body).toEqual({
    id: 1,
    alias: 'alpha',
    resource: 'https://alpha-v2.example',
    deleteEndpoints: [101],
    createEndpoints: [
      { id: 102, method: 'POST', path: '/alpha/items', fetchUserInfo: false, allow: 'true', inject: [] },
      { id: expect.any(String), method: 'DELETE', path: '/alpha/items/archive', fetchUserInfo: false, allow: null, inject: [] },
    ],
  });
  expect(api.requests.filter(request => request.method === 'GET' && request.pathname === '/configuration/resources')).toHaveLength(1);
});

test('blocks save when allow expression is invalid CEL and clears error after fix', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [alphaApi] } },
  });

  await resourceCard(page, 'alpha.example').getByRole('button', { name: 'Edit resource alpha' }).click();
  await page.locator('.endpoint-editor').filter({ hasText: '/alpha/items' }).first().click();
  const allowEditor = page.locator('versola-cel-editor').first();
  const allowTextarea = allowEditor.getByRole('textbox', { name: 'Allow expression' });
  await allowTextarea.fill('token.role ==');

  await expect(allowEditor).toHaveAttribute('has-error', '');
  await expect(allowEditor.getByRole('alert')).toBeVisible();

  await page.getByRole('button', { name: 'Save Resource', exact: true }).click();
  await expect(page.getByText(/^Invalid allow expression in GET \/alpha\/items: /)).toBeVisible();
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === '/configuration/resources')).toBeFalsy();

  await allowTextarea.fill("token.role == 'admin'");
  await expect(allowEditor).not.toHaveAttribute('has-error', '');
  await expect(allowEditor.getByRole('alert')).toHaveCount(0);

  await page.getByRole('button', { name: 'Save Resource', exact: true }).click();
  expect(findRequest(api.requests, 'PUT', '/configuration/resources').body).toMatchObject({
    createEndpoints: expect.arrayContaining([
      expect.objectContaining({ id: 101, allow: "token.role == 'admin'" }),
    ]),
  });
});

test('blocks save when inject expression is empty or invalid CEL', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [alphaApi] } },
  });

  await resourceCard(page, 'alpha.example').getByRole('button', { name: 'Edit resource alpha' }).click();
  const editor = page.locator('.endpoint-editor').filter({ hasText: '/alpha/items' }).first();
  await editor.click();
  await editor.getByRole('button', { name: 'Add rule', exact: true }).click();

  await page.getByRole('button', { name: 'Save Resource', exact: true }).click();
  await expect(page.getByText(/^Inject expression is required in GET \/alpha\/items/)).toBeVisible();

  const exprEditor = editor.locator('versola-cel-editor').nth(1);
  await exprEditor.getByRole('textbox', { name: 'Inject expression' }).fill('(unterminated');
  await page.getByRole('button', { name: 'Save Resource', exact: true }).click();
  await expect(page.getByText(/^Invalid inject expression in GET \/alpha\/items/)).toBeVisible();

  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === '/configuration/resources')).toBeFalsy();
});

test('Allow (CEL) info tooltip lists root variables, operators, and macros', async ({ page }) => {
  await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [alphaApi] } },
  });

  await resourceCard(page, 'alpha.example').getByRole('button', { name: 'Edit resource alpha' }).click();
  const editor = page.locator('.endpoint-editor').filter({ hasText: '/alpha/items' }).first();
  await editor.click();
  await editor.getByRole('button', { name: 'Allow expression info' }).click();

  const tooltip = editor.getByRole('tooltip').first();
  await expect(tooltip).toBeVisible();
  await expect(tooltip).toContainText('Root variables');
  await expect(tooltip).toContainText('token');
  await expect(tooltip).toContainText('user');
  await expect(tooltip).toContainText('request');
  await expect(tooltip).toContainText('Operators');
  await expect(tooltip).toContainText('Macros');
  await expect(tooltip).toContainText('has(x.y)');
});

test('Fetch userinfo row in edit mode does not render an info button', async ({ page }) => {
  await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [alphaApi] } },
  });

  await resourceCard(page, 'alpha.example').getByRole('button', { name: 'Edit resource alpha' }).click();
  const editor = page.locator('.endpoint-editor').filter({ hasText: '/alpha/items' }).first();
  await editor.click();

  await expect(editor.getByText('Fetch userinfo', { exact: true })).toBeVisible();
  await expect(editor.getByRole('button', { name: 'Fetch userinfo info' })).toHaveCount(0);
});

test('deletes a resource through the shared confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: resourcesPath,
    state: { resources: { 'tenant-alpha': [alphaApi] } },
  });

  await resourceCard(page, 'alpha.example').getByRole('button', { name: 'Delete resource alpha' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect(findRequest(api.requests, 'DELETE', '/configuration/resources').searchParams).toEqual({ id: '1' });
  await expect(page.locator('.resource-shell')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'No resources yet', exact: true })).toBeVisible();
});