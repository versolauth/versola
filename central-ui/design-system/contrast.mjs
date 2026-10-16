// WCAG 2.2 contrast check of the console's colour tokens. Reads the `:root[data-theme=...]`
// blocks of ../index.html, so it always checks what ships. Run from central-ui/:
//   npm run design:contrast            # prints the pairs below the threshold, exit 1 if any required pair fails
//   npm run design:contrast -- --md    # the full table as Markdown (for design-system/README.md)
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const indexHtml = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'index.html'), 'utf8');

/** `{ light: { 'bg-page': '#faf9f7', ... }, dark: {...} }` from the token blocks in index.html. */
export function readTokens(html = indexHtml) {
  const themes = {};
  html = html.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const [, theme, body] of html.matchAll(/:root\[data-theme='(light|dark)'\]\s*\{([^}]*)\}/g)) {
    themes[theme] = {};
    for (const [, name, value] of body.matchAll(/--([a-z0-9-]+):\s*([^;]+);/g)) themes[theme][name] = value.trim();
  }
  return themes;
}

const hex = (h) => {
  h = h.replace('#', '');
  if (h.length === 3) h = h.split('').map((c) => c + c).join('');
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
};
const lin = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const lum = (h) => { const [r, g, b] = hex(h); return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b); };
export const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };

// [foreground token, background token, minimum, note]. TEXT = SC 1.4.3, UI = SC 1.4.11.
// A pair marked "informative" has no requirement (a badge outline whose meaning the text carries).
const TEXT = 4.5, UI = 3;
const pairs = [
  ['text-primary', 'bg-page', TEXT], ['text-primary', 'surface', TEXT], ['text-primary', 'surface-inset', TEXT], ['text-primary', 'surface-raised', TEXT],
  ['text-secondary', 'bg-page', TEXT], ['text-secondary', 'surface', TEXT], ['text-secondary', 'surface-inset', TEXT],
  ['text-muted', 'bg-page', TEXT], ['text-muted', 'surface', TEXT], ['text-muted', 'surface-inset', TEXT, 'disabled button text'],
  ['text-primary', 'sidebar-bg', TEXT], ['text-secondary', 'sidebar-bg', TEXT], ['sidebar-active-text', 'sidebar-active-bg', TEXT],
  ['accent', 'bg-page', TEXT, 'link / accent text'], ['accent', 'surface', TEXT, 'link / accent text'], ['accent', 'surface-inset', TEXT], ['accent', 'sidebar-bg', TEXT, 'brand wordmark'],
  ['on-accent', 'accent', TEXT, 'primary button'], ['on-accent', 'accent-hover', TEXT, 'primary button hover'],
  ['accent-text', 'accent-subtle', TEXT, 'accent badge, active filter, nav hover'],
  ['accent', 'surface', UI, 'focus ring / accent border'], ['focus-ring', 'surface', UI], ['focus-ring', 'bg-page', UI], ['focus-ring', 'sidebar-bg', UI],
  ['border-strong', 'surface', UI, 'control boundary'], ['border-input', 'surface', UI, 'input border'], ['border-input', 'bg-page', UI], ['border-input', 'sidebar-bg', UI, 'tenant selector'],
  ['toggle-track-off', 'surface', UI], ['toggle-knob-off', 'toggle-track-off', UI], ['on-accent', 'accent', UI, 'toggle knob on'],
  ['success', 'surface', TEXT], ['success-text', 'success-subtle', TEXT], ['success-border', 'success-subtle', UI, 'badge border (decorative, informative only)'],
  ['warning', 'surface', TEXT], ['warning-text', 'warning-subtle', TEXT], ['warning-border', 'warning-subtle', UI, 'informative only'],
  ['danger', 'surface', TEXT], ['danger-text', 'danger-subtle', TEXT], ['danger-border', 'danger-subtle', UI, 'informative only'],
  ['on-danger', 'danger', TEXT, 'danger button'], ['danger', 'surface', UI, 'danger outline button border'],
  ['info', 'surface', TEXT], ['info-text', 'info-subtle', TEXT], ['info-border', 'info-subtle', UI, 'informative only'],
  ['accent-border', 'accent-subtle', UI, 'informative only'],
  ['text-primary', 'code-bg', TEXT], ['text-secondary', 'code-bg', TEXT], ['text-primary', 'selection-bg', TEXT],
  // Syntax highlighting sits on --code-bg (code blocks) and on --bg-page (editors).
  ...['ch-comment', 'ch-string', 'ch-number', 'ch-keyword', 'ch-tag', 'ch-attr-name', 'ch-entity', 'ch-selector', 'ch-function', 'ch-builtin', 'ch-constant', 'cel-keyword', 'cel-ctxvar']
    .flatMap((t) => [[t, 'code-bg', TEXT, 'syntax highlight'], [t, 'bg-page', TEXT, 'syntax highlight in an editor']]),
  ['border-default', 'surface', 1, 'decorative, no requirement'], ['border-subtle', 'surface', 1, 'decorative, no requirement'],
];

const md = process.argv.includes('--md');
const themes = readTokens();
let failed = 0;
const rows = [];
for (const [theme, t] of Object.entries(themes)) {
  for (const [fg, bg, min, note] of pairs) {
    if (!t[fg] || !t[bg]) throw new Error(`${theme}: missing ${fg} or ${bg}`);
    const r = ratio(t[fg], t[bg]);
    const informative = (note || '').includes('informative') || min === 1;
    const ok = r >= min || informative;
    if (!ok) failed++;
    rows.push({ theme, fg, bg, min, r: r.toFixed(2), ok: informative ? (r >= min ? 'pass' : 'n/a') : ok ? 'pass' : 'FAIL', note: note || '' });
  }
}
if (md) {
  console.log('| Theme | Foreground | Background | Ratio | Min | Result | Note |');
  console.log('|---|---|---|---|---|---|---|');
  for (const r of rows) console.log(`| ${r.theme} | ${r.fg} | ${r.bg} | ${r.r} | ${r.min === 1 ? '-' : r.min} | ${r.ok} | ${r.note} |`);
} else {
  for (const r of rows) if (r.ok !== 'pass') console.log(`${r.ok.padEnd(4)} ${r.theme} ${r.fg} on ${r.bg}: ${r.r} (min ${r.min}) ${r.note}`);
  console.log(failed ? `${failed} FAILED` : `all ${rows.length} required pairs pass`);
}
process.exit(failed ? 1 : 0);
