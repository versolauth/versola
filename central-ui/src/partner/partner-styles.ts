import { css } from 'lit';

/** The few styles every partner view shares on top of `styles/components.ts`. */
export const partnerViewStyles = css`
  :host { display: block; }

  .list-toolbar {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-md);
    align-items: center;
    margin-bottom: var(--spacing-lg);
  }

  .search-input {
    flex: 1 1 14rem;
    max-width: 28rem;
    min-width: 0;
  }

  .muted { color: var(--text-secondary); }
  .small { font-size: var(--text-sm); }
  .mono { font-family: var(--font-mono); font-size: var(--text-sm); overflow-wrap: anywhere; }
  .row-title.mono { font-size: var(--text-base); }

  .record-page .record-card { margin-bottom: var(--spacing-md); }
  .record-list { display: grid; gap: 0.5rem; padding: 0.75rem var(--spacing-lg); }
  .record-compact .record-list { padding: 0.625rem var(--spacing-md); }
  /* A list that holds a flush table runs to the card's edges. */
  .record-list.bleed, .record-compact .record-list.bleed { padding-left: 0; padding-right: 0; }
  .list-row { display: flex; flex-wrap: wrap; gap: 0.375rem 0.75rem; align-items: center; }
  .list-row .chip { margin-left: auto; }
  .chip.muted-chip { color: var(--text-secondary); }

  .link-button {
    background: none;
    border: 0;
    padding: 0;
    font: inherit;
    color: var(--accent);
    text-decoration: underline;
    text-align: left;
    cursor: pointer;
  }
  .link-button:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; border-radius: var(--radius-sm); }

  .key-actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }

  .secret-rows { display: grid; gap: 0.5rem; margin: 0.75rem 0; }
  .secret-label { font-size: var(--text-xs); color: var(--text-secondary); margin-bottom: 0.125rem; }
  .secret-value {
    margin: 0;
    padding: 0.625rem 0.75rem;
    background: var(--bg-page);
    border: 1px solid var(--border-default);
    border-radius: var(--radius-md);
    color: var(--text-primary);
    font-family: var(--font-mono);
    font-size: var(--text-sm);
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 20rem;
    overflow: auto;
  }
  .banner-actions { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: center; }
  .copy-feedback { font-size: var(--text-sm); color: var(--success-text); }
  .key-banner { display: block; }
  .key-banner .banner-text { flex: 1 1 100%; }

  .checkbox-list { display: grid; gap: 0.375rem; }
  .checkbox-row {
    display: flex;
    gap: 0.625rem;
    align-items: center;
    font-size: var(--text-base);
    color: var(--text-primary);
    text-transform: none;
    letter-spacing: 0;
    font-weight: 400;
    margin: 0;
    cursor: pointer;
  }
  .checkbox-row input { width: 1rem; height: 1rem; margin: 0; accent-color: var(--accent); flex: none; }
  .checkbox-row input:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
  .checkbox-row .mono { font-size: var(--text-base); }


  .grid-2 { display: grid; grid-template-columns: repeat(auto-fit, minmax(22rem, 1fr)); gap: var(--spacing-md); align-items: start; }
  .grid-2 > * { min-width: 0; }

  .code-block {
    margin: 0;
    padding: 0.75rem 1rem;
    background: var(--code-bg);
    border: 1px solid var(--border-default);
    border-radius: var(--radius-inner);
    color: var(--text-primary);
    font-family: var(--font-mono);
    font-size: var(--text-sm);
    line-height: 1.5;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }

  .form-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    align-items: center;
    justify-content: flex-end;
    margin-top: var(--spacing-lg);
    padding-top: var(--spacing-lg);
    border-top: 1px solid var(--border-default);
  }
  .form-actions .note { margin-right: auto; font-size: var(--text-sm); color: var(--text-secondary); }

  textarea.compact-input { max-width: min(100%, 32rem); }
`;
