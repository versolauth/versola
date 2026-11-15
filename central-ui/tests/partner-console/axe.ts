import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';

/** The WCAG 2.1 AA scan every partner-console screen passes: the violations as one line each, so a failure names the rule. */
export const scan = async (page: Page) =>
  (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()).violations.map(v => `${v.id}: ${v.help} (${v.nodes.length} nodes)`);
