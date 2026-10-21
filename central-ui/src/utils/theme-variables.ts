/**
 * The forms' theming API (`forms/common.css`, `ThemeVariables` in util): the CSS variables a theme may set one by one,
 * each with the kind of value it takes. The order is the order central writes them in. `valueProblem` repeats central's
 * rule for a value, so the editor can say what is wrong before a save is refused.
 */
export type VariableKind = 'colour' | 'length';

export interface ThemeVariable {
  name: string;
  kind: VariableKind;
  /** One line, for the list of tokens. */
  description: string;
  /** What the form paints when nothing is set, as a starting value for a new row. */
  start: string;
}

export const THEME_VARIABLES: ThemeVariable[] = [
  { name: '--brand', kind: 'colour', description: 'accent colour', start: '#155e75' },
  { name: '--on-brand', kind: 'colour', description: 'text colour on the accent', start: '#ffffff' },
  { name: '--focus', kind: 'colour', description: 'focus rings and ticks', start: '#155e75' },
  { name: '--on-focus', kind: 'colour', description: 'text colour on those', start: '#ffffff' },
  { name: '--rf', kind: 'length', description: 'corner radius of fields', start: '14px' },
  { name: '--rb', kind: 'length', description: 'corner radius of buttons', start: '14px' },
  { name: '--rs', kind: 'length', description: 'card corner radius', start: '24px' },
  { name: '--page-light', kind: 'colour', description: 'page colour, light scheme', start: '#f6f4ef' },
  { name: '--page-dark', kind: 'colour', description: 'page colour, dark scheme', start: '#060707' },
  { name: '--glow-light', kind: 'colour', description: 'glow behind the card, light scheme', start: '#155e75' },
  { name: '--glow-dark', kind: 'colour', description: 'glow behind the card, dark scheme', start: '#155e75' },
  { name: '--control-h', kind: 'length', description: 'height of fields and buttons', start: '54px' },
];

/** A variable a rule may read that is not an override of its own: the background the page paints behind the card. */
export const PAGE_BG_VARIABLE = { name: '--page-bg', description: 'what is painted behind the card' };

export const variableByName = (name: string) => THEME_VARIABLES.find(variable => variable.name === name);

const MAX_LENGTH = 120;
const SAFE = /^[A-Za-z0-9#%.,()\- +_/]+$/;
const FORBIDDEN = ['url(', 'expression(', '/*', '*/', '@', '\\', ';', '}', '{', '<', '!'];

/** Why a value cannot be stored, or `''` when it can (the rule is central's). */
export function valueProblem(value: string): string {
  const text = value.trim();
  const lower = text.toLowerCase();
  if (text === '') return 'Enter a value.';
  if (text.length > MAX_LENGTH) return `Use at most ${MAX_LENGTH} characters.`;
  if (FORBIDDEN.some(part => lower.includes(part))) return 'Not allowed: ; { } ! @ \\ url( expression( or a comment.';
  if (!SAFE.test(text)) return 'Use only what a colour or a length is written with.';
  if ([...text].filter(c => c === '(').length !== [...text].filter(c => c === ')').length) return 'Close every parenthesis.';
  return '';
}

export const isHexColour = (value: string) => /^#[0-9a-fA-F]{6}$/.test(value);
export const pixels = (value: string): number | null => {
  const match = /^(\d+(?:\.\d+)?)px$/.exec(value.trim());
  return match ? Number(match[1]) : null;
};
