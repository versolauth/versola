import { html, type TemplateResult } from 'lit';
import type { Locale } from '../types';
import { getLocalizedDescription } from '../utils/helpers';
import { valueChips } from './value-chips';

/** The active locales a localized text has no value for. Empty when no locales are known. */
export function missingLocales(value: Record<string, string> | null | undefined, locales: Locale[]): string[] {
  return locales
    .filter(locale => locale.active && !(value?.[locale.code] ?? '').trim())
    .map(locale => locale.code);
}

/** The chip next to a localized text: `Missing: ru` when an active locale has no value, nothing
 *  otherwise (a count of languages says nothing). Nothing when the console knows no locales. The
 *  host needs `chipStyles`. */
export function languagesChip(value: Record<string, string> | null | undefined, locales: Locale[]): TemplateResult | '' {
  if (locales.length === 0) return '';
  const missing = missingLocales(value, locales);
  return missing.length ? html`<span class="chip warn">Missing: ${missing.join(', ')}</span>` : '';
}

/** A localized text as a record value (D10): the English value and a Missing chip when needed. */
export function localizedValue(value: Record<string, string> | null | undefined, locales: Locale[]): TemplateResult {
  return html`<span class="localized-value">${getLocalizedDescription(value ?? {}) || '—'} ${languagesChip(value, locales)}</span>`;
}

/** A list's Languages column: the codes the text has a value for, or the Missing chip. */
export function languagesCell(value: Record<string, string> | null | undefined, locales: Locale[]): TemplateResult | string {
  const active = locales.filter(locale => locale.active);
  if (active.length === 0) return '—';
  const missing = missingLocales(value, locales);
  if (missing.length) return html`<span class="chip warn">Missing: ${missing.join(', ')}</span>`;
  return valueChips(active.map(locale => locale.code), { mono: false });
}
