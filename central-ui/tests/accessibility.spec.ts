import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { loadAdminApp } from './fixtures';

/**
 * Automated accessibility scan (axe-core) of the admin console's list screens, in both themes.
 *
 * axe covers only part of WCAG, so a clean run does not mean a screen is accessible: it is a floor
 * that catches missing labels, low contrast and similar, next to looking at the screenshots
 * (see .claude/rules/frontend.md). Tags are the WCAG 2.0/2.1 levels A and AA axe documents.
 */
const views = ['tenants', 'clients', 'scopes', 'permissions', 'resources', 'roles', 'users'];
const themes = ['light', 'dark'] as const;

for (const theme of themes) {
  for (const view of views) {
    test(`${view} has no axe violations in the ${theme} theme`, async ({ page }) => {
      await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
      await loadAdminApp(page, { path: `/?view=${view}` });
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      expect(
        results.violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`),
      ).toEqual([]);
    });
  }
}
