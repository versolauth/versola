/**
 * The forms' theming API (`forms/common.css`, `ThemeVariables` in util): the CSS variables a theme may set one by one.
 * The list itself (names, groups, kinds, descriptions, defaults) comes from central, `GET /configuration/themes/variables`;
 * it is defined once, in Scala. What stays here is the rule a value must pass, so the editor can say what is wrong
 * before a save is refused: `valueProblem` repeats central's rule for each kind.
 */
export type VariableKind = 'colour' | 'length' | 'shadow' | 'weight' | 'font';

/** One value for both colour schemes, or, for a token that is a pair, one per scheme. */
export type VariableValue = string | { light: string; dark: string };

export interface ThemeVariable {
  name: string;
  /** The `id` of the group the token is listed in. */
  group: string;
  kind: VariableKind;
  /** Takes `{ light, dark }` as well as one value. Only colours are pairs. */
  pair: boolean;
  /** One line for the list of tokens, by language. */
  description: Record<string, string>;
  /** What the forms paint when nothing is set, as `common.css` writes it. */
  default: string;
  /** A concrete value to start an editor from (a default such as `var(--ink)` is not a colour a picker can show). */
  start: VariableValue;
}

export interface ThemeVariableGroup {
  id: string;
  title: Record<string, string>;
}

export interface ThemeVariableCatalog {
  groups: ThemeVariableGroup[];
  variables: ThemeVariable[];
}

/** A variable a rule may read that is not an override of its own: the background the page paints behind the card. */
export const PAGE_BG_VARIABLE = { name: '--page-bg', description: 'what is painted behind the card' };

export const variableByName = (catalog: ThemeVariableCatalog, name: string) => catalog.variables.find(variable => variable.name === name);

/** The line to show for a token, in the console's language (English), falling back to any there is. */
export const describe = (variable: ThemeVariable, locale = 'en'): string =>
  variable.description[locale] ?? variable.description.en ?? Object.values(variable.description)[0] ?? '';

export const groupTitle = (group: ThemeVariableGroup, locale = 'en'): string => group.title[locale] ?? group.title.en ?? group.id;

const MAX_LENGTH = 120;
const SAFE = /^[A-Za-z0-9#%.,()\- +_/]+$/;
const FORBIDDEN = ['url(', 'expression(', '/*', '*/', '@', '\\', ';', '}', '{', '<', '!'];

const COLOUR_FUNCTIONS = new Set(['rgb', 'rgba', 'hsl', 'hsla', 'hwb', 'lab', 'lch', 'oklab', 'oklch', 'color-mix', 'color', 'var']);
const HEX = /^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
const WORD = /^[A-Za-z]+$/;
const LENGTH = /^(?:0|\d+(?:\.\d+)?(?:px|rem|em|vw|vh|%))$/;
const NUMBER = /^-?\d+(?:\.\d+)?(?:px|rem|em)?$|^0$/;
const FONT_LIST = /^[A-Za-z-][A-Za-z0-9 \-]*(?:, ?[A-Za-z-][A-Za-z0-9 \-]*)*$/;

/** What a text may not contain whatever it is for, or `''` (the rule every kind starts from). */
function textProblem(value: string): string {
  const text = value.trim();
  const lower = text.toLowerCase();
  if (text === '') return 'Enter a value.';
  if (text.length > MAX_LENGTH) return `Use at most ${MAX_LENGTH} characters.`;
  if (FORBIDDEN.some(part => lower.includes(part))) return 'Not allowed: ; { } ! @ \\ url( expression( or a comment.';
  if (!SAFE.test(text)) return 'Use only letters, digits, spaces and # % . , ( ) - + _ /.';
  if ([...text].filter(c => c === '(').length !== [...text].filter(c => c === ')').length) return 'Close every parenthesis.';
  return '';
}

function colourProblem(text: string): string {
  if (HEX.test(text) || WORD.test(text)) return '';
  const lower = text.toLowerCase();
  const call = /^([a-z-]+)\(.*\)$/.exec(lower);
  if (!call || !COLOUR_FUNCTIONS.has(call[1])) return 'Not a colour: use #rrggbb, a colour name, or rgb(), hsl(), oklch() or color-mix().';
  const calls = [...lower.matchAll(/([a-z-]+)\(/g)].map(match => match[1]);
  if (calls.some(name => !COLOUR_FUNCTIONS.has(name))) return 'Not a colour function.';
  const vars = [...lower.matchAll(/var\(\s*--[a-z0-9-]+\s*\)/g)].length;
  if (calls.filter(name => name === 'var').length !== vars) return 'var() takes a --name and nothing else.';
  return '';
}

function splitTop(text: string, separator: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = '';
  for (const char of text) {
    if (char === '(') depth += 1;
    if (char === ')') depth -= 1;
    if (char === separator && depth === 0) {
      parts.push(current);
      current = '';
    } else current += char;
  }
  parts.push(current);
  return parts.map(part => part.trim()).filter(part => part !== '');
}

function shadowProblem(text: string): string {
  if (text === 'none') return '';
  const layers = splitTop(text, ',');
  if (layers.length === 0 || layers.length > 4) return 'Use none, or one to four shadow layers.';
  for (const layer of layers) {
    const words = splitTop(layer, ' ');
    const inset = words.filter(word => word === 'inset').length;
    const rest = words.filter(word => word !== 'inset');
    let lengths = 0;
    while (lengths < rest.length && NUMBER.test(rest[lengths])) lengths += 1;
    const colours = rest.slice(lengths);
    if (inset > 1 || lengths < 2 || lengths > 4 || colours.length !== 1) return 'A shadow layer is [inset] x y [blur [spread]] colour.';
    const problem = colourProblem(colours[0]);
    if (problem) return `The shadow colour: ${problem}`;
  }
  return '';
}

/** Why a text is not a value of the kind, or `''` when it is (the rule is central's). */
export function kindProblem(kind: VariableKind, value: string): string {
  const common = textProblem(value);
  if (common) return common;
  const text = value.trim();
  switch (kind) {
    case 'colour': return colourProblem(text);
    case 'length': return LENGTH.test(text) ? '' : 'Use a number with px, rem, em, vw, vh or %, or 0.';
    case 'shadow': return shadowProblem(text);
    case 'weight': return /^[1-9]\d{2}$/.test(text) && Number(text) <= 900 ? '' : 'Use a whole number from 100 to 900.';
    case 'font': return FONT_LIST.test(text) ? '' : 'Use font names separated by commas, without quotes.';
  }
}

/** Why a value cannot be stored for the token, or `''`. A pair names the half that is wrong. */
export function valueProblem(variable: ThemeVariable, value: VariableValue): string {
  if (typeof value === 'string') return kindProblem(variable.kind, value);
  if (!variable.pair) return 'This token takes one value for both schemes.';
  const light = kindProblem(variable.kind, value.light);
  if (light) return `Light: ${light}`;
  const dark = kindProblem(variable.kind, value.dark);
  return dark ? `Dark: ${dark}` : '';
}

/** Whether two values are the same, ignoring the spaces around them. */
export function sameValue(a: VariableValue, b: VariableValue): boolean {
  if (typeof a === 'string' || typeof b === 'string') return typeof a === 'string' && typeof b === 'string' && a.trim() === b.trim();
  return a.light.trim() === b.light.trim() && a.dark.trim() === b.dark.trim();
}

export const trimmed = (value: VariableValue): VariableValue => (typeof value === 'string' ? value.trim() : { light: value.light.trim(), dark: value.dark.trim() });

export const isHexColour = (value: string) => /^#[0-9a-fA-F]{6}$/.test(value);
export const pixels = (value: string): number | null => {
  const match = /^(\d+(?:\.\d+)?)px$/.exec(value.trim());
  return match ? Number(match[1]) : null;
};
