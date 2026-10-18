#!/usr/bin/env node
/**
 * Converts the screenshots `tests/docs-screenshots.spec.ts` generated to AVIF and writes them into a
 * versola-website checkout, then checks that the manifest and the docs pages agree.
 *
 *   node scripts/sync-docs-screenshots.mjs <website-dir> [--dry-run] [--prune] [--source <dir>]
 *
 * The manifest (`tests/docs-screenshots.manifest.ts`) is the contract: every image a docs page
 * references must be an entry, and every entry must be referenced by a page, in either language.
 * The script fails when either side drifts, and when a manifest entry has no generated file (the
 * spec was not run, or a scene failed). Files in the website folder that no page references are
 * reported and left alone unless `--prune` is given. `--dry-run` prints the report without copying.
 *
 * Only the light theme ships (the docs site has a light theme only; `Screenshot.astro` never picks the
 * dark twin when a light one exists), as `<name>-light.avif`. AVIF at quality 60 with full-resolution
 * chroma keeps UI text crisp and is about half the size of the optimised PNG. Pass `--with-dark` to also
 * write `<name>.avif` from the dark capture. `sharp` is taken from the website checkout's node_modules.
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, unlinkSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const flags = new Set(args.filter(arg => arg.startsWith('--')));
const positional = args.filter(arg => !arg.startsWith('--'));
const sourceFlag = args.indexOf('--source');
const source = resolve(sourceFlag >= 0 ? args[sourceFlag + 1] : join(here, '..', 'test-results', 'docs-screenshots'));
if (sourceFlag >= 0) positional.splice(positional.indexOf(args[sourceFlag + 1]), 1);
const website = positional[0] && resolve(positional[0]);
const dryRun = flags.has('--dry-run');
const prune = flags.has('--prune');
const withDark = flags.has('--with-dark');

if (!website || !existsSync(join(website, 'src', 'content', 'docs'))) {
  console.error('usage: sync-docs-screenshots.mjs <website-dir> [--dry-run] [--prune] [--with-dark] [--source <dir>]');
  console.error('<website-dir> must be a versola-website checkout (src/content/docs/ was not found).');
  process.exit(2);
}

// Node strips the manifest's types itself; its runtime imports are node packages only.
const { docsShots } = await import(pathToFileURL(join(here, '..', 'tests', 'docs-screenshots.manifest.ts')).href);
const manifestNames = new Set(docsShots.map(shot => shot.name));
const duplicates = docsShots.map(shot => shot.name).filter((name, index, all) => all.indexOf(name) !== index);

function walk(dir, keep) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...walk(path, keep));
    else if (keep(path)) out.push(path);
  }
  return out;
}

// What the pages reference: `src="console/x.png"` on the Screenshot component, the light twin is
// implied (Screenshot.astro picks `x-light.png` when it exists).
const docsDir = join(website, 'src', 'content', 'docs');
const referencedBy = new Map();
for (const page of walk(docsDir, path => /\.mdx?$/.test(path))) {
  for (const match of readFileSync(page, 'utf8').matchAll(/src=["']((?:console|entities|login)\/[^"']+)\.png["']/g)) {
    const pages = referencedBy.get(match[1]) ?? new Set();
    pages.add(relative(docsDir, page));
    referencedBy.set(match[1], pages);
  }
}

const imgDir = join(website, 'public', 'img', 'docs');
const baseName = file => relative(imgDir, file).replace(/\.avif$/, '').replace(/-light$/, '');
const siteFiles = existsSync(imgDir) ? walk(imgDir, path => path.endsWith('.avif')) : [];

const unlisted = [...referencedBy.keys()].filter(name => !manifestNames.has(name)).sort();
const unreferenced = [...manifestNames].filter(name => !referencedBy.has(name)).sort();
const orphans = siteFiles.filter(file => !referencedBy.has(baseName(file))).sort();

const generated = name => [`${name}.png`, `${name}-light.png`].map(file => join(source, file));
const missing = [...manifestNames].filter(name => generated(name).some(file => !existsSync(file))).sort();

let copied = 0;
if (!dryRun) {
  const sharp = createRequire(join(website, 'package.json'))('sharp');
  for (const name of manifestNames) {
    if (missing.includes(name)) continue;
    for (const file of generated(name)) {
      if (!withDark && !file.endsWith('-light.png')) continue;
      const target = join(imgDir, relative(source, file).replace(/\.png$/, '.avif'));
      mkdirSync(dirname(target), { recursive: true });
      await sharp(file).avif({ quality: 60, effort: 6, chromaSubsampling: '4:4:4' }).toFile(target);
      copied++;
    }
  }
}

const list = (title, items, render = item => item) => {
  if (items.length === 0) return;
  console.log(`\n${title} (${items.length}):`);
  for (const item of items) console.log(`  ${render(item)}`);
};

console.log(`manifest: ${manifestNames.size} images, pages reference ${referencedBy.size}, website folder holds ${siteFiles.length} files`);
console.log(dryRun ? 'dry run: nothing written' : `wrote ${copied} AVIF files into ${relative(process.cwd(), imgDir) || '.'}`);
list('duplicate manifest names', [...new Set(duplicates)]);
list('(a) referenced by a page but not in the manifest', unlisted, name => `${name}  <- ${[...referencedBy.get(name)].join(', ')}`);
list('(b) in the manifest but referenced by no page', unreferenced);
list('generated file missing (run `npm run docs:screenshots` first)', missing, name => `${name}  (${relative(process.cwd(), source)})`);
list(prune ? '(c) unreferenced files in the website folder, deleted' : '(c) unreferenced files in the website folder (left alone; --prune deletes them)', orphans, file => relative(imgDir, file));
if (prune && !dryRun) for (const file of orphans) unlinkSync(file);

const failed = unlisted.length > 0 || unreferenced.length > 0 || missing.length > 0 || duplicates.length > 0;
console.log(failed ? '\nFAILED: the manifest and the docs pages disagree' : '\nOK');
process.exit(failed ? 1 : 0);
