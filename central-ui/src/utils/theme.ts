export type ThemeName = 'dark' | 'light';

const STORAGE_KEY = 'versola-theme';

/** Fired on `window` whenever the active theme changes, so components that
  * can't rely on CSS alone (e.g. versola-logo, which picks between two
  * entirely different SVG markups rather than swapping colors via custom
  * properties) can re-render. Not needed by most components — they just
  * consume the CSS custom properties this module keeps up to date. */
export const THEME_CHANGE_EVENT = 'versola-theme-change';

/** Dark is the original console look and stays the default for anyone who
  * hasn't made an explicit choice yet. Mirrors the fallback baked into
  * index.html's inline pre-paint script — keep both in sync if this changes. */
const DEFAULT_THEME: ThemeName = 'dark';
let sessionTheme: ThemeName = DEFAULT_THEME;

function isThemeName(value: string | null): value is ThemeName {
  return value === 'dark' || value === 'light';
}

/** Reads the persisted theme choice, falling back to the default when nothing
  * (or something invalid) is stored — e.g. localStorage disabled, or a value
  * left over from a future version of this app. */
export function getStoredTheme(): ThemeName {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    sessionTheme = isThemeName(stored) ? stored : DEFAULT_THEME;
    return sessionTheme;
  } catch {
    // Private-browsing Safari throws on localStorage access rather than just
    // being unavailable; don't let a theming preference break the app.
    return sessionTheme;
  }
}

function persistTheme(theme: ThemeName): void {
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // Best-effort only — see getStoredTheme.
  }
}

/** Applies a theme by setting `data-theme` on <html> — every component's
  * shadow root inherits the resulting custom-property values (defined in
  * index.html) through normal CSS inheritance, so nothing else needs to be
  * touched. The very first application happens before this module is even
  * loaded, via index.html's inline pre-paint script; this is what later
  * toggles call. */
export function applyTheme(theme: ThemeName): void {
  document.documentElement.setAttribute('data-theme', theme);
  window.dispatchEvent(new CustomEvent<ThemeName>(THEME_CHANGE_EVENT, { detail: theme }));
}

export function setTheme(theme: ThemeName): void {
  sessionTheme = theme;
  applyTheme(theme);
  persistTheme(theme);
}

export function toggleTheme(): ThemeName {
  const next: ThemeName = getStoredTheme() === 'dark' ? 'light' : 'dark';
  setTheme(next);
  return next;
}
