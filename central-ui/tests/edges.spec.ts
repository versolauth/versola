import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';

const edgesPath = '/?view=edges';

const alphaEdge = {
  id: 'edge-alpha',
};

const bravoEdge = {
  id: 'edge-bravo',
};

function edgeCard(page: Page, edgeId: string) {
  return page.locator('.edge-card').filter({ hasText: edgeId }).first();
}

test('renders edges as expandable cards', async ({ page }) => {
  await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [alphaEdge, bravoEdge] },
  });

  const alpha = edgeCard(page, 'edge-alpha');
  await expect(alpha).toContainText('edge-alpha');
  await expect(alpha.getByRole('button', { name: 'Delete edge edge-alpha' })).toBeVisible();

  const bravo = edgeCard(page, 'edge-bravo');
  await expect(bravo).toContainText('edge-bravo');
});

test('creates a new edge and shows the generated private key banner', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [] },
  });

  // Click on + Create Edge button from empty state
  await page.getByRole('button', { name: '+ Create Edge' }).click();
  await page.getByLabel('Edge ID').fill('new');
  await page.getByRole('button', { name: 'Create Edge', exact: true }).click();

  await expect(page.locator('.secret-banner')).toContainText('Edge Key Generated');
  await expect(page.getByRole('button', { name: 'Copy Private Key' })).toBeVisible();
  await expect(page.locator('.secret-value').first()).toBeVisible();

  // Dismiss the secret banner to return to list view
  await page.getByRole('button', { name: "I've Saved It" }).click();

  const created = edgeCard(page, 'edge-new');
  await expect(created).toContainText('edge-new');

  expect(findRequest(api.requests, 'POST', '/v1/configuration/edges').body).toEqual({ id: 'edge-new' });
});

test('shows edge form validation for invalid edge ID', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [alphaEdge] }, // Have at least one edge so header button shows
  });

  await page.getByRole('button', { name: '+ Create Edge' }).click();
  const edgeIdField = page.getByLabel('Edge ID');

  // Test invalid characters
  await edgeIdField.fill('Bad-ID');
  await expect(page.getByText('Invalid edge ID format')).toBeVisible();

  const submitButton = page.getByRole('button', { name: 'Create Edge', exact: true });
  await expect(submitButton).toBeDisabled();

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/edges')).toBeFalsy();
});

test('rejects duplicate edge ID when creating', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [alphaEdge] },
  });

  await page.getByRole('button', { name: '+ Create Edge' }).click();
  const edgeIdField = page.getByLabel('Edge ID');
  await edgeIdField.fill('alpha');
  await expect(page.getByText('Edge ID already exists')).toBeVisible();

  const submitButton = page.getByRole('button', { name: 'Create Edge', exact: true });
  await expect(submitButton).toBeDisabled();

  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/v1/configuration/edges')).toBeFalsy();
});

test('expands edge card and shows linked tenants section', async ({ page }) => {
  await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [alphaEdge] },
  });

  const card = edgeCard(page, 'edge-alpha');

  // Initially the linked tenants section should not be visible
  await expect(card.getByText('Linked Tenants')).not.toBeVisible();

  // Click on the card header to expand
  await card.locator('.edge-header').click();

  // Should show linked tenants section
  await expect(card.getByText('Linked Tenants')).toBeVisible();
});

test('rotates edge key and shows the new private key banner', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [alphaEdge] },
  });

  const card = edgeCard(page, 'edge-alpha');

  // Click edit button to open the form
  await card.getByRole('button', { name: 'Edit edge edge-alpha' }).click();

  // Click rotate key button in the form
  await page.getByRole('button', { name: 'Rotate Key', exact: true }).click();

  // Wait for and click the confirm button in the custom dialog (it's in a shadow DOM)
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Rotate Key', exact: true }).click();

  // Wait for the API request
  await page.waitForResponse(resp =>
    resp.url().includes('/v1/configuration/edges/rotate-key') && resp.status() === 200
  );

  // Should see the key banner after rotation
  await expect(page.locator('.secret-banner')).toContainText('Edge Key Rotated');
  await expect(page.locator('.secret-value').first()).toBeVisible();

  expect(findRequest(api.requests, 'POST', '/v1/configuration/edges/rotate-key').searchParams).toEqual({ edgeId: 'edge-alpha' });
});

test('deletes an edge through the confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [alphaEdge, bravoEdge] },
  });

  await edgeCard(page, 'edge-bravo').getByRole('button', { name: 'Delete edge edge-bravo' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  expect(findRequest(api.requests, 'DELETE', '/v1/configuration/edges').searchParams).toEqual({ edgeId: 'edge-bravo' });
  await expect(page.locator('.edge-card').filter({ hasText: 'edge-bravo' })).toHaveCount(0);
  await expect(edgeCard(page, 'edge-alpha')).toBeVisible();
});

test('shows empty state when no edges are created', async ({ page }) => {
  await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [] },
  });

  await expect(page.getByText('No edges yet')).toBeVisible();
  await expect(page.getByText('Create your first edge to get started')).toBeVisible();
  await expect(page.getByRole('button', { name: '+ Create Edge' })).toBeVisible();
});

test('shows empty state after deleting the last edge', async ({ page }) => {
  const api = await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [bravoEdge] },
  });

  await edgeCard(page, 'edge-bravo').getByRole('button', { name: 'Delete edge edge-bravo' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();

  await expect(page.locator('.edge-card')).toHaveCount(0);
  await expect(page.getByText('No edges yet')).toBeVisible();
});

test('closes edge form when cancel button is clicked', async ({ page }) => {
  await loadAdminApp(page, {
    path: edgesPath,
    state: { edges: [bravoEdge] },
  });

  await page.getByRole('button', { name: '+ Create Edge' }).click();
  await expect(page.locator('versola-edge-form')).toBeVisible();

  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.locator('versola-edge-form')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Edges' })).toBeVisible();
});