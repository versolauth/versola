import { css, html, nothing } from 'lit';
import { describeCountry } from '../utils/country-flag';
import { resultOf, type AuditEvent } from '../utils/user-audit';

/** The Result badge of the Activity screen: a filled pill whose text names the result (colour only supports it). */
export const resultBadgeStyles = css`
  .result {
    display: inline-block;
    padding: 0.25rem 0.625rem;
    border: 1px solid transparent;
    border-radius: var(--radius-pill);
    font-size: var(--text-xs);
    font-weight: 600;
    white-space: nowrap;
  }

  .result.ok { background: var(--success-subtle); color: var(--success-text); border-color: var(--success-border); }
  .result.warn { background: var(--warning-subtle); color: var(--warning-text); border-color: var(--warning-border); }
  .result.danger { background: var(--danger-subtle); color: var(--danger-text); border-color: var(--danger-border); }
  .result.info { background: var(--info-subtle); color: var(--info-text); border-color: var(--info-border); }

  .mono { font-family: var(--font-mono); }
  .muted { color: var(--text-secondary); }
  .flag { font-family: 'Apple Color Emoji', 'Segoe UI Emoji', 'Noto Color Emoji', sans-serif; }
`;

export function resultBadge(event: Pick<AuditEvent, 'kind' | 'outcome' | 'severity' | 'actorType'>) {
  const result = resultOf(event);
  return html`<span class="result ${result.tone}">${result.label}</span>`;
}

/** `🇰🇿 Kazakhstan · KZ` (long) or `🇰🇿 KZ` (short, the name in the title): the flag is decoration, the code and
 *  the name are the text, because some systems draw a flag emoji as two letters. Nothing when the API sent no country. */
export function countryLabel(code: string | undefined, form: 'long' | 'short' = 'long') {
  const country = describeCountry(code);
  if (!country) return nothing;
  return form === 'long'
    ? html`<span class="country"><span class="flag" aria-hidden="true">${country.flag}</span> ${country.name} · ${country.code}</span>`
    : html`<span class="country" title=${country.name}><span class="flag" aria-hidden="true">${country.flag}</span> ${country.code}</span>`;
}
