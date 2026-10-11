import { expect, type Page } from '@playwright/test';
import { build } from 'esbuild';
import { solidPlugin } from 'esbuild-plugin-solid';
import { readFile } from 'node:fs/promises';

/**
 * Renders one of the login forms (central-ui/forms/<id>) the way the server does: a page that sets
 * `window.__VERSOLA_FORM__` and loads the form's bundle, with common.css and the form's own CSS.
 * The bundle is built from source with the same esbuild options as scripts/build-forms.mjs, so a
 * test exercises what ships. Strings come from the form's own <id>.i18n.json, so the tests assert
 * on the real text and a missing key shows up as a missing string.
 *
 * Submissions are intercepted: a form posts to /challenge/... (or /logout), and the harness records
 * the request and answers with a blank page instead of a backend.
 */

export const CSRF = 'csrf-token-1';

export type Translations = Record<string, Record<string, string>>;

export type SubmittedRequest = {
  method: string;
  path: string;
  /** The query string, where the forms put `ui_locale`. */
  query: URLSearchParams;
  /** The url-encoded body of the post. */
  fields: URLSearchParams;
};

const bundles = new Map<string, Promise<string>>();

function formBundle(id: string): Promise<string> {
  let bundle = bundles.get(id);
  if (!bundle) {
    bundle = build({
      entryPoints: [new URL(`../forms/${id}/${id}.tsx`, import.meta.url).pathname],
      bundle: true,
      write: false,
      format: 'iife',
      minify: true,
      target: 'es2019',
      plugins: [solidPlugin()],
    }).then(result => result.outputFiles[0].text);
    bundles.set(id, bundle);
  }
  return bundle;
}

const read = (path: string) => readFile(new URL(path, import.meta.url), 'utf8');

export async function translations(id: string): Promise<Translations> {
  return JSON.parse(await read(`../forms/${id}/${id}.i18n.json`));
}

/** What a form config needs besides its `step`; anything given overrides the defaults. */
export type FormConfig = { step: Record<string, unknown> } & Record<string, unknown>;

export async function renderForm(page: Page, id: string, config: FormConfig) {
  const [all, commonCss, formCss, script] = await Promise.all([
    translations(id),
    read('../forms/common.css'),
    read(`../forms/${id}/${id}.css`),
    formBundle(id),
  ]);
  const full = { locale: 'en', locales: Object.keys(all), t: all.en, allT: all, csrf: CSRF, ...config };

  const submitted: SubmittedRequest[] = [];
  await page.route('**/__form__', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>form</title>' }));
  await page.route(
    url => url.pathname.startsWith('/challenge') || url.pathname === '/logout',
    async route => {
      const request = route.request();
      const url = new URL(request.url());
      submitted.push({
        method: request.method(),
        path: url.pathname,
        query: url.searchParams,
        fields: new URLSearchParams(request.postData() ?? ''),
      });
      await route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>next</title><p>next page</p>' });
    },
  );

  await page.goto('/__form__');
  await page.setContent(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${all.en.page_title.replace(/&/g, '&amp;').replace(/</g, '&lt;')}</title>
<style>${commonCss}\n${formCss}</style></head><body>
<script>window.__VERSOLA_FORM__ = ${JSON.stringify(full).replace(/</g, '\\u003c')};</script>
<div id="versola-form-root"></div>
<script>${script}</script>
</body></html>`);
  await expect(page.locator('#versola-form-root h1')).toBeVisible();

  return { submitted, t: all.en, all };
}

/** A submission is a navigation, so wait for it instead of reading the log once. */
export async function expectSubmissions(submitted: SubmittedRequest[], count: number) {
  await expect.poll(() => submitted.length, { message: `Expected ${count} submitted request(s)` }).toBe(count);
}
