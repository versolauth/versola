import { describe, expect, it } from 'vitest';
import { THEME_VARIABLES, isHexColour, pixels, valueProblem, variableByName } from './theme-variables';

describe('theme variables', () => {
  it('lists the forms\' theming API in the order central writes it', () => {
    expect(THEME_VARIABLES.map(variable => variable.name)).toEqual([
      '--brand', '--on-brand', '--focus', '--on-focus', '--rf', '--rb', '--rs', '--page-light', '--page-dark', '--glow-light', '--glow-dark', '--control-h',
    ]);
    expect(variableByName('--rs')?.description).toBe('card corner radius');
    expect(THEME_VARIABLES.filter(variable => variable.kind === 'length').map(variable => variable.name)).toEqual(['--rf', '--rb', '--rs', '--control-h']);
  });

  it('accepts colours, lengths and plain values', () => {
    for (const value of ['#ff6b4a', '8px', '3.5rem', 'rgb(0 0 0 / 50%)', 'color-mix(in oklab, red 20%, white)']) expect(valueProblem(value)).toBe('');
  });

  it('refuses what could leave the declaration, as central does', () => {
    for (const value of ['', '  ', 'red; } body { display: none', 'url(x)', 'URL(x)', 'expression(1)', 'red /* x */', 'red !important', '\\41', '@import', 'rgb(1,2,3', 'a'.repeat(121)]) {
      expect(valueProblem(value), value).not.toBe('');
    }
  });

  it('reads a hex colour and a pixel length', () => {
    expect(isHexColour('#ff6b4a')).toBe(true);
    expect(isHexColour('#fff')).toBe(false);
    expect(pixels('8px')).toBe(8);
    expect(pixels('0.5rem')).toBeNull();
  });
});
