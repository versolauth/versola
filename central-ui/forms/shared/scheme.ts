/** The visitor's own colour scheme: one choice per browser, kept in localStorage on the auth origin. */
export type SchemeChoice = 'auto' | 'light' | 'dark';

/** Read by the page shell's inline script too (ConversationRenderService.SchemeScript): keep the two in step. */
export const SCHEME_STORAGE_KEY = 'versola.colorScheme';

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

// Storage can throw on access (blocked cookies, some private modes): the choice then lives for the page only.
const guarded = <T,>(action: () => T, fallback: T): T => {
  try { return action(); } catch (_) { return fallback; }
};

const browserStorage = (): Store | undefined => guarded(() => window.localStorage, undefined);

export function readScheme(store: Store | undefined = browserStorage()): SchemeChoice {
  const stored = store ? guarded(() => store.getItem(SCHEME_STORAGE_KEY), null) : null;
  return stored === 'light' || stored === 'dark' ? stored : 'auto';
}

/**
 * Applies the choice to the page at once and remembers it. Auto clears the inline value and the stored
 * one, so the stylesheet's own `color-scheme` (the device's, for a default theme) is back in force.
 */
export function applyScheme(
  choice: SchemeChoice,
  root: { style: { colorScheme: string } } = document.documentElement,
  store: Store | undefined = browserStorage(),
) {
  root.style.colorScheme = choice === 'auto' ? '' : choice;
  if (!store) return;
  guarded(() => (choice === 'auto' ? store.removeItem(SCHEME_STORAGE_KEY) : store.setItem(SCHEME_STORAGE_KEY, choice)), undefined);
}

/** True when the theme fixes the scheme (a fixed Mode sets `--scheme-locked: 1`, see common.css): there is nothing to choose. */
export const schemeLocked = (root: Element = document.documentElement) =>
  getComputedStyle(root).getPropertyValue('--scheme-locked').trim() === '1';
