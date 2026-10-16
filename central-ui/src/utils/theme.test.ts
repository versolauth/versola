import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getItem = vi.fn();
const setItem = vi.fn();
const setAttribute = vi.fn();
const getAttribute = vi.fn();
const querySelector = vi.fn();
const dispatchEvent = vi.fn();
const addEventListener = vi.fn();
const removeEventListener = vi.fn();
let systemDark = false;

async function loadTheme() {
  vi.resetModules();
  vi.stubGlobal('localStorage', { getItem, setItem });
  vi.stubGlobal('document', { documentElement: { setAttribute, getAttribute }, querySelector });
  vi.stubGlobal('window', {
    dispatchEvent,
    matchMedia: (query: string) => ({ media: query, get matches() { return systemDark; }, addEventListener, removeEventListener }),
  });
  vi.stubGlobal('CustomEvent', class { constructor(readonly type: string, readonly init: unknown) {} });
  return import('./theme');
}

/** Fires the OS colour-scheme change the module subscribed to. */
function changeSystemTheme(dark: boolean) {
  systemDark = dark;
  for (const [, listener] of addEventListener.mock.calls) (listener as () => void)();
}

beforeEach(() => {
  getItem.mockReset();
  setItem.mockReset();
  setAttribute.mockReset();
  getAttribute.mockReset();
  getAttribute.mockReturnValue(null);
  querySelector.mockReset();
  querySelector.mockReturnValue(null);
  dispatchEvent.mockReset();
  addEventListener.mockReset();
  removeEventListener.mockReset();
  systemDark = false;
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('theme preference', () => {
  it('defaults to auto and resolves it through the OS preference', async () => {
    getItem.mockReturnValue(null);
    systemDark = true;
    const { getStoredPreference, getResolvedTheme } = await loadTheme();

    expect(getStoredPreference()).toBe('auto');
    expect(getResolvedTheme()).toBe('dark');
  });

  it('keeps honouring a light or dark value stored before auto existed', async () => {
    getItem.mockReturnValue('light');
    systemDark = true;
    const { getStoredPreference, getResolvedTheme } = await loadTheme();

    expect(getStoredPreference()).toBe('light');
    expect(getResolvedTheme()).toBe('light');
  });

  it('treats an unknown stored value as auto', async () => {
    getItem.mockReturnValue('sepia');
    const { getStoredPreference } = await loadTheme();

    expect(getStoredPreference()).toBe('auto');
  });

  it('persists the choice and paints the resolved theme with both attributes', async () => {
    const { setThemePreference } = await loadTheme();

    expect(setThemePreference('dark')).toBe('dark');

    expect(setItem).toHaveBeenCalledWith('versola-theme', 'dark');
    expect(setAttribute).toHaveBeenCalledWith('data-theme', 'dark');
    expect(setAttribute).toHaveBeenCalledWith('data-theme-pref', 'dark');
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
  });

  it('does not announce a change when the painted theme stays the same', async () => {
    getAttribute.mockReturnValue('light');
    const { setThemePreference } = await loadTheme();

    setThemePreference('auto');

    expect(setAttribute).toHaveBeenCalledWith('data-theme-pref', 'auto');
    expect(dispatchEvent).not.toHaveBeenCalled();
  });
});

describe('theme storage fallback', () => {
  it('keeps the chosen preference for the session when storage throws', async () => {
    getItem.mockImplementation(() => { throw new Error('storage unavailable'); });
    setItem.mockImplementation(() => { throw new Error('storage unavailable'); });
    const { setThemePreference, getStoredPreference, getResolvedTheme } = await loadTheme();

    setThemePreference('dark');

    expect(getStoredPreference()).toBe('dark');
    expect(getResolvedTheme()).toBe('dark');
    expect(setAttribute).toHaveBeenLastCalledWith('data-theme-pref', 'dark');
  });

  it('updates the favicon when the theme changes', async () => {
    const favicon = { setAttribute: vi.fn() };
    querySelector.mockReturnValue(favicon);
    const { setThemePreference } = await loadTheme();

    setThemePreference('light');
    setThemePreference('dark');

    expect(favicon.setAttribute).toHaveBeenNthCalledWith(1, 'href', 'logo-shield-light.svg');
    expect(favicon.setAttribute).toHaveBeenNthCalledWith(2, 'href', 'logo-shield.svg');
  });
});

describe('following the operating system', () => {
  it('repaints on an OS change while the choice is auto', async () => {
    getItem.mockReturnValue('auto');
    const { watchSystemTheme } = await loadTheme();
    const stop = watchSystemTheme();

    changeSystemTheme(true);
    expect(setAttribute).toHaveBeenLastCalledWith('data-theme-pref', 'auto');
    expect(setAttribute).toHaveBeenCalledWith('data-theme', 'dark');

    stop();
    expect(removeEventListener).toHaveBeenCalledTimes(1);
  });

  it('ignores an OS change when a fixed theme is chosen', async () => {
    getItem.mockReturnValue('light');
    const { watchSystemTheme } = await loadTheme();
    watchSystemTheme();

    changeSystemTheme(true);

    expect(setAttribute).not.toHaveBeenCalled();
  });
});
