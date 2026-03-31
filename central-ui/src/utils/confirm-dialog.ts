export type ConfirmDialogOptions = {
  title: string;
  message?: string;
  messagePrefix?: string;
  messageSubject?: string;
  messageSuffix?: string;
  confirmLabel?: string;
  cancelLabel?: string;
};

const ACTIVE_DIALOG_ATTR = 'data-versola-confirm-dialog';

export function confirmDestructiveAction(options: ConfirmDialogOptions): Promise<boolean> {
  document.querySelector(`[${ACTIVE_DIALOG_ATTR}]`)?.remove();

  return new Promise(resolve => {
    const previousActive = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;

    const overlay = document.createElement('div');
    overlay.setAttribute(ACTIVE_DIALOG_ATTR, 'true');
    overlay.tabIndex = -1;
    overlay.style.position = 'fixed';
    overlay.style.inset = '0';
    overlay.style.zIndex = '9999';

    const shadow = overlay.attachShadow({ mode: 'open' });
    shadow.innerHTML = `
      <style>
        :host { all: initial; }
        .backdrop {
          position: fixed;
          inset: 0;
          display: flex;
          align-items: center;
          justify-content: center;
          padding: 1.5rem;
          background: rgba(9, 12, 18, 0.72);
          backdrop-filter: blur(4px);
          font-family: Inter, system-ui, sans-serif;
        }
        .dialog {
          width: min(100%, 28rem);
          background: var(--bg-dark-card, #161b22);
          border: 1px solid var(--border-dark, #30363d);
          border-radius: var(--radius-lg, 1rem);
          box-shadow: 0 24px 80px rgba(0, 0, 0, 0.45);
          color: var(--text-primary, #f0f6fc);
          padding: 1.25rem;
        }
        .title { margin: 0 0 0.75rem; font-size: 1.1rem; font-weight: 700; }
        .message { margin: 0; color: var(--text-secondary, #8b949e); line-height: 1.55; }
        .message-subject {
          color: var(--accent, #58a6ff);
          font-family: var(--font-mono, ui-monospace, monospace);
          font-weight: 600;
          word-break: break-word;
        }
        .actions {
          display: flex;
          justify-content: flex-end;
          gap: 0.75rem;
          margin-top: 1.25rem;
        }
        button {
          display: inline-flex;
          align-items: center;
          justify-content: center;
          gap: 0.5rem;
          flex: 1 1 0;
          min-width: 0;
          border-radius: var(--radius-md, 0.75rem);
          border: 1px solid var(--border-dark, #30363d);
          padding: 0.625rem 1rem;
          font: inherit;
          font-size: 0.875rem;
          font-weight: 600;
          line-height: 1.25;
          cursor: pointer;
          transition: background-color 120ms ease, transform 120ms ease, border-color 120ms ease, color 120ms ease, box-shadow 120ms ease;
        }
        button:hover { transform: translateY(-1px); }
        button:focus-visible {
          outline: none;
          box-shadow: 0 0 0 3px rgba(88, 166, 255, 0.22);
        }
        .cancel {
          background: var(--bg-dark-card, #161b22);
          border-color: var(--border-dark, #30363d);
          color: var(--text-primary, #f0f6fc);
        }
        .cancel:hover {
          border-color: var(--accent, #58a6ff);
          color: var(--accent, #58a6ff);
        }
        .confirm {
          background: rgba(248, 81, 73, 0.12);
          border-color: rgba(248, 81, 73, 0.35);
          color: #ffb4ad;
        }
        .confirm:hover {
          background: rgba(248, 81, 73, 0.18);
          border-color: var(--danger, #f85149);
          color: #ffd2cd;
        }
        @media (max-width: 520px) {
          .actions {
            flex-direction: column;
          }
        }
      </style>
      <div class="backdrop">
        <div class="dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title" aria-describedby="confirm-message">
          <h2 id="confirm-title" class="title"></h2>
          <p id="confirm-message" class="message"></p>
          <div class="actions">
            <button type="button" class="cancel"></button>
            <button type="button" class="confirm"></button>
          </div>
        </div>
      </div>
    `;

    const title = shadow.getElementById('confirm-title') as HTMLHeadingElement;
    const message = shadow.getElementById('confirm-message') as HTMLParagraphElement;
    const cancelButton = shadow.querySelector('.cancel') as HTMLButtonElement;
    const confirmButton = shadow.querySelector('.confirm') as HTMLButtonElement;
    const backdrop = shadow.querySelector('.backdrop') as HTMLDivElement;
    const dialog = shadow.querySelector('.dialog') as HTMLDivElement;

    title.textContent = options.title;
    if (options.messageSubject) {
      message.replaceChildren(
        document.createTextNode(options.messagePrefix ?? ''),
        Object.assign(document.createElement('span'), {
          className: 'message-subject',
          textContent: options.messageSubject,
        }),
        document.createTextNode(options.messageSuffix ?? ''),
      );
    } else {
      message.textContent = options.message ?? '';
    }
    cancelButton.textContent = options.cancelLabel ?? 'Cancel';
    confirmButton.textContent = options.confirmLabel ?? 'Delete';

    const cleanup = (confirmed: boolean) => {
      document.removeEventListener('keydown', onKeydown);
      document.body.style.overflow = previousOverflow;
      overlay.remove();
      previousActive?.focus();
      resolve(confirmed);
    };

    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') cleanup(false);
    };

    backdrop.addEventListener('click', event => {
      const target = event.composedPath()[0];
      if (target instanceof Node && dialog.contains(target)) return;
      cleanup(false);
    });
    cancelButton.addEventListener('click', () => cleanup(false));
    confirmButton.addEventListener('click', () => cleanup(true));
    document.addEventListener('keydown', onKeydown);
    document.body.style.overflow = 'hidden';
    document.body.appendChild(overlay);
    cancelButton.focus();
  });
}
