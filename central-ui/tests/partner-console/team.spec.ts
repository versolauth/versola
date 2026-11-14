import { expect, test } from '@playwright/test';
import { expectSheetWidth, findRequest } from '../fixtures';
import { scan } from './axe';
import { loadPartnerApp, sidePanel, sidePanelDialog } from './fixtures';
import { PARTNER_NOW, asLevel } from './partner-fixtures';

/**
 * Team, kept on the partner's edge only (#549 decision 3): the members and the invites, invite by
 * phone or email with nothing looked up, change a level, remove a member, and the admin who cannot
 * remove themself.
 */
const teamPath = '/partner.html?view=team';

test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

test('lists members and invites, filters them, and explains the three levels', async ({ page }) => {
  await loadPartnerApp(page, { path: teamPath });
  await expect(page.getByText('Kept on your edge only: the operator never sees this list')).toBeVisible();
  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Member', 'Level', 'State', 'Added', 'Actions']);
  await expect(table.getByRole('button', { name: /^Open member/ })).toHaveCount(4);
  await expect(table.locator('tr[data-key="u_7f3a9c2e"]')).toContainText('since Sep 2, 2026');
  await expect(table.locator('tr[data-key="inv-1"]')).toContainText('Invited');
  await expect(table.locator('tr[data-key="inv-1"]')).toContainText('expires Nov 13, 2026');
  await expect(page.getByRole('region', { name: 'Levels' })).toContainText('read everything, change nothing');
  expect(await scan(page)).toEqual([]);

  await page.getByRole('group', { name: 'Filter team' }).getByRole('button', { name: 'Admins' }).click();
  await expect(table.getByRole('button', { name: /^Open member/ })).toHaveText([/Dana Kim/, /ops@pilotpay\.example/]);
  await page.getByRole('group', { name: 'Filter team' }).getByRole('button', { name: 'Invited' }).click();
  await expect(table.getByRole('button', { name: /^Open member/ })).toHaveText([/ops@pilotpay\.example/]);
  await page.getByRole('group', { name: 'Filter team' }).getByRole('button', { name: 'All' }).click();
  await page.getByLabel('Search team').fill('marat');
  await expect(table.getByRole('button', { name: /^Open member/ })).toHaveText([/Marat Lee/]);
});

test('invites by email with nothing looked up; the invite waits for the first sign-in', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: teamPath });
  await page.getByRole('button', { name: '+ Invite' }).click();
  const editor = sidePanel(page, 'Invite a member');
  await expect(page).toHaveURL(/new=1/);
  await expect(editor.getByText('Nothing is sent and nothing is looked up')).toBeVisible();
  await expect(editor.getByRole('radiogroup', { name: 'Level' }).getByRole('radio')).toHaveCount(3);
  await expect(editor.getByRole('radio', { name: /developer/ })).toBeChecked();
  expect(await scan(page)).toEqual([]);

  await editor.getByRole('button', { name: 'Create invitation' }).click();
  await expect(editor.getByRole('alert')).toHaveText('A phone number or an email is required.');
  await editor.getByLabel('Phone or email *').fill('not an identifier');
  await editor.getByRole('button', { name: 'Create invitation' }).click();
  await expect(editor.getByRole('alert')).toContainText('A phone number in international format');
  await editor.getByLabel('Phone or email *').fill('sec@pilotpay.example');
  await editor.getByLabel('Expires').selectOption('30');
  await editor.getByRole('radio', { name: /viewer/ }).check();
  await editor.getByRole('button', { name: 'Create invitation' }).click();

  expect((await findRequest(api.requests, 'POST', '/staff')).body).toEqual({ identifier: 'sec@pilotpay.example', level: 'viewer', expiresInDays: 30 });
  const panel = sidePanel(page, 'sec@pilotpay.example');
  await expect(sidePanelDialog(page, 'sec@pilotpay.example')).toBeVisible();
  await expect(panel.getByRole('region', { name: 'Member' })).toContainText('Unknown until the first sign-in');
  await expect(panel.getByRole('region', { name: 'Member' })).toContainText('Stored hashed until the first sign-in');
  await expect(panel.getByRole('region', { name: 'Member' })).toContainText('expires Dec 7, 2026');
  await expect(panel.getByRole('region', { name: 'Cancel invitation' })).toBeVisible();
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open member/ })).toHaveCount(5);
});

test('changes a member\'s level; the signed-in admin cannot change or remove themself', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${teamPath}&id=u_2b91d4e0` });
  const panel = sidePanel(page, 'Marat Lee');
  await expect(panel.getByRole('region', { name: 'Member' })).toContainText('u_2b91d4e0');
  await expect(panel.getByRole('region', { name: 'Member' })).toContainText('From the operator');
  await panel.getByRole('button', { name: 'Edit level' }).click();
  const editor = sidePanel(page, 'Edit level');
  await expect(editor.getByRole('radio', { name: /developer/ })).toBeChecked();
  await editor.getByRole('radio', { name: /admin/ }).check();
  await expect(editor.getByText('An admin can invite and remove members, change settings and end sessions.')).toBeVisible();
  await editor.getByRole('button', { name: 'Save level' }).click();
  expect((await findRequest(api.requests, 'PUT', '/staff/u_2b91d4e0')).body).toEqual({ level: 'admin' });
  await expect(panel.getByRole('region', { name: 'Level' })).toContainText('admin');
  await expect(page.locator('versola-data-table').locator('tr[data-key="u_2b91d4e0"]')).toContainText('admin');

  await page.goto(`${teamPath}&id=u_7f3a9c2e`);
  const self = sidePanel(page, 'Dana Kim');
  await expect(self.getByRole('region', { name: 'Level' })).toContainText('Your own level');
  await expect(self.getByRole('button', { name: 'Edit level' })).toHaveCount(0);
  await expect(self.getByRole('button', { name: 'Remove member Dana Kim' })).toBeDisabled();
  await expect(self.getByText('You cannot remove yourself: ask another admin.')).toBeVisible();
  expect(await scan(page)).toEqual([]);
});

test('removes a member and cancels an invitation through the confirm dialogs', async ({ page }) => {
  const api = await loadPartnerApp(page, { path: `${teamPath}&id=u_9c0e1a77` });
  await sidePanel(page, 'Aya Sato').getByRole('button', { name: 'Remove member Aya Sato' }).click();
  await expect(page.getByRole('dialog', { name: 'Remove member' })).toContainText('their account with the operator is untouched');
  await page.getByRole('dialog', { name: 'Remove member' }).getByRole('button', { name: 'Remove', exact: true }).click();
  await findRequest(api.requests, 'DELETE', '/staff/u_9c0e1a77');
  await expect(sidePanel(page, 'Aya Sato')).toHaveCount(0);

  await page.getByRole('button', { name: 'Actions for ops@pilotpay.example' }).click();
  await page.getByRole('menuitem', { name: 'Cancel invitation ops@pilotpay.example' }).click();
  await page.getByRole('dialog', { name: 'Cancel invitation' }).getByRole('button', { name: 'Cancel invitation' }).click();
  await findRequest(api.requests, 'DELETE', '/staff/inv-1');
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open member/ })).toHaveText([/Dana Kim/, /Marat Lee/]);
});

test('a developer has no Team; the sheet below 720px', async ({ page }) => {
  await loadPartnerApp(page, { path: teamPath, state: asLevel('developer') });
  await expect(page.getByRole('heading', { level: 1, name: 'No access' })).toBeVisible();

  await page.setViewportSize({ width: 375, height: 812 });
  await loadPartnerApp(page, { path: `${teamPath}&id=inv-1` });
  const dialog = sidePanelDialog(page, 'ops@pilotpay.example');
  await expect(dialog).toBeVisible();
  await expectSheetWidth(page, (await dialog.boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect(await scan(page)).toEqual([]);
});
