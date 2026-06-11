import { css } from 'lit';

export const celHighlightStyles = css`
  .cel-tok { white-space: inherit; }
  .cel-keyword { color: #ff7b72; }
  .cel-ctxvar  { color: #d2a8ff; font-weight: 600; }
  .cel-fn      { color: #d2a8ff; }
  .cel-ident   { color: var(--text-primary); }
  .cel-string  { color: #a5d6ff; }
  .cel-number  { color: #79c0ff; }
  .cel-op      { color: #ff7b72; }
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
