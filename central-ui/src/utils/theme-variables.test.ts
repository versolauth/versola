import { describe, expect, it } from 'vitest';
import catalogJson from './theme-variables.json' with { type: 'json' };
import { describe as describeVariable, groupTitle, isHexColour, kindProblem, pixels, sameValue, valueProblem, variableByName, type ThemeVariableCatalog } from './theme-variables';

// The copy of central's list (ThemeVariables.scala keeps it equal to the Scala list in a test).
const catalog = catalogJson as ThemeVariableCatalog;
const variable = (name: string) => variableByName(catalog, name)!;
const problem = (name: string, value: Parameters<typeof valueProblem>[1]) => valueProblem(variable(name), value);

describe('the catalog central serves', () => {
  it('lists every token with a group, a kind, descriptions in both languages and a start that passes its own rule', () => {
    const groups = new Set(catalog.groups.map(group => group.id));
    expect(catalog.variables.length).toBeGreaterThanOrEqual(40);
    for (const item of catalog.variables) {
      expect(groups.has(item.group), item.name).toBe(true);
      expect(Object.keys(item.description).sort(), item.name).toEqual(['en', 'ru']);
      expect(valueProblem(item, item.start), item.name).toBe('');
      expect(typeof item.start === 'object', item.name).toBe(item.pair);
      if (item.pair) expect(item.kind, item.name).toBe('colour');
    }
    expect(catalog.groups.map(group => groupTitle(group))).toContain('Buttons');
    expect(describeVariable(variable('--btn'))).toBe('Fill of the main button');
    expect(describeVariable(variable('--btn'), 'ru')).toBe('Заливка главной кнопки');
  });

  it('has the tokens an operator restyles first, with the old names kept', () => {
    for (const name of ['--btn', '--on-btn', '--btn-hover', '--btn-secondary-line', '--btn-secondary-ink', '--sheet', '--sheet-line', '--shadow', '--field', '--field-hover', '--line', '--line-strong', '--ink', '--text-muted', '--danger', '--invert', '--brand', '--on-brand', '--focus', '--on-focus', '--rf', '--rb', '--rs', '--page-light', '--page-dark', '--glow-light', '--glow-dark', '--control-h']) {
      expect(variableByName(catalog, name), name).toBeDefined();
    }
  });
});

describe('value rules per kind', () => {
  it('accepts a colour as hex, a name or a colour function', () => {
    for (const value of ['#ff6b4a', '#fff', '#ff6b4a80', 'red', 'transparent', 'rgb(0 0 0 / 50%)', 'rgba(0, 0, 0, 0.5)', 'oklch(60% 0.2 30)', 'color-mix(in oklab, var(--brand) 20%, white)']) {
      expect(kindProblem('colour', value), value).toBe('');
    }
    for (const value of ['12px', '#ff', '#ggg', 'calc(1px)', 'light-dark(red, blue)', 'var(x)', 'red blue', '1']) expect(kindProblem('colour', value), value).not.toBe('');
  });

  it('accepts a length as a number with a unit, or 0', () => {
    for (const value of ['8px', '0', '3.5rem', '1em', '90vw', '50%', '999px']) expect(kindProblem('length', value), value).toBe('');
    for (const value of ['red', '8', '-4px', '8 px', 'calc(1px + 2px)', '8pt']) expect(kindProblem('length', value), value).not.toBe('');
  });

  it('accepts a weight from 100 to 900', () => {
    for (const value of ['100', '400', '750', '900']) expect(kindProblem('weight', value), value).toBe('');
    for (const value of ['99', '901', 'bold', '7.5', '0700', '-100']) expect(kindProblem('weight', value), value).not.toBe('');
  });

  it('accepts none, or layers of offsets, optional blur and spread, and a colour', () => {
    for (const value of ['none', '0 30px 60px -24px var(--shadow-color)', '0 0 0 1px #00000020', '0 2px 8px rgba(0, 0, 0, 0.2), inset 0 1px 0 white', '4px 4px red']) expect(kindProblem('shadow', value), value).toBe('');
    for (const value of ['red', '0 0', '0 0 0 0 0 red', '0 30px 60px -24px', '0 1px red blue', 'none, none', '0 0 inset inset red']) expect(kindProblem('shadow', value), value).not.toBe('');
  });

  it('accepts a list of font names, without quotes, urls or functions', () => {
    for (const value of ['Georgia, serif', 'system-ui, Segoe UI, sans-serif', 'Arial']) expect(kindProblem('font', value), value).toBe('');
    for (const value of ["'Georgia'", '"x"', 'url(x)', 'Georgia;', '1px']) expect(kindProblem('font', value), value).not.toBe('');
  });

  it('refuses what could leave the declaration, whatever the kind, as central does', () => {
    for (const kind of ['colour', 'length', 'shadow', 'weight', 'font'] as const) {
      for (const value of ['', '  ', 'red; } body { display: none', 'url(x)', 'URL(x)', 'expression(1)', 'red /* x */', 'red !important', '\\41', '@import', 'rgb(1,2,3', 'a'.repeat(121)]) {
        expect(kindProblem(kind, value), `${kind}: ${value}`).not.toBe('');
      }
    }
  });
});

describe('pairs', () => {
  it('takes one value, or a light and a dark one, for a pair token', () => {
    expect(problem('--ink', '#111111')).toBe('');
    expect(problem('--ink', { light: '#111111', dark: '#eeeeee' })).toBe('');
  });

  it('names the half that is wrong, and refuses a pair for a token that is not one', () => {
    expect(problem('--ink', { light: '#111111', dark: 'nope nope' })).toMatch(/^Dark: /);
    expect(problem('--ink', { light: '12px', dark: '#eeeeee' })).toMatch(/^Light: /);
    expect(problem('--brand', { light: '#111111', dark: '#eeeeee' })).toBe('This token takes one value for both schemes.');
    expect(problem('--rs', { light: '8px', dark: '9px' })).not.toBe('');
  });

  it('compares values ignoring spaces, and a pair with a single value is never the same', () => {
    expect(sameValue(' #fff ', '#fff')).toBe(true);
    expect(sameValue({ light: '#fff', dark: ' #000' }, { light: '#fff ', dark: '#000' })).toBe(true);
    expect(sameValue('#fff', { light: '#fff', dark: '#fff' })).toBe(false);
    expect(sameValue({ light: '#fff', dark: '#000' }, { light: '#fff', dark: '#111' })).toBe(false);
  });
});

describe('helpers', () => {
  it('reads a hex colour and a pixel length', () => {
    expect(isHexColour('#ff6b4a')).toBe(true);
    expect(isHexColour('#fff')).toBe(false);
    expect(pixels('8px')).toBe(8);
    expect(pixels('0.5rem')).toBeNull();
  });
});
