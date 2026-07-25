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
      const html = source
        .replace('/src/index.ts', `${BASE_PATH}versola-admin.js`)
        .replace('href="/logo-shield.svg"', `href="${BASE_PATH}logo-shield.svg"`);

      await mkdir(outputDir, { recursive: true });
      await writeFile(outputPath, html, 'utf8');
    },
  };
}

const isPlaywright = process.env.PLAYWRIGHT === 'true';

export default defineConfig(({ command }) => ({
  // Only prefix in production builds — the local dev server (npm run dev)
  // still serves from the root so `localhost:3000/` keeps working as before.
  base: command === 'build' ? BASE_PATH : '/',
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

