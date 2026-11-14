// Walks the partner console against a REAL partner edge in a headless browser, signs in through the operator's auth, and records what the
// edge answered, so the golden files of tests/partner-console/golden can be captured from a real run (see the README there).
//
//   EDGE_URL=http://localhost:9121 EMAIL=<team admin's email> CODE=<the code auth accepts> node scripts/walk-partner-edge.mjs [outDir]
//
// A staged stack's console client redirects to an https host that does not exist; the redirect is caught and replayed against the edge's own
// /complete, which is what a real DNS name would have done. Screenshots, the console's errors (a CSP violation among them) and every
// /partner/api answer are written to outDir (default: a temporary directory, printed at the end).
import { chromium } from '@playwright/test';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const edge = process.env.EDGE_URL;
const email = process.env.EMAIL;
const code = process.env.CODE;
if (!edge || !email || !code) {
  console.error('usage: EDGE_URL=<edge origin> EMAIL=<team admin> CODE=<sign-in code> node scripts/walk-partner-edge.mjs [outDir]');
  process.exit(2);
}
const out = process.argv[2] ?? await mkdtemp(path.join(os.tmpdir(), 'partner-walk-'));
await mkdir(out, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'en-US', timezoneId: 'UTC' });
const page = await context.newPage();
const problems = [];
const answers = [];
page.on('console', message => { if (message.type() === 'error') problems.push(`console: ${message.text()}`); });
page.on('pageerror', error => problems.push(`pageerror: ${error.message}`));
if (process.env.TRACE) page.on('requestfailed', request => console.log(`FAILED ${request.method()} ${request.url().slice(0, 160)} ${request.failure()?.errorText}`));
if (process.env.TRACE) page.on('response', response => { if (response.request().isNavigationRequest()) console.log(`${response.status()} ${response.url().slice(0, 110)}`); });
page.on('response', async response => {
  const url = new URL(response.url());
  if (!url.pathname.startsWith('/partner/api')) return;
  const body = await response.text().catch(() => '');
  answers.push({ method: response.request().method(), path: url.pathname + url.search, status: response.status(), body });
});

// The staged console client's redirect URI is an https host nobody resolves: the browser fails on it, and the failed request is the redirect
// that auth sent, with the code and the state. It is replayed against the edge's own /complete below.
const redirected = page.waitForEvent('requestfailed', { predicate: request => /^console\./.test(new URL(request.url()).hostname), timeout: 60000 });

const shot = async name => page.screenshot({ path: path.join(out, `${name}.png`) });

await page.goto(`${edge}/partner/login`);
await page.locator('input[type="email"]').fill(email);
await page.getByRole('button', { name: 'Continue' }).click();
await page.getByText('Enter Code').waitFor();
await page.locator('input:not([type=hidden])').first().click();
await page.keyboard.type(code);
// A console client that asks for consent (the staged partner's does) shows the Allow screen before it redirects.
await page.getByRole('button', { name: 'Allow' }).click({ timeout: 8000 }).catch(() => undefined);
const failed = await redirected;
await page.goto(`${edge}/complete${new URL(failed.url()).search}`).catch(() => undefined);
await page.waitForTimeout(500);
await page.goto(`${edge}/partner/`);
await page.locator('versola-partner-console').waitFor({ timeout: 15000 }).catch(async () => {
  await shot('stuck');
  console.log(`the console did not appear; the page is at ${page.url()}`);
  console.log(problems.slice(0, 10).join('\n'));
  await browser.close();
  process.exit(1);
});

const views = ['overview', 'clients', 'resources', 'edges', 'revocation', 'audit', 'team', 'settings'];
for (const view of views) {
  await page.goto(`${edge}/partner/?view=${view}`);
  await page.waitForTimeout(1500);
  await shot(view);
}


// FLOWS=1 also drives what writes: the wizard, an invitation, a second edge and its token, and a revocation. The partner of a staged stack
// is limited (see its /me), so the values below keep inside those limits; whatever the edge refuses is recorded with its answer.
if (process.env.FLOWS) {
  const domain = process.env.REDIRECT_DOMAIN ?? 'e2e-partner-faf53869a4.example';
  const stamp = Date.now().toString(36);
  const step = async (name, action) => {
    try { await action(); } catch (error) { problems.push(`${name}: ${String(error.message).split('\n')[0]}`); }
    await page.waitForTimeout(700);
    await shot(`flow-${name}`);
  };

  await step('wizard', async () => {
    await page.goto(`${edge}/partner/?view=clients&new=1`);
    await page.getByRole('button', { name: /^Web app/ }).click();
    await page.getByRole('button', { name: 'Continue to basics' }).click();
    await page.getByLabel('Client ID *').fill(`walk-${stamp}`);
    await page.getByLabel('Display name *').fill(`Walk ${stamp}`);
    await page.getByLabel('Redirect URIs *').fill(`https://walk.${domain}/callback`);
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await page.getByLabel('Access token, minutes *').fill('5');
    await page.getByRole('button', { name: 'Continue to review' }).click();
    await page.getByRole('button', { name: 'Submit for approval' }).click();
    await page.getByRole('status').first().waitFor({ timeout: 8000 });
  });
  await step('invite', async () => {
    await page.goto(`${edge}/partner/?view=team`);
    await page.getByRole('button', { name: '+ Invite' }).click();
    await page.getByLabel('Phone or email *').fill(`walk-${stamp}@partner.example`);
    await page.getByRole('radio', { name: /viewer/ }).check();
    await page.getByRole('button', { name: 'Create invitation' }).click();
  });
  await step('edge-token', async () => {
    // The staged partner may already hold its limit of edges; a waiting edge is enough to issue, revoke and issue its enrollment token again.
    await page.goto(`${edge}/partner/?view=edges`);
    await page.getByRole('button', { name: /^Open edge/ }).first().click();
    const certificate = page.getByRole('region', { name: 'Certificate' });
    await certificate.getByRole('button', { name: 'Revoke token' }).click().catch(() => undefined);
    await page.getByRole('dialog', { name: 'Revoke enrollment token' }).getByRole('button', { name: 'Revoke token' }).click().catch(() => undefined);
    await page.waitForTimeout(500);
    await certificate.getByRole('button', { name: /Issue (enrollment )?token/ }).click();
    await page.getByRole('button', { name: 'Copy token' }).waitFor({ timeout: 8000 });
  });
  await step('revoke', async () => {
    // A team member other than the signed-in admin (who would end their own session): by user, then by an id the edge does not know.
    const staff = answers.findLast(a => a.method === 'GET' && a.path === '/partner/api/staff' && a.status === 200);
    const viewer = JSON.parse(staff.body).staff.find(member => member.level === 'viewer' && member.subject);
    await page.goto(`${edge}/partner/?view=revocation`);
    const end = async (what, value) => {
      await page.getByRole('button', { name: what, exact: true }).click().catch(() => undefined);
      await page.getByLabel(/\*$/).first().fill(value);
      await page.getByRole('button', { name: 'End sessions', exact: true }).click();
      await page.getByRole('dialog', { name: 'End sessions' }).getByRole('button', { name: 'End sessions' }).click();
      await page.getByRole('status').or(page.getByRole('alert')).first().waitFor({ timeout: 8000 });
      await page.waitForTimeout(400);
    };
    await end('A user', viewer.subject);
    await shot('flow-revoke-user');
    await end('A session', 'sid_unknown');
    await shot('flow-revoke-sid');
    await end('A refresh family', 'fam_unknown');
  });
}

// The golden files of tests/partner-console/golden, from what this run saw. The one-time secrets are replaced; everything else is as sent.
const redact = (name, body) => {
  if (name === 'edge-created' || name === 'enrollment-token') body = { ...body, token: 'REDACTED-ENROLLMENT-TOKEN' };
  return body;
};
const goldenSources = {
  'me': ['GET', /^\/partner\/api\/me$/, 200], 'clients': ['GET', /^\/partner\/api\/clients$/, 200], 'edges': ['GET', /^\/partner\/api\/edges$/, 200],
  'resources': ['GET', /^\/partner\/api\/resources$/, 200], 'staff': ['GET', /^\/partner\/api\/staff$/, 200], 'settings': ['GET', /^\/partner\/api\/settings$/, 200],
  'audit': ['GET', /^\/partner\/api\/audit(\?|$)/, 200], 'client-created': ['POST', /^\/partner\/api\/clients$/, 202], 'edge-created': ['POST', /^\/partner\/api\/edges$/, 201],
  'enrollment-token': ['POST', /^\/partner\/api\/edges\/[^/]+\/enrollment-tokens$/, 201],
};
await mkdir(path.join(out, 'golden'), { recursive: true });
for (const [name, [method, pattern, status]] of Object.entries(goldenSources)) {
  const answer = answers.findLast(a => a.method === method && pattern.test(a.path) && a.status === status);
  if (answer) await writeFile(path.join(out, 'golden', `${name}.json`), `${JSON.stringify(redact(name, JSON.parse(answer.body)), null, 2)}\n`);
}

await writeFile(path.join(out, 'answers.json'), JSON.stringify(answers, null, 2));
await writeFile(path.join(out, 'problems.txt'), problems.join('\n'));
console.log(`${answers.length} answers, ${problems.length} problems, written to ${out}`);
for (const line of problems.slice(0, 20)) console.log(line);
await browser.close();
