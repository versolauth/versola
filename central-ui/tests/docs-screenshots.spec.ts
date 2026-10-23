/**
 * Generates the screenshots embedded in the public documentation (versola-website,
 * public/img/docs/). Not an assertion suite: one test per manifest entry and theme, each writing a
 * PNG under `test-results/docs-screenshots/`. The manifest (`docs-screenshots.manifest.ts`) is the
 * contract with the website: `scripts/sync-docs-screenshots.mjs` copies the output into a checkout
 * and fails when the manifest and the docs pages disagree.
 *
 * Run with: npm run docs:screenshots
 */
import { test, type Page } from '@playwright/test';
import { loadAdminApp } from './fixtures';
import { renderForm } from './form-harness';
import { docsShots, tenantLogoBodies, type DocsShot, type DocsTheme } from './docs-screenshots.manifest';

const outputDir = 'test-results/docs-screenshots';
const frame = { width: 1440, height: 900 };
const cropPadding = 16;

// Dates on the user card follow the browser; pin them so a re-run changes nothing but the screen.
test.use({ locale: 'en-US', timezoneId: 'UTC' });

const file = (name: string, theme: DocsTheme) => `${outputDir}/${name}${theme === 'light' ? '-light' : ''}.png`;

/**
 * `versola-navigation`'s sidebar is `position: fixed; height: 100vh`, so Playwright's own `fullPage`
 * capture (which stitches scrolled sections) would paint it only within the first 900px. Growing
 * the viewport to the document height avoids any scrolling: the sidebar covers the whole capture,
 * and an element's bounding box is in page coordinates, which is what a crop's clip needs.
 */
async function growToDocument(page: Page) {
  const height = await page.evaluate(() => document.documentElement.scrollHeight);
  await page.setViewportSize({ width: frame.width, height: Math.max(frame.height, height) });
}

async function consoleShot(page: Page, shot: Extract<DocsShot, { kind: 'frame' | 'crop' }>, theme: DocsTheme) {
  await page.addInitScript(value => localStorage.setItem('versola-theme', value), theme);
  await page.setViewportSize(frame);
  // The tenant's logos are URLs the console previews; they are not reachable from the test run.
  await page.route(url => url.href in tenantLogoBodies, route =>
    route.fulfill({ contentType: 'image/svg+xml', body: tenantLogoBodies[route.request().url()] }));
  await loadAdminApp(page, { path: shot.path, state: typeof shot.state === 'function' ? await shot.state() : shot.state });
  await shot.before?.(page);
  // Fonts and the theme's transitions settle before the capture.
  await page.waitForTimeout(500);

  if (shot.kind === 'crop') {
    await growToDocument(page);
    const box = await shot.section(page).boundingBox();
    if (!box) throw new Error(`${shot.name}: the section to crop is not visible`);
    const viewport = page.viewportSize()!;
    const x = Math.max(0, box.x - cropPadding);
    const y = Math.max(0, box.y - cropPadding);
    const clip = { x, y, width: Math.min(viewport.width - x, box.width + 2 * cropPadding), height: Math.min(viewport.height - y, box.height + 2 * cropPadding) };
    await page.screenshot({ path: file(shot.name, theme), clip, animations: 'disabled' });
    return;
  }

  if (shot.fullPage) await growToDocument(page);
  await page.screenshot({ path: file(shot.name, theme), animations: 'disabled' });
}

// A login screen is a narrow card: a small window keeps it legible at the docs' image width. Dark
// and light are the device's colour scheme, as the forms see it.
async function loginShot(page: Page, shot: Extract<DocsShot, { kind: 'login' }>, theme: DocsTheme) {
  await page.emulateMedia({ colorScheme: theme });
  await page.setViewportSize({ width: shot.width ?? 800, height: shot.width ? 844 : 860 });
  await renderForm(page, shot.form, { step: shot.step, ...(shot.logo ? { logo: shot.logo } : {}) }, shot.extraCss ? { extraCss: shot.extraCss } : {});
  await shot.act?.(page);
  await page.waitForTimeout(300);
  await page.screenshot({ path: file(shot.name, theme), animations: 'disabled' });
}

for (const shot of docsShots) {
  for (const theme of ['dark', 'light'] as const) {
    test(`${shot.name} (${theme})`, async ({ page }) => {
      if (shot.kind === 'login') await loginShot(page, shot, theme);
      else await consoleShot(page, shot, theme);
    });
  }
}
