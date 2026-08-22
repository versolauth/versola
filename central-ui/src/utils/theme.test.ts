import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getItem = vi.fn();
const setItem = vi.fn();
const setAttribute = vi.fn();
const dispatchEvent = vi.fn();

async function loadTheme() {
  vi.resetModules();
  vi.stubGlobal('localStorage', { getItem, setItem });
  vi.stubGlobal('document', { documentElement: { setAttribute } });
  vi.stubGlobal('window', { dispatchEvent });
  vi.stubGlobal('CustomEvent', class { constructor(readonly type: string, readonly init: unknown) {} });
  return import('./theme');
}

beforeEach(() => {
  getItem.mockReset();
  setItem.mockReset();
  setAttribute.mockReset();
  dispatchEvent.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('theme storage fallback', () => {
  it('keeps the selected theme available for toggling when storage throws', async () => {
    getItem.mockImplementation(() => { throw new Error('storage unavailable'); });
    setItem.mockImplementation(() => { throw new Error('storage unavailable'); });
    const { setTheme, toggleTheme } = await loadTheme();

    setTheme('light');

    expect(toggleTheme()).toBe('dark');
    expect(setAttribute).toHaveBeenLastCalledWith('data-theme', 'dark');
  });

  it('uses a valid persisted theme when storage is available', async () => {
    getItem.mockReturnValue('light');
    const { getStoredTheme } = await loadTheme();

    expect(getStoredTheme()).toBe('light');
  });
});