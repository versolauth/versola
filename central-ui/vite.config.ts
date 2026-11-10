import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { defineConfig, type Plugin } from 'vite';

// The admin console is mounted at different paths depending on the
// deployment target -- /central/admin/ in local dev, docker-local, and
// path-based prod (see deploy.md / nginx routing); the docroot itself for a
// domain-hosted console (e.g. k8s, see #222). A relative base means the same
// dist/ works unmodified at either mount point: asset URLs baked into
// index.html resolve against wherever the HTML itself was served from,
// instead of assuming a fixed absolute path.
const BASE_PATH = './';

function distIndexHtmlPlugin(partner: boolean): Plugin {
  return {
    name: 'dist-index-html',
    async closeBundle() {
      const projectRoot = process.cwd();
      const outputDir = path.join(projectRoot, 'dist');
      // Each console is its own build (see `build` below): index.html belongs to the operator's, partner.html to the partner's.
      const page = partner
        ? { source: 'partner.html', output: 'partner.html', scriptMarker: '/src/partner/main.ts', bundle: 'versola-partner.js' }
        : { source: 'index.html', output: 'index.html', scriptMarker: '/src/index.ts', bundle: 'versola-admin.js' };
      const source = await readFile(path.join(projectRoot, page.source), 'utf8');

      // Guard against the page being reformatted later without updating
      // these markers — a silent no-op replace() would otherwise ship a
      // dist page with a dangling /src/... reference or an
      // un-prefixed favicon path, and nothing would fail the build to say so.
      const faviconMarker = 'href="/favicon-light.svg"';
      if (!source.includes(page.scriptMarker)) {
        throw new Error(`dist-index-html: expected to find "${page.scriptMarker}" in ${page.source} — update this plugin if ${page.source}'s shape changed.`);
      }
      if (!source.includes(faviconMarker)) {
        throw new Error(`dist-index-html: expected to find '${faviconMarker}' in ${page.source} — update this plugin if ${page.source}'s shape changed.`);
      }

      const html = source
        .replace(page.scriptMarker, `${BASE_PATH}${page.bundle}`)
        .replace(faviconMarker, `href="${BASE_PATH}favicon-light.svg"`);

      await mkdir(outputDir, { recursive: true });
      await writeFile(path.join(outputDir, page.output), html, 'utf8');
    },
  };
}

const isPlaywright = process.env.PLAYWRIGHT === 'true';

export default defineConfig(({ command, isPreview, mode }) => {
  // `vite build` builds the operator console, `vite build --mode partner` the partner console (#549 decision 5).
  const partner = mode === 'partner';
  return {
  // Prefix in production builds, and also when previewing that build
  // (`vite preview` still reports command === 'serve', with isPreview=true
  // as the only signal) — otherwise `npm run preview` serves dist/ at "/"
  // while its index.html references /admin/... assets, and everything 404s.
  // The plain dev server (npm run dev) is the only case that stays at "/".
  base: command === 'build' || isPreview ? BASE_PATH : '/',
  plugins: [distIndexHtmlPlugin(partner)],
  // The partner entry installs an in-page mock in the plain dev server only; Playwright routes the API itself.
  define: { 'import.meta.env.PLAYWRIGHT': JSON.stringify(isPlaywright) },
  build: {
    // Two builds, one package: each console is a single self-contained file, so the partner's bundle shares no chunk with
    // the operator's and holds nothing but what its own entry reaches (checked on the output by scripts/check-bundles.mjs).
    // The operator's build empties dist; the partner's adds to it.
    emptyOutDir: !partner,
    lib: {
      entry: partner ? { 'versola-partner': 'src/partner/main.ts' } : { 'versola-admin': 'src/index.ts' },
      formats: ['es'],
      fileName: (_format, entryName) => `${entryName}.js`,
    },
    rollupOptions: {
      external: [],
      output: { inlineDynamicImports: true },
    },
    // Copy public assets
    copyPublicDir: !partner,
  },
  server: {
    port: 3000,
    open: !isPlaywright,
    // The console calls /central/... (see central-api.ts), but edge itself only
    // speaks /resources/{resourceId}/... and /permissions/me. In production
    // nginx rewrites between the two; these entries mirror those rewrites so a
    // local edge sees exactly the same requests it would in prod.
    //
    // Keys starting with "^" are treated as regular expressions and are matched
    // in declaration order, so the /permissions/me special case must come
    // first — otherwise the general /central/ rule would swallow it and rewrite
    // it to /resources/central/permissions/me, which edge doesn't serve.
    //
    // Each rewrite is a replace() on the matched prefix rather than a constant,
    // because the path handed to rewrite() still carries the query string
    // (/permissions/me is called with ?resource=central) and returning a
    // constant would silently drop it.
    proxy: {
      '^/central/permissions/me': {
        target: 'http://localhost:9005',
        changeOrigin: true,
        rewrite: (path: string) => path.replace(/^\/central\/permissions\/me/, '/permissions/me'),
      },
      '^/central/': {
        target: 'http://localhost:9005',
        changeOrigin: true,
        rewrite: (path: string) => path.replace(/^\/central\//, '/resources/central/'),
      },
      // edge's own routes, still reachable directly. The console no longer uses
      // them, but they mirror what nginx keeps exposed for other edge clients.
      '/resources': {
        target: 'http://localhost:9005',
        changeOrigin: true,
      },
      '/permissions': {
        target: 'http://localhost:9005',
        changeOrigin: true,
      },
      // A 401 from the console redirects the browser to /login/<presetId> (see
      // central-api.ts's redirectToLogin). Without this proxy that top-level
      // navigation hits Vite's own dev server, which has no such route and
      // falls back to serving index.html — reloading the console, which
      // immediately 401s and redirects again, looping forever instead of ever
      // reaching edge to set the EDGE_SESSION cookie.
      '/login': {
        target: 'http://localhost:9005',
        changeOrigin: true,
      },
      '/complete': {
        target: 'http://localhost:9005',
        changeOrigin: true,
      },
      // The admin shell's "Log out" link navigates to /logout/<presetId> (see navigation.ts)
      // — edge's preset-scoped logout route. Keep this more specific proxy before the generic
      // auth logout route below.
      '/logout/': {
        target: 'http://localhost:9005',
        changeOrigin: true,
      },
      // The account settings form has no knowledge of an OAuth preset. Its generic /logout
      // navigation must reach auth's own logout page directly.
      '/logout': {
        target: 'http://localhost:9003',
        changeOrigin: true,
      },
    },
  },
  // Public directory for static assets
  publicDir: 'public',
};
});
