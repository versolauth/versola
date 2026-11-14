import { describe, expect, it } from 'vitest';

/**
 * A partner has no way to choose, or see, "first-party" as an edge, a group kind, an owner or an edge binding (the edge fixes
 * the group from the certificate that names the partner), so none of the operator's words for it may be in what the partner
 * console ships. Three layers: the sources of `src/partner` and of every file it imports (here), the built partner file (here,
 * after `npm run build`, and in `scripts/check-bundles.mjs`, the CI step), and the DOM (`tests/partner-console/no-first-party.spec.ts`).
 *
 * `types/index.ts` is left out of the source walk: it holds the operator's types (`groupKind` of an Edge, `partnerId` of a client),
 * which the partner console imports for its shared limits and the compiler erases. The built file shows whether any of it got in.
 */
const FORBIDDEN = [
  /first[\s_-]?party/i,
  /group[\s_-]?kind/i,
  /who owns the client/i,
  /decides consent, limits and which edges serve the client/i,
  /renderOwnerSection/,
];

// The glob of the parent folder leaves out this folder's own files, so it is read by a second glob and renamed to the same `../partner/...` keys.
const others = import.meta.glob<string>('../**/*.ts', { query: '?raw', import: 'default', eager: true });
const own = import.meta.glob<string>('./**/*.ts', { query: '?raw', import: 'default', eager: true });
const sources: Record<string, string> = { ...others, ...Object.fromEntries(Object.entries(own).map(([key, source]) => [key.replace(/^\.\//, '../partner/'), source])) };
const UNTYPED = new Set(['../types/index.ts']);

const stripComments = (source: string) => source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`\\])\/\/.*$/gm, '$1');

function resolve(from: string, specifier: string): string | null {
  if (!specifier.startsWith('.')) return null;
  const parts = from.split('/').slice(0, -1);
  for (const part of specifier.split('/')) {
    if (part === '..') parts.pop();
    else if (part !== '.') parts.push(part);
  }
  const base = parts.join('/');
  return [`${base}.ts`, `${base}/index.ts`, base].find(candidate => candidate in sources) ?? null;
}

/** `src/partner/main.ts` and everything it imports, however deep (type-only imports are erased, so they are not followed). */
function closure(entry: string): string[] {
  const seen = new Set<string>();
  const queue = [entry];
  while (queue.length > 0) {
    const file = queue.pop()!;
    if (seen.has(file)) continue;
    seen.add(file);
    for (const match of stripComments(sources[file]).matchAll(/(?:^|\n)\s*(?:import|export)\s+(?!type\b)[^;]*?from\s+'([^']+)'|import\(\s*'([^']+)'\s*\)|^import\s+'([^']+)'/gm)) {
      const target = resolve(file, match[1] ?? match[2] ?? match[3]);
      if (target) queue.push(target);
    }
  }
  return [...seen].sort();
}

describe('the partner console never offers first-party', () => {
  const files = closure('../partner/main.ts').filter(file => !UNTYPED.has(file) && !file.endsWith('.test.ts'));

  it('walks the partner console and the shared files it imports', () => {
    expect(files.some(file => file.startsWith('../partner/views/partner-edges'))).toBe(true);
    expect(files.some(file => file.startsWith('../components/side-panel'))).toBe(true);
    expect(files.length).toBeGreaterThan(20);
  });

  it('does not import the operator\'s client wizard, edges list or owner step', () => {
    expect(files.filter(file => /client-form|client-kind-step|edges-list|partners-list|partner-wizard|partner-approvals/.test(file))).toEqual([]);
  });

  for (const file of files) {
    it(`${file} says nothing of first-party, a group kind or an owner step`, () => {
      const code = stripComments(sources[file]);
      expect(FORBIDDEN.filter(pattern => pattern.test(code)).map(String)).toEqual([]);
    });
  }

  it('the pattern list catches what it is for', () => {
    for (const sample of ['First-party', 'first_party', 'groupKind', 'group_kind', 'Who owns the client']) {
      expect(FORBIDDEN.some(pattern => pattern.test(sample)), sample).toBe(true);
    }
    expect(FORBIDDEN.some(pattern => pattern.test('Your group pilot-pay'))).toBe(false);
  });

  // `npm run build` writes dist/; the same words are checked on it in CI by `npm run check:bundles`. Without a build there is nothing to read and the test is skipped.
  const built = import.meta.glob<string>('../../dist/versola-partner*.js', { query: '?raw', import: 'default', eager: true });
  it.skipIf(Object.keys(built).length === 0)('the built partner file says nothing of them either', () => {
    expect(Object.keys(built).some(name => name.endsWith('/versola-partner.js'))).toBe(true);
    for (const [name, code] of Object.entries(built)) {
      expect(FORBIDDEN.filter(pattern => pattern.test(code)).map(String), name).toEqual([]);
    }
  });
});
