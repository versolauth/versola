import { expect, type Page } from '@playwright/test';
import { loadPartnerApp, sidePanel, test } from './fixtures';
import { FORBIDDEN_TEXT, WRITE_CONTRACT, expectWritesWithinContract } from './write-contract';
import { PARTNER_NOW, asLevel, partnerSeedState, suspendedPartner } from './partner-fixtures';

/**
 * A partner has no way to choose, or even see, "first-party" as an edge, a group kind, an owner or a binding: the edge fixes
 * the group from the certificate that names the partner. Two checks keep it so, with `write-contract.ts` (every write of every
 * partner-console spec is checked against the contract after the test):
 *
 * - the DOM of every view, its editors and the client wizard, shadow roots included, carries no such text, label, option or
 *   attribute, and no control that picks an owner or a group;
 * - the contract table itself rejects a body with a group, a group kind, an owner or a binding.
 *
 * The same words are absent from the sources and from the built partner file (`src/partner/no-first-party.test.ts`,
 * `scripts/check-bundles.mjs`).
 */
test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

/** Every text node and every attribute value under the page, shadow roots included. */
const collect = (page: Page) => page.evaluate(() => {
  const found: string[] = [];
  const walk = (root: ParentNode) => {
    for (const element of Array.from(root.querySelectorAll('*'))) {
      for (const node of Array.from(element.childNodes)) if (node.nodeType === Node.TEXT_NODE && node.textContent?.trim()) found.push(`text: ${node.textContent.trim()}`);
      for (const attribute of Array.from(element.attributes)) found.push(`${attribute.name}=${attribute.value}`);
      if (element instanceof HTMLInputElement || element instanceof HTMLSelectElement || element instanceof HTMLTextAreaElement) found.push(`value=${element.value}`);
      if (element.shadowRoot) walk(element.shadowRoot);
    }
  };
  walk(document);
  return found;
});

/** Controls that would pick an owner, a group or an edge binding: a labelled select, radio, switch, combobox or typeahead. */
const ownerControls = (page: Page) => page.evaluate(() => {
  const found: string[] = [];
  const walk = (root: ParentNode) => {
    for (const element of Array.from(root.querySelectorAll('select, input, [role=radio], [role=radiogroup], [role=combobox], [role=switch], [role=listbox], versola-typeahead-select'))) {
      const label = element.getAttribute('aria-label') ?? element.getAttribute('name') ?? element.id ?? element.tagName;
      if (/owner|group|binding|bound|party|serves/i.test(label)) found.push(`${element.tagName.toLowerCase()} ${label}`);
    }
    for (const element of Array.from(root.querySelectorAll('*'))) if (element.shadowRoot) walk(element.shadowRoot);
  };
  walk(document);
  return found;
});

const views: Array<{ name: string; path: string; state?: Parameters<typeof loadPartnerApp>[1]['state']; ready?: (page: Page) => Promise<void> }> = [
  { name: 'overview', path: '/partner.html?view=overview' },
  { name: 'clients', path: '/partner.html?view=clients' },
  { name: 'a client record', path: '/partner.html?view=clients&id=pilot-pay-web' },
  { name: 'the client editor', path: '/partner.html?view=clients&id=pilot-pay-web&edit=general' },
  { name: 'the client wizard, step 1', path: '/partner.html?view=clients&new=1' },
  { name: 'the client wizard, step 2', path: '/partner.html?view=clients&new=1', ready: async page => {
    await page.getByRole('button', { name: /^Web app/ }).click();
    await page.getByRole('button', { name: 'Continue to basics' }).click();
    await expect(page.getByLabel('Client ID *')).toBeVisible();
  } },
  { name: 'the client wizard, step 3', path: '/partner.html?view=clients&new=1', ready: async page => {
    await page.getByRole('button', { name: /^Web app/ }).click();
    await page.getByRole('button', { name: 'Continue to basics' }).click();
    await page.getByLabel('Client ID *').fill('pilot-pay-portal');
    await page.getByLabel('Display name *').fill('Pilot Pay Portal');
    await page.getByLabel('Redirect URIs *').fill('https://portal.pilotpay.example/complete');
    await page.getByLabel('Redirect URIs *').press('Enter');
    await page.getByRole('button', { name: 'Continue to review' }).click();
    await expect(page.getByText('Review and submit')).toBeVisible();
  } },
  { name: 'resources', path: '/partner.html?view=resources' },
  { name: 'a resource record', path: '/partner.html?view=resources&id=pilot-orders' },
  { name: 'the resource editor', path: '/partner.html?view=resources&new=1' },
  { name: 'edges', path: '/partner.html?view=edges' },
  { name: 'an edge record', path: '/partner.html?view=edges&id=edge-pilot-eu-1' },
  { name: 'the Add edge editor', path: '/partner.html?view=edges&new=1', state: { edges: partnerSeedState.edges.slice(0, 1) } },
  { name: 'revocation', path: '/partner.html?view=revocation' },
  { name: 'audit', path: '/partner.html?view=audit' },
  { name: 'team', path: '/partner.html?view=team' },
  { name: 'the invite editor', path: '/partner.html?view=team&new=1' },
  { name: 'settings', path: '/partner.html?view=settings' },
  { name: 'overview of a suspended partner', path: '/partner.html?view=overview', state: suspendedPartner },
  { name: 'clients of a viewer', path: '/partner.html?view=clients', state: asLevel('viewer') },
];

for (const view of views) {
  test(`${view.name}: nothing offers or names first-party, a group kind, an owner or an edge binding`, async ({ page }) => {
    await loadPartnerApp(page, { path: view.path, state: view.state });
    await view.ready?.(page);
    await page.waitForLoadState('networkidle');
    // The panel or page of a record is in the DOM by now; its heading proves the route resolved.
    await expect(page.locator('versola-partner-console')).toBeVisible();
    const found = await collect(page);
    expect(found.length).toBeGreaterThan(20);
    expect(found.filter(entry => FORBIDDEN_TEXT.some(pattern => pattern.test(entry)))).toEqual([]);
    expect(found.filter(entry => /^(data-)?(group-?kind|party|owner)=/i.test(entry))).toEqual([]);
    expect(await ownerControls(page)).toEqual([]);
  });
}

test('the Edges list has no Group or Kind column and Add edge asks for the ID and the public URL only', async ({ page }) => {
  await loadPartnerApp(page, { path: '/partner.html?view=edges&new=1', state: { edges: partnerSeedState.edges.slice(0, 1) } });
  const editor = sidePanel(page, 'Add edge');
  await expect(editor.getByLabel('Edge ID *')).toBeVisible();
  await expect(editor.getByLabel('Public URL *')).toBeVisible();
  // Two fields and the buttons: no select, radio, checkbox or switch that could pick a group or a kind.
  await expect(editor.locator('select, input[type=radio], input[type=checkbox], [role=switch], [role=combobox]')).toHaveCount(0);
  await expect(editor.locator('input:not([type=hidden])')).toHaveCount(2);
  await page.goto('/partner.html?view=edges');
  const headers = await page.locator('versola-data-table').getByRole('columnheader').allTextContents();
  expect(headers.map(header => header.trim().toLowerCase()).filter(header => /group|kind|owner|party/.test(header))).toEqual([]);
});

test('the client wizard has no owner or edge field: step 1 is the type, step 2 the client, and the group is a fixed row', async ({ page }) => {
  await loadPartnerApp(page, { path: '/partner.html?view=clients&new=1' });
  await expect(page.getByRole('region', { name: 'Owner' })).toHaveCount(0);
  await expect(page.getByRole('group', { name: 'Who owns the client' })).toHaveCount(0);
  // The group is told, as the partner's own, in a locked row; nothing next to it can be changed.
  await expect(page.getByText('Served by the edges of group pilot-pay')).toBeVisible();
  await page.getByRole('button', { name: /^Web app/ }).click();
  await page.getByRole('button', { name: 'Continue to basics' }).click();
  await expect(page.getByLabel(/owner|group|edge/i)).toHaveCount(0);
});

test('a body with a group, a group kind, an owner, an edge binding or a party is refused by the contract check', () => {
  const log = (method: string, pathname: string, body: unknown) => [{ method, pathname, searchParams: {}, body }];
  const ok = { id: 'pilot-us-1', publicUrl: 'https://login-us.pilotpay.example' };
  expect(() => expectWritesWithinContract(log('POST', '/edges', ok))).not.toThrow();
  for (const extra of [{ groupId: 'pilot-pay' }, { groupKind: 'first_party' }, { party: 'first-party' }, { owner: 'first-party' }, { partnerId: 'pilot-pay' }, { edgeId: 'edge-1' }, { binding: 'x' }]) {
    expect(() => expectWritesWithinContract(log('POST', '/edges', { ...ok, ...extra })), JSON.stringify(extra)).toThrow();
  }
  // Nested, and as a value; and a write the contract does not list.
  expect(() => expectWritesWithinContract(log('PUT', '/settings', { contact: { email: 'a@pilotpay.example', groupKind: 'first_party' }, defaults: {} }))).toThrow();
  expect(() => expectWritesWithinContract(log('POST', '/resources', { resourceId: 'r', resource: 'https://r.example', audience: ['first-party'] }))).toThrow();
  expect(() => expectWritesWithinContract(log('POST', '/edges/edge-1/group', {}))).toThrow();
  expect(() => expectWritesWithinContract(log('POST', '/edges/edge-1/enrollment-tokens', { groupId: 'pilot-pay' }))).toThrow();
  // Every write the console's API client makes has a row.
  expect(WRITE_CONTRACT.length).toBeGreaterThanOrEqual(18);
});
