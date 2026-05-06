import { html, type TemplateResult } from 'lit';

export type CelTokenKind =
  | 'whitespace'
  | 'string'
  | 'number'
  | 'keyword'
  | 'ctxvar'
  | 'fn'
  | 'ident'
  | 'op'
  | 'punct'
  | 'plain';

export interface CelToken {
  kind: CelTokenKind;
  value: string;
  error?: boolean;
}

const keywords = new Set(['true', 'false', 'null', 'in']);
const contextVars = new Set(['token', 'user', 'request']);

const identStart = /[A-Za-z_]/;
const identCont = /[A-Za-z0-9_]/;
const digit = /[0-9]/;
const hexDigit = /[0-9a-fA-F]/;
const opChars = '+-*/%<>=!&|?:.';

export function tokenizeCel(source: string): CelToken[] {
  const tokens: CelToken[] = [];
  let i = 0;
  const n = source.length;

  while (i < n) {
    const c = source[i];

    if (c === ' ' || c === '\t' || c === '\n' || c === '\r') {
      let j = i + 1;
      while (j < n && /\s/.test(source[j])) j++;
      tokens.push({ kind: 'whitespace', value: source.slice(i, j) });
      i = j;
      continue;
    }

    // Raw string prefix
    let stringStart = i;
    let isRaw = false;
    if ((c === 'r' || c === 'R') && (source[i + 1] === '"' || source[i + 1] === '\'')) {
      isRaw = true;
      stringStart = i + 1;
    }
    const quote = source[stringStart];
    if (quote === '"' || quote === '\'') {
      const triple = source.slice(stringStart, stringStart + 3) === quote.repeat(3);
      const result = triple ? readTripleString(source, stringStart, quote, isRaw) : readString(source, stringStart, quote, isRaw);
      tokens.push({ kind: 'string', value: source.slice(i, result.end), error: !result.terminated || undefined });
      i = result.end;
      continue;
    }

    if (digit.test(c) || (c === '.' && i + 1 < n && digit.test(source[i + 1]))) {
      const end = readNumber(source, i);
      tokens.push({ kind: 'number', value: source.slice(i, end) });
      i = end;
      continue;
    }

    if (identStart.test(c)) {
      let j = i + 1;
      while (j < n && identCont.test(source[j])) j++;
      const value = source.slice(i, j);
      let kind: CelTokenKind;
      if (keywords.has(value)) kind = 'keyword';
      else if (contextVars.has(value)) kind = 'ctxvar';
      else {
        let k = j;
        while (k < n && /\s/.test(source[k])) k++;
        kind = source[k] === '(' ? 'fn' : 'ident';
      }
      tokens.push({ kind, value });
      i = j;
      continue;
    }

    if (opChars.includes(c)) {
      let j = i + 1;
      while (j < n && opChars.includes(source[j])) j++;
      tokens.push({ kind: 'op', value: source.slice(i, j) });
      i = j;
      continue;
    }

    if ('()[]{},;'.includes(c)) {
      tokens.push({ kind: 'punct', value: c });
      i++;
      continue;
    }

    tokens.push({ kind: 'plain', value: c });
    i++;
  }

  return tokens;
}

function readString(source: string, start: number, quote: string, isRaw: boolean): { end: number; terminated: boolean } {
  let i = start + 1;
  while (i < source.length) {
    const c = source[i];
    if (c === quote) return { end: i + 1, terminated: true };
    if (!isRaw && c === '\\' && i + 1 < source.length) { i += 2; continue; }
    if (c === '\n') return { end: i, terminated: false };
    i++;
  }
  return { end: i, terminated: false };
}

function readTripleString(source: string, start: number, quote: string, isRaw: boolean): { end: number; terminated: boolean } {
  const triple = quote.repeat(3);
  let i = start + 3;
  while (i < source.length) {
    if (source.slice(i, i + 3) === triple) return { end: i + 3, terminated: true };
    if (!isRaw && source[i] === '\\' && i + 1 < source.length) { i += 2; continue; }
    i++;
  }
  return { end: source.length, terminated: false };
}

function readNumber(source: string, start: number): number {
  let i = start;
  if (source[i] === '0' && (source[i + 1] === 'x' || source[i + 1] === 'X')) {
    i += 2;
    while (i < source.length && hexDigit.test(source[i])) i++;
  } else {
    while (i < source.length && digit.test(source[i])) i++;
    if (source[i] === '.' && digit.test(source[i + 1])) {
      i++;
      while (i < source.length && digit.test(source[i])) i++;
    }
    if (source[i] === 'e' || source[i] === 'E') {
      i++;
      if (source[i] === '+' || source[i] === '-') i++;
      while (i < source.length && digit.test(source[i])) i++;
    }
  }
  if (source[i] === 'u' || source[i] === 'U') i++;
  return i;
}

export function renderHighlightedCel(source: string): TemplateResult {
  const tokens = tokenizeCel(source);
  return html`${tokens.map(token => html`<span class=${`cel-tok cel-${token.kind}${token.error ? ' cel-error' : ''}`}>${token.value}</span>`)}`;
}

export { validateCel } from './cel-validator';
export type { CelValidationError, CelValidationResult } from './cel-validator';
