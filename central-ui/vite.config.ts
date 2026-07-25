import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { defineConfig, type Plugin } from 'vite';

// The admin console is served at https://id.versola.kz/admin/ in production
// (see deploy.md / nginx routing), so every asset URL baked into the built
// index.html must be prefixed with this path — otherwise the browser looks
// for them at the domain root and gets a 404.
const BASE_PATH = '/admin/';

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
    proxy: {
      '/resources': {
        target: 'http://localhost:9005',
        changeOrigin: true,
      },
      '/permissions': {
        target: 'http://localhost:9005',
        changeOrigin: true,
      },
    },
  },
  // Public directory for static assets
  publicDir: 'public',
}));

