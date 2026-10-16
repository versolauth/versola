/** The theme that is actually painted. */
export type ThemeName = 'dark' | 'light';
/** What the visitor chose: a fixed theme, or `auto` to follow the operating system. */
export type ThemePreference = 'auto' | ThemeName;

export const THEME_PREFERENCES: readonly ThemePreference[] = ['auto', 'light', 'dark'];

const STORAGE_KEY = 'versola-theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';

/** Fired on `window` whenever the painted theme changes (a new choice, or the
  * OS switching while the choice is `auto`), with the resolved ThemeName as
  * detail, so components that can't rely on CSS alone (e.g. versola-logo,
  * which picks between two SVG markups rather than swapping colours via
  * custom properties) can re-render. Most components just consume the CSS
  * custom properties this module keeps up to date. */
export const THEME_CHANGE_EVENT = 'versola-theme-change';

const FAVICON_PATHS: Record<ThemeName, string> = {
  dark: 'logo-shield.svg',
  light: 'logo-shield-light.svg',
};

/** Auto is the default: a first visit follows the device, like the login
  * forms. Mirrors the fallback baked into index.html's inline pre-paint script
  * — keep both in sync if this changes. */
const DEFAULT_PREFERENCE: ThemePreference = 'auto';
let sessionPreference: ThemePreference = DEFAULT_PREFERENCE;

function isPreference(value: string | null): value is ThemePreference {
  return value === 'auto' || value === 'dark' || value === 'light';
}

function systemTheme(): ThemeName {
  try {
    return window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light';
  } catch {
    // No matchMedia (an old engine, a test runtime): light, like index.html.
    return 'light';
  }
}

/** The theme a preference paints. Same rule as index.html's pre-paint script. */
export function resolveTheme(preference: ThemePreference): ThemeName {
  return preference === 'auto' ? systemTheme() : preference;
}

/** Reads the persisted choice, falling back to the default when nothing (or
  * something invalid) is stored — e.g. localStorage disabled, or a value
  * left over from a future version of this app. A plain `light`/`dark` stored
  * before `auto` existed is still a valid choice. */
export function getStoredPreference(): ThemePreference {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    sessionPreference = isPreference(stored) ? stored : DEFAULT_PREFERENCE;
    return sessionPreference;
  } catch {
    // Private-browsing Safari throws on localStorage access rather than just
    // being unavailable; don't let a theming preference break the app.
    return sessionPreference;
  }
}

/** The theme currently painted for the stored (or session) preference. */
export function getResolvedTheme(): ThemeName {
  return resolveTheme(getStoredPreference());
}

function persistPreference(preference: ThemePreference): void {
  try {
    localStorage.setItem(STORAGE_KEY, preference);
  } catch {
    // Best-effort only — see getStoredPreference.
  }
}

function updateFavicon(theme: ThemeName): void {
  document.querySelector<HTMLLinkElement>('link[rel="icon"]')?.setAttribute('href', FAVICON_PATHS[theme]);
}

/** Paints a theme by setting `data-theme` on <html> (and records the choice in
  * `data-theme-pref`) — every component's shadow root inherits the resulting
  * custom-property values (defined in index.html) through normal CSS
  * inheritance, so nothing else needs to be touched. The very first
  * application happens before this module is even loaded, via index.html's
  * inline pre-paint script; this is what later choices call. */
export function applyTheme(preference: ThemePreference): ThemeName {
  const theme = resolveTheme(preference);
  const root = document.documentElement;
  const changed = root.getAttribute('data-theme') !== theme;
  root.setAttribute('data-theme', theme);
  root.setAttribute('data-theme-pref', preference);
  updateFavicon(theme);
  if (changed) window.dispatchEvent(new CustomEvent<ThemeName>(THEME_CHANGE_EVENT, { detail: theme }));
  return theme;
}

export function setThemePreference(preference: ThemePreference): ThemeName {
  sessionPreference = preference;
  persistPreference(preference);
  return applyTheme(preference);
}

/** Keeps the painted theme in step with the operating system while the choice
  * is `auto`. Returns the function that stops watching. */
export function watchSystemTheme(): () => void {
  let query: MediaQueryList;
  try {
    query = window.matchMedia(DARK_QUERY);
  } catch {
    return () => {};
  }
  const follow = () => {
    if (getStoredPreference() === 'auto') applyTheme('auto');
  };
  query.addEventListener('change', follow);
  return () => query.removeEventListener('change', follow);
}
