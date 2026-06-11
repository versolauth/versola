import { css } from 'lit';

export const codeHighlightStyles = css`
  .ch-tok { white-space: inherit; }

  /* shared */
  .ch-text        { color: var(--text-primary); }
  .ch-comment     { color: #8b949e; font-style: italic; }
  .ch-punctuation { color: var(--text-secondary); }
  .ch-string      { color: #a5d6ff; }
  .ch-number      { color: #f2cc60; }
  .ch-boolean     { color: #f47067; }
  .ch-keyword     { color: #f47067; }
  .ch-operator    { color: var(--text-secondary); }

  /* HTML / markup */
  .ch-tag         { color: #7ee787; }
  .ch-attr-name   { color: #79c0ff; }
  .ch-attr-value  { color: #a5d6ff; }
  .ch-doctype     { color: #8b949e; }
  .ch-entity      { color: #ffa657; }
  .ch-namespace   { color: #ffa657; }

  /* CSS */
  .ch-selector    { color: #d2a8ff; }
  .ch-property    { color: #79c0ff; }
  .ch-value       { color: #a5d6ff; }
  .ch-unit        { color: #f2cc60; }
  .ch-important   { color: #f47067; font-weight: 600; }
  .ch-atrule      { color: #f47067; }
  .ch-function    { color: #d2a8ff; }

  /* TypeScript / TSX */
  .ch-builtin         { color: #ffa657; }
  .ch-class-name      { color: #ffa657; }
  .ch-maybe-class-name { color: #ffa657; }
  .ch-parameter       { color: var(--text-primary); }
  .ch-template-string { color: #a5d6ff; }
  .ch-template-punctuation { color: #7ee787; }
  .ch-interpolation   { color: var(--text-primary); }
  .ch-regex           { color: #a5d6ff; }
  .ch-char            { color: #a5d6ff; }
  .ch-constant        { color: #79c0ff; }
  .ch-symbol          { color: #79c0ff; }
  .ch-imports         { color: #79c0ff; }
  .ch-exports         { color: #79c0ff; }
  .ch-dom             { color: #79c0ff; }
  .ch-console         { color: #79c0ff; }
`;
