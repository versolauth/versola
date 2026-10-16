import { css } from 'lit';

export const buttonStyles = css`
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    padding: 0.625rem 1.25rem;
    font-family: var(--font-family);
    font-size: 0.875rem;
    font-weight: 600;
    line-height: 1.5;
    border: 1px solid transparent;
    border-radius: var(--radius-md);
    cursor: pointer;
    /* Only the hover lift animates: a colour transition would show a
       half-way colour the moment a button is enabled (axe scans it). */
    transition: transform var(--transition-fast);
    text-decoration: none;
    white-space: nowrap;
  }

  .btn:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  .btn-primary {
    background: var(--accent);
    color: var(--on-accent);
    border: none;
  }

  .btn-primary:not(:disabled):hover {
    background: var(--accent-hover);
    transform: translateY(-1px);
  }

  .btn-secondary {
    background: var(--surface);
    color: var(--text-primary);
    border-color: var(--border-input);
  }

  .btn-secondary:not(:disabled):hover {
    border-color: var(--accent);
    color: var(--accent);
  }

  .btn-danger {
    background: var(--danger);
    color: var(--on-danger);
    border: none;
  }

  .btn-danger:not(:disabled):hover {
    opacity: 0.9;
  }

  .btn-danger-secondary {
    background: var(--danger-subtle);
    color: var(--danger-text);
    border-color: var(--danger-border);
  }

  .btn-danger-secondary:not(:disabled):hover {
    background: var(--danger-subtle);
    border-color: var(--danger);
    color: var(--danger-text);
  }

  /* Disabled is a colour state, not a faded copy of the enabled button: a
     half-transparent fill fails the text contrast the button otherwise has
     (axe flags it), and reads as a rendering glitch next to a solid one. */
  .btn:disabled,
  .btn-primary:disabled,
  .btn-secondary:disabled,
  .btn-danger:disabled,
  .btn-danger-secondary:disabled {
    background: var(--surface-inset);
    color: var(--text-muted);
    border: 1px solid var(--border-default);
    cursor: not-allowed;
    transform: none;
  }

  .btn-sm {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
  }

  .btn-lg {
    padding: 0.75rem 1.5rem;
    font-size: 1rem;
  }
`;

export const badgeStyles = css`
  .badge {
    display: inline-block;
    padding: 0.25rem 0.625rem;
    border-radius: var(--radius-sm);
    font-size: 0.75rem;
    font-weight: 600;
  }

  .badge-warning {
    background: var(--warning-subtle);
    color: var(--warning-text);
  }

  .badge-success {
    background: var(--success-subtle);
    color: var(--success-text);
  }
`;

/** A chip: a small outlined label for a state or a kind (`Web app`, `FAPI 2.0`, `Partner`).
 *  `ok` and `warn` colour the outline; the text carries the meaning. */
export const chipStyles = css`
  .chips {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
    align-items: center;
  }

  .chip {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    padding: 0.25rem 0.625rem;
    border-radius: 999px;
    border: 1px solid var(--border-default);
    font-size: 0.75rem;
    color: var(--text-primary);
    white-space: nowrap;
  }

  .chip .icon,
  .chip .status-icon {
    flex: none;
  }

  .chip.ok {
    border-color: var(--success);
  }

  .chip.ok .icon,
  .chip.ok .status-icon {
    color: var(--success);
  }

  .chip.warn {
    border-color: var(--warning);
  }
`;

/** Text styles for an icon-only control (the row menu, copy) that must stay a 24px target. */
export const iconButtonStyles = css`
  .icon-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 1.5rem;
    min-height: 1.5rem;
    padding: 0.25rem;
    border: 0;
    border-radius: var(--radius-sm);
    background: none;
    color: var(--text-secondary);
    cursor: pointer;
    line-height: 1;
    transition: color var(--transition-fast);
  }

  .icon-button:hover {
    color: var(--accent);
  }

  .icon-button:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
`;

/** A record page's cards (`versola-record-card`, `versola-record-row`, `versola-danger-card`).
 *  Those elements render into light DOM, so the screen that uses them includes this block
 *  (with buttonStyles and cardStyles) in its own styles. */
export const recordStyles = css`
  versola-record-card,
  versola-danger-card {
    display: block;
    margin-bottom: var(--spacing-md);
  }

  .record-card {
    padding: 0;
  }

  .record-card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--spacing-md);
    padding: var(--spacing-md) var(--spacing-lg);
    border-bottom: 1px solid var(--border-default);
  }

  .record-card-head h2 {
    margin: 0;
    font-size: 1rem;
    font-weight: 700;
    color: var(--text-primary);
  }

  .record-card-sub {
    font-size: 0.8125rem;
    color: var(--text-secondary);
    margin-top: 0.125rem;
  }

  .record-fixed {
    display: inline-flex;
    gap: 0.375rem;
    align-items: center;
    color: var(--text-secondary);
    font-size: 0.75rem;
    white-space: nowrap;
  }

  .record-row {
    display: grid;
    grid-template-columns: 12rem minmax(0, 1fr) auto;
    gap: 0.25rem var(--spacing-lg);
    padding: 0.75rem var(--spacing-lg);
    border-bottom: 1px solid var(--border-subtle);
    align-items: baseline;
    font-size: 0.875rem;
  }

  .record-card > :last-child > .record-row,
  .record-card > .record-row:last-child {
    border-bottom: 0;
  }

  .record-label {
    color: var(--text-secondary);
  }

  .record-value {
    color: var(--text-primary);
    overflow-wrap: anywhere;
  }

  .danger-card {
    border-color: var(--danger);
  }

  .danger-card .record-card-head {
    border-bottom: 0;
  }

  .danger-card h2 {
    color: var(--danger);
  }

  .btn-delete {
    color: var(--danger-text);
    border-color: var(--danger);
  }

  .btn-delete:not(:disabled):hover {
    background: var(--danger-subtle);
    border-color: var(--danger);
    color: var(--danger-text);
  }

  @media (max-width: 720px) {
    .record-row {
      grid-template-columns: 1fr;
    }
  }
`;

export const cardStyles = css`
  .card {
    background: var(--surface);
    border: 1px solid var(--border-default);
    border-radius: var(--radius-lg);
    padding: var(--spacing-xl);
    transition: border-color var(--transition-base);
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--spacing-lg);
    padding-bottom: var(--spacing-md);
    border-bottom: 1px solid var(--border-default);
  }

  .card-title {
    font-size: 1.25rem;
    font-weight: 700;
    color: var(--text-primary);
    margin: 0;
  }

  .card-body {
    color: var(--text-secondary);
  }
`;

export const tableStyles = css`
  .table-container {
    overflow-x: auto;
    border: 1px solid var(--border-default);
    border-radius: var(--radius-lg);
  }

  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.875rem;
  }

  thead {
    background: var(--surface-inset);
    border-bottom: 1px solid var(--border-default);
  }

  th {
    padding: 0.875rem 1rem;
    text-align: left;
    font-weight: 600;
    color: var(--text-secondary);
    text-transform: uppercase;
    font-size: 0.75rem;
    letter-spacing: 0.05em;
  }

  td {
    padding: 1rem;
    border-top: 1px solid var(--border-default);
    color: var(--text-primary);
  }

  tbody tr {
    transition: background-color var(--transition-fast);
  }

  tbody tr:hover {
    background: var(--accent-subtle);
  }

  .table-actions {
    display: flex;
    gap: 0.75rem;
    justify-content: flex-end;
    align-items: center;
  }

  .icon-action {
    background: none;
    border: none;
    padding: 0;
    cursor: pointer;
    color: var(--text-secondary);
    font-size: 1.125rem;
    transition: all var(--transition-fast);
    line-height: 1;
  }

  .icon-action:hover {
    color: var(--accent);
    transform: scale(1.15);
  }

  .icon-action.danger:hover {
    color: var(--danger);
  }
`;

export const iconActionStyles = css`
  .icon-action {
    background: none;
    border: none;
    padding: 0;
    cursor: pointer;
    color: var(--text-secondary);
    font-size: 1.125rem;
    transition: all var(--transition-fast);
    line-height: 1;
  }

  .icon-action:hover {
    color: var(--accent);
    transform: scale(1.15);
  }

  .icon-action.danger:hover {
    color: var(--danger);
  }
`;

export const formStyles = css`
  .title-stack {
    display: grid;
    gap: 0.375rem;
    min-width: 0;
  }

  /* Keeps the mobile drawer toggle grouped with a form's title.
     .form-header rows are justify-content: space-between, so without this
     wrapper the toggle and the title would be pushed to opposite ends.
     The toggle is display:none above the breakpoint, which collapses this
     back to a plain title on desktop. */
  .form-header-lead {
    display: flex;
    align-items: center;
    gap: var(--spacing-md);
    min-width: 0;
  }

  .compact-input {
    display: block;
    width: 100%;
    max-width: min(100%, var(--compact-field-max-width, 22.8rem));
    box-sizing: border-box;
  }

  .entity-id-meta {
    display: block;
    max-width: min(100%, 32rem);
    color: var(--accent);
    font-family: var(--font-mono);
    font-size: 0.9375rem;
    font-weight: 500;
    line-height: 1.4;
    overflow-wrap: anywhere;
  }

  .inline-action-field {
    min-width: 0;
  }

  .inline-action-row {
    display: flex;
    align-items: flex-start;
    gap: 0.5rem;
    width: 100%;
    max-width: min(100%, calc(var(--compact-field-max-width, 22.8rem) + var(--inline-action-button-width, 5.25rem) + 0.5rem));
  }

  .inline-action-row > input,
  .inline-action-row > select,
  .inline-action-row > .inline-action-field {
    flex: 0 0 min(100%, var(--compact-field-max-width, 22.8rem));
    width: min(100%, var(--compact-field-max-width, 22.8rem));
    max-width: min(100%, var(--compact-field-max-width, 22.8rem));
    min-width: 0;
    box-sizing: border-box;
  }

  .inline-action-button {
    flex: 0 0 var(--inline-action-button-width, 5.25rem);
    width: var(--inline-action-button-width, 5.25rem);
    min-width: var(--inline-action-button-width, 5.25rem);
  }

  .form-group {
    margin-bottom: var(--spacing-lg);
  }

  label {
    display: block;
    margin-bottom: var(--spacing-sm);
    font-size: 0.875rem;
    font-weight: 500;
    color: var(--text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  input[type="text"],
  input[type="email"],
  input[type="search"],
  input[type="url"],
  input[type="number"],
  textarea,
  select {
    width: 100%;
    padding: 0.75rem 1rem;
    background: var(--bg-page);
    border: 1px solid var(--border-input);
    border-radius: var(--radius-md);
    color: var(--text-primary);
    font-family: var(--font-family);
    font-size: 0.875rem;
    transition: border-color var(--transition-fast);
  }

  input[type="search"] {
    -webkit-appearance: none;
    appearance: none;
  }

  select {
    -webkit-appearance: none;
    appearance: none;
    padding-right: 2.75rem;
    /* The chevron is an image, so its colour cannot be a var() inside the data
       URL: --select-chevron carries a themed copy of it (index.html). */
    background-image: var(--select-chevron);
    background-position: calc(100% - 1rem) 50%;
    background-size: 0.625rem 0.375rem;
    background-repeat: no-repeat;
  }

  /* The focus indicator is always the 2px --focus-ring outline (3:1 on every
     surface, design-system/contrast.mjs); the border colour only follows. */
  input:focus,
  textarea:focus,
  select:focus {
    outline: 2px solid var(--focus-ring);
    outline-offset: 1px;
    border-color: var(--accent);
  }

  textarea {
    resize: vertical;
    min-height: 100px;
  }

  input.input-error,
  textarea.input-error,
  select.input-error {
    border-color: var(--danger);
  }

  input.input-error:focus,
  textarea.input-error:focus,
  select.input-error:focus {
    border-color: var(--danger);
  }

  .error-message {
    margin-top: var(--spacing-xs);
    font-size: 0.8125rem;
    color: var(--danger);
  }

  .hint {
    margin-top: var(--spacing-xs);
    font-size: 0.8125rem;
    color: var(--text-secondary);
  }

  @media (max-width: 720px) {
    .inline-action-row {
      flex-direction: column;
      max-width: min(100%, var(--compact-field-max-width, 22.8rem));
    }

    .inline-action-row > input,
    .inline-action-row > select,
    .inline-action-row > .inline-action-field,
    .inline-action-button {
      flex: 1 1 auto;
      width: 100%;
      max-width: min(100%, var(--compact-field-max-width, 22.8rem));
      min-width: 0;
    }
  }
`;

/** Endpoint method badge + path, as shown in permission and resource cards. */
export const methodBadgeStyles = css`
  /* Previously declared inline in permissions-list, permission-form and
     resources-list, none of which set a size — so paths rendered at the
     browser's default 16px while the rest of the UI sits at 0.875rem. The
     oversized text pushed long paths (/configuration/edges/rotate-key) onto a
     second line and shoved the row's remove button down with it. */
  .endpoint-path {
    font-family: var(--font-mono, monospace);
    font-size: 0.8125rem;
    color: var(--text-primary);
    word-break: break-all;
  }

  .method-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 4.25rem;
    padding: 0.3rem 0.65rem;
    border-radius: 999px;
    border: 1px solid transparent;
    font-size: 0.75rem;
    font-weight: 700;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    font-family: var(--font-mono);
  }

  .method-get {
    color: var(--success-text);
    background: var(--success-subtle);
    border-color: var(--success-border);
  }

  .method-post {
    color: var(--accent-text);
    background: var(--accent-subtle);
    border-color: var(--accent-border);
  }

  .method-put {
    color: var(--accent-text);
    background: var(--accent-subtle);
    border-color: var(--accent-border);
  }

  .method-patch {
    color: var(--warning-text);
    background: var(--warning-subtle);
    border-color: var(--warning-border);
  }

  .method-delete {
    color: var(--danger-text);
    background: var(--danger-subtle);
    border-color: var(--danger-border);
  }

  .method-unknown {
    color: var(--text-secondary);
    background: var(--surface-inset);
    border-color: var(--border-default);
  }
`;

