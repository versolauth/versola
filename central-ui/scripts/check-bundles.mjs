// Checks the build output of the two consoles (#549 decision 5): `npm run build` leaves exactly one file per console, and the
// partner console's file holds nothing of the operator's API. The guarantee is on what ships, not on what the sources import:
// a shared util that carries an operator path in a string would pass an import check and fail here.
//
//   node scripts/check-bundles.mjs [dist]
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';

const dist = path.resolve(process.argv[2] ?? 'dist');
// The partner page's pre-paint theme script is a file of its own: the edge's CSP blocks an inline script.
const EXPECTED = ['versola-admin.js', 'versola-partner-theme.js', 'versola-partner.js'];

// What a partner's browser must never be able to reach: the operator's API, its user lookups and its permission catalog.
const FORBIDDEN = ['/configuration/', '/users/', 'permissions/me', '/central/', 'versola-admin'];

// The in-page mock of the partner API is for the dev server only: a production file that still carries it would answer for the edge.
const MOCK_MARKERS = ['No mock handler for', 'installMockFetch', 'partner-default', 'pilot-pay-web'];

const files = (await readdir(dist)).filter(name => name.endsWith('.js')).sort();
const problems = [];
if (JSON.stringify(files) !== JSON.stringify(EXPECTED)) {
  problems.push(`dist holds ${JSON.stringify(files)}, expected exactly ${JSON.stringify(EXPECTED)}: a shared chunk means the partner bundle loads code the operator's build also holds.`);
}

// The partner file and every file it imports, however the bundler split them.
const seen = new Set();
const queue = ['versola-partner.js'];
while (queue.length > 0) {
  const name = queue.pop();
  if (seen.has(name)) continue;
  seen.add(name);
  let code;
  try {
    code = await readFile(path.join(dist, name), 'utf8');
  } catch {
    problems.push(`${name} is imported but missing from dist`);
    continue;
  }
  for (const needle of [...FORBIDDEN, ...MOCK_MARKERS]) {
    const at = code.indexOf(needle);
    if (at >= 0) problems.push(`${name} contains "${needle}": …${code.slice(Math.max(0, at - 40), at + 60).replace(/\s+/g, ' ')}…`);
  }
  for (const match of code.matchAll(/(?:from\s*|import\s*\(\s*)["']\.\/([^"']+\.js)["']/g)) queue.push(match[1]);
}

// The partner page must run under the edge's CSP: no inline script, no third-party resource.
const page = await readFile(path.join(dist, 'partner.html'), 'utf8');
if (/<script(?![^>]*\bsrc=)[^>]*>/.test(page)) problems.push('partner.html has an inline <script>: the edge\'s Content-Security-Policy blocks it');
for (const external of page.match(/(?:href|src)="https?:\/\/[^"]+"/g) ?? []) problems.push(`partner.html loads ${external}: the edge's Content-Security-Policy allows its own origin only`);

if (problems.length > 0) {
  console.error(`Bundle check failed:\n- ${problems.join('\n- ')}`);
  process.exit(1);
}
console.log(`Bundle check passed: ${[...seen].join(', ')} hold no operator paths.`);
