import { expect, test } from '@playwright/test';
import { findRequest } from '../fixtures';
import { loadPartnerApp, sidePanel } from './fixtures';
import { PARTNER_NOW, partnerSeedState } from './partner-fixtures';

/**
 * The contract the console depends on (#963): what the edge serves and the console must not guess.
 * An approval says whether it is a creation or a change, a client carries the edge's own login URL,
 * adding an edge is one write that returns its token, and a lost session goes to the edge's fixed
 * login path.
 */
test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('the integration snippet uses the client\'s loginPath on the edge, not a path built from its id', async ({ page }) => {
  const clients = partnerSeedState.clients.map(client => client.id === 'pilot-pay-web' ? { ...client, loginPath: '/login/shop-preset' } : client);
  await loadPartnerApp(page, { path: '/partner.html?view=clients&id=pilot-pay-web', state: { clients } });
  const integrate = sidePanel(page, 'Pilot Pay Shop').getByRole('region', { name: 'Integrate' });
  await expect(integrate).toContainText('https://login.pilotpay.example/login/shop-preset');
  await expect(integrate).not.toContainText('/login/pilot-pay-web');
});

test('withdrawing a change to an approved client keeps the client; withdrawing its creation removes it', async ({ page }) => {
  // A rename of an approved client: the request is a change, so it carries `kind: 'change'` and the name is among its waiting fields.
  const changing = partnerSeedState.clients.map(client => client.id === 'pilot-pay-web'
    ? { ...client, approval: { kind: 'change' as const, status: 'pending' as const, waitingFields: ['clientName'], requestedAt: '2026-11-06T09:00:00.000Z' } }
    : client);
  const api = await loadPartnerApp(page, { path: '/partner.html?view=clients&id=pilot-pay-web', state: { clients: changing } });
  const panel = sidePanel(page, 'Pilot Pay Shop');
  await panel.getByRole('button', { name: 'Withdraw request' }).click();
  const dialog = page.getByRole('dialog', { name: 'Withdraw the request' });
  await expect(dialog).toContainText('The client stays as registered; the requested change is dropped.');
  await expect(dialog).not.toContainText('The client is removed');
  await dialog.getByRole('button', { name: 'Withdraw' }).click();
  await findRequest(api.requests, 'POST', '/clients/pilot-pay-web/withdraw');
  await expect(panel.getByRole('region', { name: 'Integrate' })).toBeVisible();
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open client/ })).toHaveCount(3);

  await page.goto('/partner.html?view=clients&id=pilot-pay-checkout');
  await sidePanel(page, 'Pilot Pay Checkout').getByRole('button', { name: 'Withdraw request' }).click();
  await expect(page.getByRole('dialog', { name: 'Withdraw the request' })).toContainText('The client is removed');
});

test('adding an edge is one write that returns the edge with its token', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: '/partner.html?view=edges&new=1', state: { edges: partnerSeedState.edges.slice(0, 1) } });
  const editor = sidePanel(page, 'Add edge');
  await editor.getByLabel('Edge ID *').fill('pilot-us-1');
  await editor.getByLabel('Public URL *').fill('https://login-us.pilotpay.example');
  await editor.getByRole('button', { name: 'Add edge' }).click();
  const panel = sidePanel(page, 'edge-pilot-us-1');
  await expect(panel.locator('.secret-value').first()).toHaveText(/^vtk1\./);
  expect(api.requests.filter(request => request.method === 'POST').map(request => request.pathname)).toEqual(['/edges']);
});

test('a lost session goes to the edge\'s fixed login path and does not leave the screen waiting', async ({ page }) => {
  await page.route('**/partner/login', route => route.fulfill({ status: 200, contentType: 'text/html', body: '<h1>edge login</h1>' }));
  // Audit does not read the clients, so nothing hits the 401 before the click does.
  await loadPartnerApp(page, { path: '/partner.html?view=audit' });
  await page.route('**/partner/api/clients', route => route.fulfill({ status: 401, body: '' }));
  await Promise.all([
    page.waitForURL(/\/partner\/login$/),
    page.getByRole('navigation', { name: 'Sections' }).getByRole('button', { name: 'Clients' }).click({ noWaitAfter: true }),
  ]);
});

// ── the same limits as central's own clients ──

test('a native client carries DPoP-bound tokens, so its access token lives at least an hour, as central requires', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: '/partner.html?view=clients&new=1' });
  await page.getByRole('button', { name: /^Mobile or desktop app/ }).click();
  await expect(page.getByText('DPoP-bound access tokens')).toBeVisible();
  await page.getByRole('button', { name: 'Continue to basics' }).click();
  // It starts at an hour instead of on a value central would refuse.
  await expect(page.getByLabel('Access token, minutes *')).toHaveValue('60');
  await page.getByLabel('Client ID *').fill('pilot-pay-android');
  await page.getByLabel('Display name *').fill('Pilot Pay Android');
  await page.getByLabel('Redirect URIs *').fill('https://app.pilotpay.example/callback');
  await page.getByLabel('Redirect URIs *').press('Enter');
  await page.getByLabel('Access token, minutes *').fill('30');
  await page.getByRole('button', { name: 'Continue to review' }).click();
  await expect(page.getByRole('alert')).toHaveText('Must be at least 1 hour when DPoP-bound access tokens are required');
  expect(api.requests.some(request => request.method === 'POST')).toBe(false);
  await page.getByLabel('Access token, minutes *').fill('60');
  await page.getByRole('button', { name: 'Continue to review' }).click();
  await expect(page.getByText('Review and submit')).toBeVisible();
});

test('the partner API refuses what central refuses: a native client under an hour, any client over a day', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: '/partner.html?view=overview' });
  const post = (body: unknown) => page.evaluate(async payload => {
    const response = await fetch('/partner/api/clients', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    return { status: response.status, body: await response.json() as { message?: string } };
  }, body);
  const base = { clientName: 'X', redirectUris: ['https://app.pilotpay.example/cb'], scope: ['openid'], refreshTokenTtl: 86_400 };
  const native = await post({ ...base, id: 'n-1', kind: 'native', accessTokenTtl: 300 });
  expect(native.status).toBe(400);
  expect(native.body.message).toContain('at least 1 hour');
  const web = await post({ ...base, id: 'w-1', kind: 'web', accessTokenTtl: 90_000 });
  expect(web.status).toBe(400);
  expect(web.body.message).toContain('24 hours');
  expect(api.requests.filter(request => request.method === 'POST')).toHaveLength(2);
});
