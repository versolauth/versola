import { build } from 'esbuild';
import { solidPlugin } from 'esbuild-plugin-solid';
import { writeFile, copyFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const formsSrcDir = resolve(here, '../forms');
const outDir = resolve(here, '../../central/src/main/resources/forms');

// outDir is gitignored (generated output), so on a fresh checkout it doesn't exist yet.
// writeFile/copyFile below don't create parent directories, so without this the first
// write fails with ENOENT — this is exactly what would happen in CI right now.
await mkdir(outDir, { recursive: true });

const forms = ['credential', 'otp', 'password', 'access-denied', 'conversation-expired', 'service-unavailable', 'passkey-enroll', 'set-password', 'confirm-logout', 'signed-out', 'consent', 'auth-settings'];
const sharedAssets = ['common.css'];

for (const id of forms) {
  const formDir = resolve(formsSrcDir, id);
  const entry = resolve(formDir, `${id}.tsx`);
  const result = await build({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'iife',
    minify: true,
    target: 'es2019',
    plugins: [solidPlugin()],
  });
  const compiled = result.outputFiles[0].text;
  await writeFile(resolve(outDir, `${id}.js`), compiled, 'utf8');
  // The stored source is what the console shows and forks. It imports ../shared/*, which nothing stores,
  // so the shared modules are inlined: readable (not minified), JSX kept, only solid-js left as an import.
  // Type annotations are dropped by esbuild; jsCompiled above is what runs.
  const source = await build({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'esm',
    jsx: 'preserve',
    target: 'esnext',
    external: ['solid-js', 'solid-js/web'],
  });
  await writeFile(resolve(outDir, `${id}.tsx`), source.outputFiles[0].text, 'utf8');
  await copyFile(resolve(formDir, `${id}.i18n.json`), resolve(outDir, `${id}.i18n.json`));
  await copyFile(resolve(formDir, `${id}.css`), resolve(outDir, `${id}.css`));
  console.log(`Built ${id}: ${compiled.length} bytes`);
}

for (const asset of sharedAssets) {
  await copyFile(resolve(formsSrcDir, asset), resolve(outDir, asset));
  console.log(`Copied ${asset}`);
}
