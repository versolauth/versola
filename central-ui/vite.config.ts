import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { defineConfig, type Plugin } from 'vite';

function distIndexHtmlPlugin(): Plugin {
  return {
    name: 'dist-index-html',
    async closeBundle() {
      const projectRoot = process.cwd();
      const sourcePath = path.join(projectRoot, 'index.html');
      const outputDir = path.join(projectRoot, 'dist');
      const outputPath = path.join(outputDir, 'index.html');
      const source = await readFile(sourcePath, 'utf8');
      const html = source.replace('/src/index.ts', './versola-admin.js');

      await mkdir(outputDir, { recursive: true });
      await writeFile(outputPath, html, 'utf8');
    },
  };
}

const isPlaywright = process.env.PLAYWRIGHT === 'true';

export default defineConfig({
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
      '/v1': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
  // Public directory for static assets
  publicDir: 'public',
});

