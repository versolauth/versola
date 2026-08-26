import { css } from 'lit';

export const celHighlightStyles = css`
  /* Colors come from CEL and code-highlight custom properties (defined per-theme in
     index.html), same reasoning as code-highlight.ts. */
  .cel-tok { white-space: inherit; }
  .cel-keyword { color: var(--cel-keyword); }
  .cel-ctxvar  { color: var(--cel-ctxvar); font-weight: 600; }
  .cel-fn      { color: var(--cel-ctxvar); }
  .cel-ident   { color: var(--text-primary); }
  .cel-string  { color: var(--ch-string); }
  .cel-number  { color: var(--ch-attr-name); }
  .cel-op      { color: var(--cel-keyword); }
  .cel-punct   { color: var(--text-secondary); }
  .cel-plain   { color: var(--text-primary); }
  .cel-whitespace { color: inherit; }
  .cel-error   { text-decoration: underline wavy var(--danger); text-underline-offset: 2px; }
  .cel-inline {
    font-family: var(--font-mono, monospace);
    font-size: 0.8125rem;
    line-height: 1.5;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    word-break: break-word;
    color: var(--text-primary);
  }
`;
