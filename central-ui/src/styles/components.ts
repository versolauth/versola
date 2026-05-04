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
    transition: all var(--transition-fast);
    text-decoration: none;
    white-space: nowrap;
  }

  .btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .btn-primary {
    background: var(--accent-gradient);
    color: white;
    border: none;
  }

  .btn-primary:not(:disabled):hover {
    opacity: 0.9;
    transform: translateY(-1px);
  }

  .btn-secondary {
    background: var(--bg-dark-card);
    color: var(--text-primary);
    border-color: var(--border-dark);
  }

  .btn-secondary:not(:disabled):hover {
    border-color: var(--accent);
    color: var(--accent);
  }

  .btn-danger {
    background: var(--danger);
    color: white;
    border: none;
  }

  .btn-danger:not(:disabled):hover {
    opacity: 0.9;
  }

  .btn-danger-secondary {
    background: rgba(248, 81, 73, 0.12);
    color: #ffb4ad;
    border-color: rgba(248, 81, 73, 0.35);
  }

  .btn-danger-secondary:not(:disabled):hover {
    background: rgba(248, 81, 73, 0.18);
    border-color: var(--danger, #f85149);
    color: #ffd2cd;
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
    background: rgba(210, 153, 34, 0.15);
    color: var(--warning);
  }
`;

export const cardStyles = css`
  .card {
    background: var(--bg-dark-card);
    border: 1px solid var(--border-dark);
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
    border-bottom: 1px solid var(--border-dark);
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
    border: 1px solid var(--border-dark);
    border-radius: var(--radius-lg);
  }

  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.875rem;
  }

  thead {
    background: rgba(0, 0, 0, 0.2);
    border-bottom: 1px solid var(--border-dark);
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
    border-top: 1px solid var(--border-dark);
    color: var(--text-primary);
  }

  tbody tr {
    transition: background-color var(--transition-fast);
  }

  tbody tr:hover {
    background: rgba(88, 166, 255, 0.05);
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
    background: var(--bg-dark);
    border: 1px solid var(--border-dark);
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
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 6'%3E%3Cpath fill='%238b949e' d='M5 6 0 0h10z'/%3E%3C/svg%3E");
    background-position: calc(100% - 1rem) 50%;
    background-size: 0.625rem 0.375rem;
    background-repeat: no-repeat;
  }

  input:focus,
  textarea:focus,
  select:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 2px rgba(88, 166, 255, 0.15);
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
    box-shadow: 0 0 0 2px rgba(248, 81, 73, 0.18);
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

export const methodBadgeStyles = css`
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
    color: #3fb950;
    background: rgba(63, 185, 80, 0.12);
    border-color: rgba(63, 185, 80, 0.35);
  }

  .method-post {
    color: #58a6ff;
    background: rgba(88, 166, 255, 0.12);
    border-color: rgba(88, 166, 255, 0.35);
  }

  .method-put {
    color: #a371f7;
    background: rgba(163, 113, 247, 0.12);
    border-color: rgba(163, 113, 247, 0.35);
  }

  .method-patch {
    color: #d29922;
    background: rgba(210, 153, 34, 0.14);
    border-color: rgba(210, 153, 34, 0.35);
  }

  .method-delete {
    color: #f85149;
    background: rgba(248, 81, 73, 0.14);
    border-color: rgba(248, 81, 73, 0.35);
  }

  .method-unknown {
    color: var(--text-secondary);
    background: rgba(139, 148, 158, 0.12);
    border-color: rgba(139, 148, 158, 0.3);
  }
`;

