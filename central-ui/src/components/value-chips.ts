import { html, nothing, type TemplateResult } from 'lit';

/**
 * The values a record holds, in a list cell: the first `max` as chips and `+N` for the rest, with
 * the rest named in its `title` (a count alone says nothing). Nothing but a dash when there are
 * none. The host needs `chipStyles` (`.chips`, `.chip.value`, `.chip.more`).
 */
export function valueChips(values: string[], options: { max?: number; empty?: string; mono?: boolean; extra?: unknown } = {}): TemplateResult {
  const { max = 2, empty = '—', mono = true, extra = nothing } = options;
  if (values.length === 0 && extra !== nothing) return html`<div class="chips">${extra}</div>`;
  if (values.length === 0) return html`<span class="muted">${empty}</span>`;
  const shown = values.slice(0, max);
  const rest = values.slice(max);
  return html`<div class="chips">
    ${shown.map(value => html`<span class=${mono ? 'chip value mono' : 'chip value'} title=${value}>${value}</span>`)}
    ${rest.length ? html`<span class="chip more" title=${rest.join(', ')}>+${rest.length}</span>` : nothing}
    ${extra}
  </div>`;
}
