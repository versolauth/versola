import { expect, test } from '@playwright/test';
import { expectSheetWidth, findRequest } from '../fixtures';
import { scan } from './axe';
import { loadPartnerApp, sidePanel, sidePanelDialog } from './fixtures';
import { PARTNER_NOW, asLevel, partnerSeedState, suspendedPartner } from './partner-fixtures';

/**
 * Edges, the partner's deployments in its group: the table with the certificates, Add edge up to
 * the cap ending on the token and the bundle shown once, the record with the operator's policies
 * read-only, revoke an unused token, remove an edge, and the suspended state.
 */
const edgesPath = '/partner.html?view=edges';

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('lists the edges with their certificates; at the cap Add edge is disabled', async ({ page }) => {
  await loadPartnerApp(page, { path: edgesPath });
  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Edge', 'Public URL', 'Status', 'Certificate', 'Replicas', 'Actions']);
  const eu1 = table.getByRole('row').filter({ hasText: 'edge-pilot-eu-1' });
  await expect(eu1).toContainText('Enrolled');
  await expect(eu1).toContainText('valid to Nov 17, 2026');
  await expect(eu1).toContainText('renewed Nov 3, 2026');
  const kz = table.getByRole('row').filter({ hasText: 'edge-pilot-kz-1' });
  await expect(kz).toContainText('Waiting for enrollment');
  await expect(kz).toContainText('not enrolled · token expires in 1 hr');
  await expect(page.getByText('Certificates renew by themselves')).toBeVisible();
  await expect(page.getByText('You have the 3 edges the operator allows.')).toBeVisible();
  const add = page.getByRole('button', { name: '+ Add edge' });
  await expect(add).toBeDisabled();
  await expect(add).toHaveAttribute('title', 'The operator allows you 3 edges');
  expect(await scan(page)).toEqual([]);
});

test('an enrolled edge: the link to the operator, the certificate names, the policies read-only', async ({ page }) => {
  await loadPartnerApp(page, { path: `${edgesPath}&id=edge-pilot-eu-1` });
  const panel = sidePanel(page, 'edge-pilot-eu-1');
  await expect(sidePanelDialog(page, 'edge-pilot-eu-1')).toBeVisible();
  const link = panel.getByRole('region', { name: 'Link to the operator' });
  await expect(link).toContainText('https://central-mtls.acme.example');
  await expect(link).toContainText('connected');
  await expect(link).toContainText('serves from the signed snapshot; management calls answer 503');
  const certificate = panel.getByRole('region', { name: 'Certificate' });
  await expect(certificate).toContainText('Nov 3, 2026 → Nov 17, 2026');
  await expect(certificate).toContainText('urn:versola:edge:edge-pilot-eu-1');
  await expect(certificate).toContainText('urn:versola:edge-group:pilot-pay');
  await expect(certificate).toContainText('By itself');
  await expect(panel.getByRole('region', { name: 'Replicas' })).toContainText('2, each with its own copy of the certificate');
  const policies = panel.getByRole('region', { name: "The operator's policies" });
  await expect(policies).toContainText('partner-default · 30000 / 60 s per client');
  await expect(policies).toContainText('inbound priority · retry budget 10 %');
  await expect(policies).toContainText('failures-and-auth');
  await expect(policies.getByRole('button')).toHaveCount(0);
  await expect(panel.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  expect(await scan(page)).toEqual([]);

  await panel.getByRole('button', { name: 'Open page' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'edge-pilot-eu-1' })).toBeVisible();
  await expect(page.getByRole('region', { name: "The operator's policies" })).toBeVisible();
  expect(await scan(page)).toEqual([]);
});

test('adds an edge: the token and the bootstrap bundle are shown once, and leaving asks first', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: edgesPath, state: { edges: partnerSeedState.edges.slice(0, 2) } });
  await page.getByRole('button', { name: '+ Add edge' }).click();
  const editor = sidePanel(page, 'Add edge');
  await expect(page).toHaveURL(/new=1/);
  await editor.getByRole('button', { name: 'Add edge' }).click();
  await expect(editor.getByRole('alert')).toHaveText(['An edge ID is required.', 'The public URL is required.']);
  await editor.getByLabel('Edge ID *').fill('pilot-us-1');
  await expect(editor.getByText('Registered as edge-pilot-us-1.')).toBeVisible();
  await editor.getByLabel('Public URL *').fill('https://login-us.pilotpay.example/path');
  await editor.getByRole('button', { name: 'Add edge' }).click();
  await expect(editor.getByRole('alert')).toHaveText('An https origin, such as https://login.pilotpay.example.');
  await editor.getByLabel('Public URL *').fill('https://login-us.pilotpay.example/');
  expect(await scan(page)).toEqual([]);
  await editor.getByRole('button', { name: 'Add edge' }).click();

  expect((await findRequest(api.requests, 'POST', '/edges')).body).toEqual({ id: 'edge-pilot-us-1', publicUrl: 'https://login-us.pilotpay.example' });
  // One write: the edge comes back with its first token and bundle, no second call.
  expect(api.requests.filter(request => request.method === 'POST').map(request => request.pathname)).toEqual(['/edges']);
  const panel = sidePanel(page, 'edge-pilot-us-1');
  await expect(panel.getByRole('status').first()).toContainText('Bootstrap bundle created. It is shown once.');
  await expect(panel.getByRole('status').first()).toContainText('bootstrap.json');
  await expect(panel.locator('.secret-value').first()).toHaveText(/^vtk_pilot-pay_/);
  const bundle = panel.getByRole('region', { name: 'Bootstrap bundle' });
  await expect(bundle).toContainText('"edgeId": "edge-pilot-us-1"');
  // The signing key comes back with the edge, in the same write, and is shown once.
  await expect(panel.getByRole('region', { name: 'Signing key' })).toContainText('BEGIN PRIVATE KEY');
  await expect(bundle).toContainText('"group": "pilot-pay"');
  await expect(bundle).toContainText('https://central-mtls.acme.example');
  await expect(panel.getByRole('region', { name: 'Certificate' })).toContainText('Not enrolled · token expires in 24 hr');
  expect(await scan(page)).toEqual([]);

  await panel.getByRole('button', { name: 'Copy token' }).click();
  await expect(panel.locator('.copy-feedback')).toHaveText(/Token copied\.|Could not copy the token\./);
  await page.getByRole('button', { name: 'Close panel' }).click();
  const leave = page.getByRole('dialog', { name: 'Leave without saving the bundle?' });
  await expect(leave).toBeVisible();
  await leave.getByRole('button', { name: 'Stay' }).click();
  await expect(panel.locator('.secret-value').first()).toBeVisible();
  await panel.getByRole('button', { name: 'Done' }).click();
  await expect(panel.locator('.secret-value')).toHaveCount(0);
  await page.getByRole('button', { name: 'Close panel' }).click();
  await expect(panel).toHaveCount(0);
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'edge-pilot-us-1' })).toContainText('token expires in 24 hr');
  await expect(page.getByRole('button', { name: '+ Add edge' })).toBeDisabled();
});

test('a waiting edge: revoke the unused token, issue a new one', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${edgesPath}&id=edge-pilot-kz-1` });
  const panel = sidePanel(page, 'edge-pilot-kz-1');
  const certificate = panel.getByRole('region', { name: 'Certificate' });
  await expect(certificate).toContainText('Not enrolled · token expires in 1 hr');
  await expect(sidePanel(page, 'edge-pilot-kz-1').getByRole('region', { name: 'Link to the operator' })).toContainText('not enrolled');
  await certificate.getByRole('button', { name: 'Revoke token' }).click();
  await page.getByRole('dialog', { name: 'Revoke enrollment token' }).getByRole('button', { name: 'Revoke token' }).click();
  await findRequest(api.requests, 'DELETE', '/edges/edge-pilot-kz-1/enrollment-tokens');
  await expect(certificate).toContainText('no token issued');
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'edge-pilot-kz-1' })).toContainText('no token');

  await certificate.getByRole('button', { name: 'Issue enrollment token' }).click();
  await findRequest(api.requests, 'POST', '/edges/edge-pilot-kz-1/enrollment-tokens');
  await expect(panel.getByRole('status').first()).toContainText('Bootstrap bundle created. It is shown once.');
  await expect(certificate).toContainText('token expires in 24 hr');
  await expect(certificate.getByRole('button', { name: 'Issue a new token' })).toBeVisible();
});

test('removes an edge through the confirm dialog', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${edgesPath}&id=edge-pilot-eu-2` });
  await sidePanel(page, 'edge-pilot-eu-2').getByRole('button', { name: 'Remove edge edge-pilot-eu-2' }).click();
  await expect(page.getByRole('dialog', { name: 'Remove edge' })).toContainText('sessions on it end');
  await page.getByRole('dialog', { name: 'Remove edge' }).getByRole('button', { name: 'Remove', exact: true }).click();
  await findRequest(api.requests, 'DELETE', '/edges/edge-pilot-eu-2');
  await expect(sidePanel(page, 'edge-pilot-eu-2')).toHaveCount(0);
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open edge/ })).toHaveText(['edge-pilot-eu-1', 'edge-pilot-kz-1']);
  await expect(page.getByRole('button', { name: '+ Add edge' })).toBeEnabled();
});

test('suspended: renewals refused on the record, no token actions, no removal', async ({ page }) => {
  await loadPartnerApp(page, { path: `${edgesPath}&id=edge-pilot-eu-1`, state: suspendedPartner });
  const panel = sidePanel(page, 'edge-pilot-eu-1');
  await expect(panel.getByRole('status').first()).toContainText('Certificate renewal refused: Acme Retail has suspended you');
  await expect(panel.getByRole('status').first()).toContainText('The certificate expires on Nov 17, 2026');
  await expect(panel.getByRole('region', { name: 'Certificate' })).toContainText('Refused: suspended');
  await expect(panel.getByRole('region', { name: 'Remove edge' })).toHaveCount(0);
  expect(await scan(page)).toEqual([]);
});

test('a viewer gets the record read-only; the sheet below 720px', async ({ page }) => {
  await loadPartnerApp(page, { path: `${edgesPath}&id=edge-pilot-kz-1`, state: asLevel('viewer') });
  const panel = sidePanel(page, 'edge-pilot-kz-1');
  await expect(panel.getByRole('button', { name: /token/ })).toHaveCount(0);
  await expect(panel.getByRole('region', { name: 'Remove edge' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '+ Add edge' })).toHaveCount(0);

  await page.setViewportSize({ width: 375, height: 812 });
  await loadPartnerApp(page, { path: `${edgesPath}&id=edge-pilot-eu-1` });
  const dialog = sidePanelDialog(page, 'edge-pilot-eu-1');
  await expect(dialog).toBeVisible();
  await expectSheetWidth(page, (await dialog.boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect(await scan(page)).toEqual([]);
});

test('the empty state offers the first edge', async ({ page }) => {
  await loadPartnerApp(page, { path: edgesPath, state: { edges: [] } });
  await expect(page.getByRole('heading', { name: 'No edges yet' })).toBeVisible();
  await page.getByRole('button', { name: '+ Add edge' }).click();
  await expect(sidePanelDialog(page, 'Add edge')).toBeVisible();
  expect(await scan(page)).toEqual([]);
});
