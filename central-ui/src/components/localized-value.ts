import { html, type TemplateResult } from 'lit';
import type { Locale } from '../types';
import { getLocalizedDescription } from '../utils/helpers';

/** The active locales a localized text has no value for. Empty when no locales are known. */
export function missingLocales(value: Record<string, string> | null | undefined, locales: Locale[]): string[] {
  return locales
    .filter(locale => locale.active && !(value?.[locale.code] ?? '').trim())
    .map(locale => locale.code);
}

/** The chip next to a localized text: `N languages`, or `Missing: ru` when an active locale has
 *  no value. Nothing when the console knows no locales. The host needs `chipStyles`. */
export function languagesChip(value: Record<string, string> | null | undefined, locales: Locale[]): TemplateResult | '' {
  if (locales.length === 0) return '';
  const missing = missingLocales(value, locales);
  if (missing.length) return html`<span class="chip warn">Missing: ${missing.join(', ')}</span>`;
  const count = Object.keys(value ?? {}).filter(code => (value?.[code] ?? '').trim()).length;
  return html`<span class="chip">${count} ${count === 1 ? 'language' : 'languages'}</span>`;
}

/** A localized text as a record value (D10): the English value and its languages chip. */
export function localizedValue(value: Record<string, string> | null | undefined, locales: Locale[]): TemplateResult {
  return html`<span class="localized-value">${getLocalizedDescription(value ?? {}) || '—'} ${languagesChip(value, locales)}</span>`;
}

/** `3 of 3` for a list's Languages column, or the Missing chip. */
export function languagesCell(value: Record<string, string> | null | undefined, locales: Locale[]): TemplateResult | string {
  const active = locales.filter(locale => locale.active);
  if (active.length === 0) return '—';
  const missing = missingLocales(value, locales);
  if (missing.length) return html`<span class="chip warn">Missing: ${missing.join(', ')}</span>`;
  return `${active.length} of ${active.length}`;
}
