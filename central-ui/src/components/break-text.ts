import { html, type TemplateResult } from 'lit';

/**
 * A value that may only break where it reads naturally: after each of `after` (a path after `/`,
 * a host after `.`). Everything else stays in one piece, so an ID or a host never splits in the
 * middle of a word. The `<wbr>` is a hint to the browser; copying the text gives the value back.
 */
export function breakAfter(value: string, after: string): TemplateResult {
  const parts = value.split(new RegExp(`([${after.replace(/[\]\\^-]/g, '\\$&')}])`)).filter(part => part.length > 0);
  const out: unknown[] = [];
  for (const part of parts) {
    out.push(part);
    if (part.length === 1 && after.includes(part)) out.push(html`<wbr />`);
  }
  return html`${out}`;
}

/** `/orders/{orderId}` breaking after each `/`. */
export const breakPath = (path: string) => breakAfter(path, '/');

/** `https://orders.internal.example.com` breaking after each `.`; the scheme stays attached to the host. */
export function breakHost(host: string): TemplateResult {
  const scheme = /^[a-z][a-z0-9+.-]*:\/\//i.exec(host)?.[0] ?? '';
  return html`${scheme}${breakAfter(host.slice(scheme.length), '.')}`;
}
