// Captures the answers of a real partner edge as the golden files of tests/partner-console/golden (see the README there).
//
//   EDGE_URL=https://edge.partner.example.com COOKIE='EDGE_SESSION=…' node scripts/capture-golden.mjs
//
// Reads only (GET); the answers that need a write (a created client, a created edge) are not captured here: make the call by hand
// with the session and save the body as client-created.json or edge-created.json. The token in such a body is a
// secret: blank them before the file is committed.
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const base = process.env.EDGE_URL;
const cookie = process.env.COOKIE;
if (!base || !cookie) {
  console.error('usage: EDGE_URL=<edge origin> COOKIE=\'EDGE_SESSION=…\' node scripts/capture-golden.mjs');
  process.exit(2);
}
const out = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'tests', 'partner-console', 'golden');
const reads = { 'me.json': '/me', 'clients.json': '/clients', 'resources.json': '/resources', 'edges.json': '/edges', 'audit.json': '/audit', 'staff.json': '/staff', 'settings.json': '/settings' };

for (const [file, route] of Object.entries(reads)) {
  const response = await fetch(`${base}/partner/api${route}`, { headers: { Cookie: cookie, Accept: 'application/json' } });
  const text = await response.text();
  if (!response.ok) {
    console.error(`${route}: ${response.status} ${text.slice(0, 200)}`);
    continue;
  }
  await writeFile(path.join(out, file), `${JSON.stringify(JSON.parse(text), null, 2)}\n`);
  console.log(`captured ${file}`);
}
