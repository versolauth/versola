import { defineConfig, type Plugin } from 'vite';

/**
 * The console with its backend swapped for the in-page mock (src/demo): the same index.html, so the
 * theme tokens and fonts stay defined in one place, pointed at the demo entry instead of the
 * library one. Built as a plain static app, to be mounted at any path.
 */
function demoEntryPlugin(): Plugin {
  return {
    name: 'demo-entry',
    // 'pre': Vite rewrites the script's src to a built asset in its own pass, and by then there is nothing to match.
    transformIndexHtml: { order: 'pre', handler(html) {
      if (!html.includes('/src/index.ts')) {
        throw new Error('demo-entry: index.html no longer references /src/index.ts; update this plugin.');
      }
      // The demo opens light whatever the visitor's device or earlier choice says, and keeps its own choice
      // in memory: this origin also hosts the marketing site, whose stored theme it must neither read nor change.
      const themeKey = `<script>
      (function () {
        var theme = 'light', get = Storage.prototype.getItem, set = Storage.prototype.setItem;
        Storage.prototype.getItem = function (k) { return k === 'versola-theme' ? theme : get.call(this, k); };
        Storage.prototype.setItem = function (k, v) { if (k === 'versola-theme') theme = v; else set.call(this, k, v); };
      })();
    </script>`;
      if (!html.includes('<head>')) {
        throw new Error('demo-entry: index.html has no <head>; update this plugin.');
      }
      return html.replace('<head>', `<head>${themeKey}`).replace('/src/index.ts', '/src/demo/main.ts').replace('<title>Versola Central</title>', '<title>Versola Console: live demo</title>');
    } },
  };
}

export default defineConfig({
  base: './',
  plugins: [demoEntryPlugin()],
  build: {
    outDir: 'dist-demo',
    emptyOutDir: true,
    target: 'es2022',
  },
});
