import { css } from 'lit';

export const codeHighlightStyles = css`
  .ch-tok { white-space: inherit; }

  /* Colors come from --ch-* custom properties (defined per-theme in
     index.html) rather than literal hex here, since highlighted code needs
     genuinely different colors per theme for contrast/readability — not just
     a hue swap of one palette. */

  /* shared */
  .ch-text        { color: var(--text-primary); }
  .ch-comment     { color: var(--ch-comment); font-style: italic; }
  .ch-punctuation { color: var(--text-secondary); }
  .ch-string      { color: var(--ch-string); }
  .ch-number      { color: var(--ch-number); }
  .ch-boolean     { color: var(--ch-keyword); }
  .ch-keyword     { color: var(--ch-keyword); }
  .ch-null        { color: var(--ch-keyword); }
  .ch-operator    { color: var(--text-secondary); }

  /* HTML / markup */
  .ch-tag         { color: var(--ch-tag); }
  .ch-attr-name   { color: var(--ch-attr-name); }
  .ch-attr-value  { color: var(--ch-string); }
  .ch-doctype     { color: var(--ch-comment); }
  .ch-entity      { color: var(--ch-entity); }
  .ch-namespace   { color: var(--ch-entity); }

  /* CSS */
  .ch-selector    { color: var(--ch-selector); }
  .ch-property    { color: var(--ch-attr-name); }
  .ch-value       { color: var(--ch-string); }
  .ch-unit        { color: var(--ch-number); }
  .ch-important   { color: var(--ch-keyword); font-weight: 600; }
  .ch-atrule      { color: var(--ch-keyword); }
  .ch-function    { color: var(--ch-function); }

  /* TypeScript / TSX */
  .ch-builtin         { color: var(--ch-builtin); }
  .ch-class-name      { color: var(--ch-builtin); }
  .ch-maybe-class-name { color: var(--ch-builtin); }
  .ch-parameter       { color: var(--text-primary); }
  .ch-template-string { color: var(--ch-string); }
  .ch-template-punctuation { color: var(--ch-tag); }
  .ch-interpolation   { color: var(--text-primary); }
  .ch-regex           { color: var(--ch-string); }
  .ch-char            { color: var(--ch-string); }
  .ch-constant        { color: var(--ch-constant); }
  .ch-symbol          { color: var(--ch-constant); }
  .ch-imports         { color: var(--ch-constant); }
  .ch-exports         { color: var(--ch-constant); }
  .ch-dom             { color: var(--ch-constant); }
  .ch-console         { color: var(--ch-constant); }
`;
