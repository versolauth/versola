import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import catalog from '../src/utils/theme-variables.json' with { type: 'json' };

const here = dirname(fileURLToPath(import.meta.url));
const read = (path: string) => readFileSync(join(here, path), 'utf8');
const common = read('common.css');
const sheets = [common, ...readdirSync(here, { withFileTypes: true }).filter(entry => entry.isDirectory()).flatMap(entry =>
  readdirSync(join(here, entry.name)).filter(file => file.endsWith('.css')).map(file => read(join(entry.name, file))))];
const all = sheets.join('\n');

// A token is a promise that changing it changes something on a sign-in screen, so a token nothing reads is a defect.
describe('the tokens in the list', () => {
  it('are each read by a rule of the forms (or by another token that a rule reads)', () => {
    const unread = catalog.variables.map(variable => variable.name).filter(name => !new RegExp(`var\\(\\s*${name}\\b`).test(all));
    expect(unread).toEqual([]);
  });

  it('are each declared in common.css :root, with the default the list says', () => {
    const root = common.slice(common.indexOf(':root {'), common.indexOf('\n}\n'));
    for (const variable of catalog.variables) {
      const declared = new RegExp(`^\\s*${variable.name}:\\s*([^;]+);`, 'm').exec(root);
      expect(declared, variable.name).not.toBeNull();
      expect(declared![1].replace(/\s+/g, ' ').trim(), variable.name).toBe(variable.default.replace(/\s+/g, ' '));
    }
  });

  it('leave no literal colour on the button, link and focus-border rules', () => {
    const rule = (selector: string) => new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`).exec(common)?.[1] ?? '';
    for (const selector of ['.btn-primary', '.btn-primary:hover:not(:disabled)', '.btn-secondary', '.btn-secondary:hover:not(:disabled)', 'a', '.input-field:focus']) {
      expect(rule(selector), selector).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(/);
    }
  });
});
