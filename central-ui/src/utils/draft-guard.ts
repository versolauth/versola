import { confirmDestructiveAction } from './confirm-dialog';

/** Asked before a side-panel editor closes with changes in it (Escape, backdrop, Cancel, crumb). */
export function confirmDiscardDraft(): Promise<boolean> {
  return confirmDestructiveAction({
    title: 'Discard changes?',
    message: 'What you entered here has not been saved.',
    confirmLabel: 'Discard',
    cancelLabel: 'Keep editing',
  });
}
