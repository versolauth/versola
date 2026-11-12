/**
 * The review screenshots of the partner-edge screens (#963): every operator and partner-console
 * screen in light and dark at 1440×900, and the lists and one record at 390 wide. Not an
 * assertion suite: one test per shot and theme, each writing a PNG under
 * `test-results/partner-edge/` (its own Playwright project, `partner-edge`, so a `console` run
 * does not empty it). The gallery in `versola-root/ui-review/partner-edge/` is copied from here.
 *
 * Run with: npx playwright test --project partner-edge
 */
import { expect, test, type Page } from '@playwright/test';
import { loadAdminApp, sidePanel } from './fixtures';
import { PARTNER_NOW, partnerState, partnersPath, partnerTenant } from './partners-fixtures';
import { loadPartnerApp } from './partner-console/fixtures';
import { asLevel, centralDown, notAMember, partnerSeedState, suspendedPartner } from './partner-console/partner-fixtures';

const outputDir = 'test-results/partner-edge';
const frame = { width: 1440, height: 900 };
const narrow = { width: 390, height: 844 };

test.use({ locale: 'en-US', timezoneId: 'UTC' });

type Shot = {
  name: string;
  /** `operator` loads the admin console with the partner fixtures; `partner` loads the partner console. */
  app: 'operator' | 'partner';
  path: string;
  state?: Parameters<typeof loadPartnerApp>[1]['state'];
  before?: (page: Page) => Promise<void>;
  /** Also taken at 390 wide. */
  narrow?: boolean;
  fullPage?: boolean;
};

const walkClientWizardToOwner = async (page: Page) => {
  await page.getByRole('button', { name: '+ Create Client', exact: true }).click();
  await page.getByRole('button', { name: /Web app/ }).click();
  await page.getByRole('group', { name: 'Who owns the client' }).getByRole('button', { name: 'Partner' }).click();
  await page.getByRole('combobox', { name: 'Partner' }).click();
  await page.getByRole('option', { name: /Pilot Pay/ }).click();
  await expect(page.getByText('Consent required, names the partner')).toBeVisible();
};

const shots: Shot[] = [
  // ── operator ──
  { name: 'op-partners', app: 'operator', path: partnersPath, narrow: true, before: async page => {
    await expect(page.getByRole('button', { name: /^Open partner Pilot Pay/ })).toBeVisible();
  } },
  { name: 'op-partner-panel', app: 'operator', path: `${partnersPath}&id=pilot-pay`, narrow: true, before: async page => {
    await expect(sidePanel(page, 'Pilot Pay').getByRole('region', { name: 'Limits' })).toBeVisible();
  } },
  { name: 'op-partner-page', app: 'operator', path: `${partnersPath}&id=pilot-pay&layout=page`, fullPage: true, before: async page => {
    await expect(page.getByRole('region', { name: 'Activity' })).toBeVisible();
  } },
  { name: 'op-partner-suspended', app: 'operator', path: `${partnersPath}&id=nomad-travel`, before: async page => {
    await expect(sidePanel(page, 'Nomad Travel').getByText('Suspended: every call of this partner is refused')).toBeVisible();
  } },
  { name: 'op-partner-edit-limits', app: 'operator', path: `${partnersPath}&id=pilot-pay&edit=limits`, before: async page => {
    await expect(sidePanel(page, 'Edit limits').getByLabel('Redirect domains *')).toBeVisible();
  } },
  { name: 'op-partner-suspend', app: 'operator', path: `${partnersPath}&id=pilot-pay&edit=suspend`, before: async page => {
    await expect(sidePanel(page, 'Suspend partner').getByLabel('Note *')).toBeVisible();
  } },
  { name: 'op-partner-new-1', app: 'operator', path: `${partnersPath}&new=1`, before: async page => {
    await page.getByLabel('Partner ID *').fill('nomad-air');
    await page.getByLabel('Display name *').fill('Nomad Air');
    await page.getByLabel('Contact email *').fill('dev@nomadair.example');
  } },
  { name: 'op-partner-new-3', app: 'operator', path: `${partnersPath}&new=1`, before: async page => {
    await fillWizardToStep3(page);
  } },
  { name: 'op-partner-new-4', app: 'operator', path: `${partnersPath}&new=1`, before: async page => {
    await fillWizardToStep3(page);
    await page.getByRole('button', { name: 'Continue to review' }).click();
    await expect(page.getByRole('region', { name: 'What the partner gets' })).toBeVisible();
  } },
  { name: 'op-partner-created', app: 'operator', path: `${partnersPath}&new=1`, fullPage: true, before: async page => {
    await fillWizardToStep3(page);
    await page.getByRole('button', { name: 'Continue to review' }).click();
    await page.getByRole('button', { name: 'Add partner', exact: true }).click();
    await expect(page.getByText('Partner created. Save the enrollment token')).toBeVisible();
  } },
  { name: 'op-approvals', app: 'operator', path: `${partnersPath}&tab=approvals`, narrow: true, before: async page => {
    await expect(page.getByRole('button', { name: /^Open request New client/ })).toBeVisible();
  } },
  { name: 'op-approval-record', app: 'operator', path: `${partnersPath}&tab=approvals&id=apr-1001`, narrow: true, before: async page => {
    await expect(page.getByRole('group', { name: 'Requested against registered' })).toBeVisible();
  } },
  { name: 'op-approval-overrides', app: 'operator', path: `${partnersPath}&tab=approvals&id=apr-1001`, before: async page => {
    await page.getByRole('button', { name: 'Approve with overrides…' }).click();
    await expect(page.getByRole('group', { name: 'Overrides' })).toBeVisible();
    await page.getByRole('group', { name: 'Overrides' }).scrollIntoViewIfNeeded();
  } },
  { name: 'op-approval-decided', app: 'operator', path: `${partnersPath}&tab=approvals&id=apr-0998`, before: async page => {
    await expect(page.getByRole('region', { name: 'Decision' })).toContainText('b.argin');
  } },
  { name: 'op-client-owner', app: 'operator', path: `/?view=clients&tenant=${partnerTenant}`, fullPage: true, before: walkClientWizardToOwner },
  { name: 'op-client-owner-basics', app: 'operator', path: `/?view=clients&tenant=${partnerTenant}`, fullPage: true, before: async page => {
    await walkClientWizardToOwner(page);
    await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
    await page.locator('versola-client-form #client-id').fill('pilot-pay-shop');
    await page.locator('versola-client-form input#client-name').fill('Pilot Pay Shop');
    await page.locator('versola-client-form #redirect-uri').fill('https://shop.pilot-partner.net/complete');
    await page.locator('versola-client-form #redirect-uri').press('Enter');
    await expect(page.getByText("Outside the partner's redirect domains")).toBeVisible();
  } },
  { name: 'op-client-record-owned', app: 'operator', path: `/?view=clients&tenant=${partnerTenant}&expandClient=pilot-pay-checkout`, before: async page => {
    await page.getByRole('button', { name: /^Open client Pilot Pay Checkout/ }).click();
    await expect(page.getByText('Awaiting approval').first()).toBeVisible();
  } },
  { name: 'op-edges', app: 'operator', path: `/?view=edges&tenant=${partnerTenant}`, narrow: true, before: async page => {
    await expect(page.getByRole('button', { name: 'Open edge edge-pilot-eu-1' })).toBeVisible();
  } },
  { name: 'op-edge-partner', app: 'operator', path: `/?view=edges&tenant=${partnerTenant}&id=edge-nomad-1`, before: async page => {
    await expect(sidePanel(page, 'edge-nomad-1').getByText('Renewal refused').first()).toBeVisible();
  } },

  // ── partner console ──
  { name: 'pt-overview', app: 'partner', path: '/partner.html?view=overview', narrow: true, before: async page => {
    await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
  } },
  { name: 'pt-clients', app: 'partner', path: '/partner.html?view=clients', narrow: true, before: async page => {
    await expect(page.getByRole('button', { name: /^Open client Pilot Pay Checkout/ })).toBeVisible();
  } },
  { name: 'pt-client-waiting', app: 'partner', path: '/partner.html?view=clients&id=pilot-pay-checkout', narrow: true, before: async page => {
    await expect(page.locator('versola-side-panel').getByText(/Waiting for Acme Retail since/)).toBeVisible();
  } },
  { name: 'pt-client-active', app: 'partner', path: '/partner.html?view=clients&id=pilot-pay-web&layout=page', fullPage: true, before: async page => {
    await expect(page.getByRole('region', { name: 'Integrate' })).toBeVisible();
  } },
  { name: 'pt-client-new', app: 'partner', path: '/partner.html?view=clients&new=1', fullPage: true, before: async page => {
    await page.getByRole('button', { name: /Web app/ }).click();
    await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
    await page.getByLabel('Client ID *').fill('pilot-pay-shop');
    await page.getByLabel('Display name *').fill('Pilot Pay Shop');
    await page.getByLabel('Redirect URIs').fill('https://shop.pilot-partner.net/complete');
    await page.getByLabel('Redirect URIs').press('Enter');
    await expect(page.getByText(/outside your redirect domains/)).toBeVisible();
  } },
  { name: 'pt-client-new-review', app: 'partner', path: '/partner.html?view=clients&new=1', fullPage: true, before: async page => {
    await page.getByRole('button', { name: /Web app/ }).click();
    await page.getByRole('button', { name: 'Continue to basics', exact: true }).click();
    await page.getByLabel('Client ID *').fill('pilot-pay-shop');
    await page.getByLabel('Display name *').fill('Pilot Pay Shop');
    await page.getByLabel('Redirect URIs').fill('https://shop.pilotpay.example/complete');
    await page.getByLabel('Redirect URIs').press('Enter');
    await page.getByRole('button', { name: 'Continue to review', exact: true }).click();
    await expect(page.getByText('the operator will review the name and logo').first()).toBeVisible();
  } },
  { name: 'pt-resources', app: 'partner', path: '/partner.html?view=resources', narrow: true, before: async page => {
    await expect(page.getByRole('button', { name: /^Open resource pilot-orders/ })).toBeVisible();
  } },
  { name: 'pt-resource-record', app: 'partner', path: '/partner.html?view=resources&id=pilot-orders&layout=page', narrow: true, fullPage: true, before: async page => {
    await expect(page.getByRole('table', { name: 'Endpoints of pilot-orders' })).toBeVisible();
  } },
  { name: 'pt-endpoint-editor', app: 'partner', path: '/partner.html?view=resources&id=pilot-orders&edit=endpoints', fullPage: true, before: async page => {
    await page.getByRole('list', { name: 'Endpoints' }).getByRole('button', { name: /^POST \/orders(?= |$)/ }).click();
    await expect(page.getByRole('region', { name: 'Test with a sample token' })).toBeVisible();
  } },
  { name: 'pt-edges', app: 'partner', path: '/partner.html?view=edges', narrow: true, before: async page => {
    await expect(page.getByRole('button', { name: 'Open edge edge-pilot-eu-1' })).toBeVisible();
  } },
  { name: 'pt-edge-record', app: 'partner', path: '/partner.html?view=edges&id=edge-pilot-eu-1&layout=page', fullPage: true, before: async page => {
    await expect(page.getByRole('region', { name: "The operator's policies" })).toBeVisible();
  } },
  { name: 'pt-edge-token', app: 'partner', path: '/partner.html?view=edges', state: { edges: partnerSeedState.edges.slice(0, 2) }, before: async page => {
    await page.getByRole('button', { name: '+ Add edge' }).click();
    await page.getByLabel('Edge ID *').fill('pilot-us-1');
    await page.getByLabel('Public URL *').fill('https://login-us.pilotpay.example');
    await page.getByRole('button', { name: 'Add edge', exact: true }).click();
    await expect(page.getByText('Enrollment token created')).toBeVisible();
  } },
  { name: 'pt-revocation', app: 'partner', path: '/partner.html?view=revocation', narrow: true, before: async page => {
    await expect(page.getByRole('heading', { name: 'Sessions & revocation' })).toBeVisible();
  } },
  { name: 'pt-audit', app: 'partner', path: '/partner.html?view=audit', narrow: true, before: async page => {
    await expect(page.getByRole('table', { name: 'Audit events' })).toBeVisible();
  } },
  { name: 'pt-team', app: 'partner', path: '/partner.html?view=team', narrow: true, before: async page => {
    await expect(page.getByRole('button', { name: /^Open member Dana Kim/ })).toBeVisible();
  } },
  { name: 'pt-team-invite', app: 'partner', path: '/partner.html?view=team&new=1', before: async page => {
    await expect(page.getByLabel('Phone or email *')).toBeVisible();
  } },
  { name: 'pt-settings', app: 'partner', path: '/partner.html?view=settings', narrow: true, before: async page => {
    await expect(page.getByRole('region', { name: 'Operator endpoints' })).toBeVisible();
  } },
  { name: 'pt-suspended', app: 'partner', path: '/partner.html?view=overview', state: suspendedPartner, before: async page => {
    await expect(page.getByText(/has suspended you/)).toBeVisible();
  } },
  { name: 'pt-central-unavailable', app: 'partner', path: '/partner.html?view=clients', state: centralDown, before: async page => {
    await expect(page.getByRole('heading', { name: 'Could not load clients' })).toBeVisible();
    await expect(page.getByText(/Central is unavailable right now/).first()).toBeVisible();
  } },
  { name: 'pt-no-access', app: 'partner', path: '/partner.html?view=overview', state: notAMember, narrow: true, before: async page => {
    await expect(page.getByRole('heading', { name: /not on Pilot Pay's team/ })).toBeVisible();
  } },
  { name: 'pt-viewer', app: 'partner', path: '/partner.html?view=clients&id=pilot-pay-web', state: asLevel('viewer'), before: async page => {
    await expect(page.locator('versola-side-panel').getByRole('region', { name: 'General' })).toBeVisible();
  } },
];

async function fillWizardToStep3(page: Page) {
  await page.getByLabel('Partner ID *').fill('nomad-air');
  await page.getByLabel('Display name *').fill('Nomad Air');
  await page.getByLabel('Contact email *').fill('dev@nomadair.example');
  await page.getByRole('button', { name: 'Continue to limits' }).click();
  await page.getByRole('group', { name: 'Allowed scopes' }).getByRole('checkbox', { name: 'profile' }).check();
  await page.getByRole('group', { name: 'Allowed scopes' }).getByRole('checkbox', { name: 'email' }).check();
  await page.getByLabel('Redirect domains *').fill('nomadair.example\nnomadair.app');
  await page.getByRole('button', { name: 'Continue to edge & console' }).click();
  await page.getByLabel('Partner console URL *').fill('https://login.nomadair.example');
  await page.getByLabel('Edge ID *').fill('eu-1');
  await page.getByLabel('Public URL *').fill('https://login.nomadair.example');
  await expect(page.getByText('Redirect URI becomes https://login.nomadair.example/complete')).toBeVisible();
}

async function growToDocument(page: Page, width: number) {
  const height = await page.evaluate(() => document.documentElement.scrollHeight);
  await page.setViewportSize({ width, height: Math.max(frame.height, height) });
}

async function take(page: Page, shot: Shot, theme: 'light' | 'dark', size: { width: number; height: number }, suffix: string) {
  await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
  await page.setViewportSize(size);
  await page.clock.install({ time: new Date(PARTNER_NOW) });
  if (shot.app === 'operator') await loadAdminApp(page, { path: shot.path, state: partnerState });
  else await loadPartnerApp(page, { path: shot.path, state: shot.state ?? {} });
  await shot.before?.(page);
  // A field focused by the recipe scrolled the page: the frame starts at the top.
  if (!shot.name.includes('overrides')) await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(400);
  if (shot.fullPage && size.width === frame.width) await growToDocument(page, size.width);
  await page.screenshot({ path: `${outputDir}/${shot.name}${suffix}-${theme}.png`, animations: 'disabled' });
}

// `PARTNER_EDGE_SHOTS=operator|partner` takes one console's shots only.
const wanted = shots.filter(shot => !process.env.PARTNER_EDGE_SHOTS || shot.app === process.env.PARTNER_EDGE_SHOTS);

for (const shot of wanted) {
  for (const theme of ['light', 'dark'] as const) {
    test(`${shot.name} (${theme})`, async ({ page }) => {
      await take(page, shot, theme, frame, '');
    });
    if (shot.narrow && theme === 'light') {
      test(`${shot.name} (390 wide)`, async ({ page }) => {
        await take(page, shot, theme, narrow, '-390');
      });
    }
  }
}
