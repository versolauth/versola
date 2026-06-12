import { build } from 'esbuild';
import { solidPlugin } from 'esbuild-plugin-solid';
import { writeFile, copyFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const formsSrcDir = resolve(here, '../forms');
const outDir = resolve(here, '../../central/src/main/resources/forms');

const forms = ['credential', 'otp', 'password'];
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
  await copyFile(entry, resolve(outDir, `${id}.tsx`));
  await copyFile(resolve(formDir, `${id}.i18n.json`), resolve(outDir, `${id}.i18n.json`));
  await copyFile(resolve(formDir, `${id}.css`), resolve(outDir, `${id}.css`));
  console.log(`Built ${id}: ${compiled.length} bytes`);
}

for (const asset of sharedAssets) {
  await copyFile(resolve(formsSrcDir, asset), resolve(outDir, asset));
  console.log(`Copied ${asset}`);
}
