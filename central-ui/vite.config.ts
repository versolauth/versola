import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { defineConfig, type Plugin } from 'vite';

// The admin console is served at https://id.versola.kz/central/admin/ in
// production (see deploy.md / nginx routing), so every asset URL baked into
// the built index.html must be prefixed with this path — otherwise the browser
// looks for them at the domain root and gets a 404.
//
// The /central segment isn't cosmetic: the EDGE_SESSION cookie is scoped to
// that path so each app behind edge gets its own session, and a cookie is only
// sent to URLs beneath its path. Both the console's assets and its API calls
// therefore have to live under the same prefix — see central-api.ts.
const BASE_PATH = '/central/admin/';

function distIndexHtmlPlugin(): Plugin {
  return {
    name: 'dist-index-html',
    async closeBundle() {
      const projectRoot = process.cwd();
      const sourcePath = path.join(projectRoot, 'index.html');
      const outputDir = path.join(projectRoot, 'dist');
      const outputPath = path.join(outputDir, 'index.html');
      const source = await readFile(sourcePath, 'utf8');

      // Guard against index.html being reformatted later without updating
      // these markers — a silent no-op replace() would otherwise ship a
      // dist/index.html with a dangling /src/index.ts reference or an
      // un-prefixed favicon path, and nothing would fail the build to say so.
      const scriptMarker = '/src/index.ts';
      const faviconMarker = 'href="/logo-shield.svg"';
      if (!source.includes(scriptMarker)) {
        throw new Error(`dist-index-html: expected to find "${scriptMarker}" in index.html — update this plugin if index.html's shape changed.`);
      }
      if (!source.includes(faviconMarker)) {
        throw new Error(`dist-index-html: expected to find '${faviconMarker}' in index.html — update this plugin if index.html's shape changed.`);
      }

      const html = source
        .replace(scriptMarker, `${BASE_PATH}versola-admin.js`)
        .replace(faviconMarker, `href="${BASE_PATH}logo-shield.svg"`);

      await mkdir(outputDir, { recursive: true });
      await writeFile(outputPath, html, 'utf8');
    },
  };
}

const isPlaywright = process.env.PLAYWRIGHT === 'true';

export default defineConfig(({ command, isPreview }) => ({
  // Prefix in production builds, and also when previewing that build
  // (`vite preview` still reports command === 'serve', with isPreview=true
  // as the only signal) — otherwise `npm run preview` serves dist/ at "/"
  // while its index.html references /admin/... assets, and everything 404s.
  // The plain dev server (npm run dev) is the only case that stays at "/".
  base: command === 'build' || isPreview ? BASE_PATH : '/',
  plugins: [distIndexHtmlPlugin()],
  build: {
    lib: {
      entry: 'src/index.ts',
      formats: ['es'],
      fileName: 'versola-admin',
    },
    rollupOptions: {
      external: [],
    },
    // Copy public assets
    copyPublicDir: true,
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
    },
  },
  // Public directory for static assets
  publicDir: 'public',
}));

