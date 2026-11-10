import { expect, test } from '@playwright/test';
import { scan } from './axe';
import { loadPartnerApp, sidePanelDialog } from './fixtures';
import { PARTNER_NOW, partnerSeedState } from './partner-fixtures';

/** Overview: the link to the operator, the edges, what needs attention, the limits, and the last events. */
test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('shows the link, the edges, the attention list, the limits and the recent activity', async ({ page }) => {
  await loadPartnerApp(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Overview' })).toBeVisible();
  await expect(page.getByText('Pilot Pay on the Versola installation of Acme Retail.')).toBeVisible();
  await expect(page.getByText('Connected', { exact: true })).toBeVisible();
  await expect(page.getByText('central over mTLS · auth https://id.acme.example')).toBeVisible();
  await expect(page.getByText('next certificate renewal before Nov 15, 2026')).toBeVisible();
  for (const id of ['edge-pilot-eu-1', 'edge-pilot-eu-2', 'edge-pilot-kz-1']) await expect(page.locator('.tile').getByText(id, { exact: true })).toBeVisible();

  await expect(page.getByRole('button', { name: 'Client pilot-pay-checkout is waiting for the operator since Nov 5, 2026' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Edge edge-pilot-kz-1 has not enrolled yet (token expires in 1 hr)' })).toBeVisible();
  // The invite expires in six days: not yet something to attend to.
  await expect(page.getByRole('button', { name: /^Invite for/ })).toHaveCount(0);

  const clients = page.getByRole('region', { name: 'Clients' });
  await expect(clients.getByRole('button', { name: 'pilot-pay-checkout' })).toBeVisible();
  await expect(clients).toContainText('Awaiting approval');
  const limits = page.getByRole('region', { name: 'What the operator allows you' });
  await expect(limits).toContainText('pilotpay.example');
  await expect(limits).toContainText('up to 10 · up to 3');
  await expect(limits).toContainText('access ≤ 1 h · refresh ≤ 30 days');
  await expect(limits).toContainText('Nov 27, 2026');
  const activity = page.getByRole('region', { name: 'Recent activity' });
  await expect(activity.locator('li')).toHaveCount(6);
  await expect(activity.locator('li').first()).toContainText('Marat Lee');
  expect(await scan(page)).toEqual([]);
});

test('the attention items and the card buttons go to the record', async ({ page }) => {
  await loadPartnerApp(page);
  await page.getByRole('button', { name: /^Client pilot-pay-checkout is waiting/ }).click();
  await expect(page).toHaveURL(/view=clients/);
  await expect(page).toHaveURL(/id=pilot-pay-checkout/);
  await expect(sidePanelDialog(page, 'Pilot Pay Checkout')).toBeVisible();

  await page.goto('/partner.html?view=overview');
  await page.getByRole('region', { name: 'Recent activity' }).getByRole('button', { name: 'Open audit' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Audit' })).toBeVisible();

  await page.goto('/partner.html?view=overview');
  await page.getByRole('button', { name: '+ Create client' }).click();
  await expect(page).toHaveURL(/view=clients/);
  await expect(page).toHaveURL(/new=1/);
  await expect(page.getByRole('heading', { level: 1, name: 'Create client' })).toBeVisible();
});

test('says Nothing when nothing needs attention, and that cards fail on their own', async ({ page }) => {
  await loadPartnerApp(page, {
    state: {
      clients: partnerSeedState.clients.filter(client => client.approval.status === 'approved'),
      edges: partnerSeedState.edges.filter(edge => edge.status === 'enrolled'),
    },
  });
  await expect(page.getByText('Nothing', { exact: true })).toBeVisible();
  await expect(page.getByText('no client waiting, no invite expiring, no renewal refused')).toBeVisible();

  await page.route('**/partner/api/audit*', route => route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"boom"}' }));
  await page.goto('/partner.html?view=overview');
  await expect(page.getByRole('region', { name: 'Recent activity' })).toContainText('…');
  await expect(page.getByRole('region', { name: 'Clients' })).toContainText('pilot-pay-web');
  await expect(page.getByRole('alert')).toHaveCount(0);
});
