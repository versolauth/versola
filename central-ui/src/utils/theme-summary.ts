import type { OAuthClient, ResolvedThemeTokens, ThemeOverrides, ThemePreset, ThemeRecord } from '../types';

const ORDER: Array<keyof ThemeOverrides> = ['brand', 'surface', 'background', 'shape', 'mode'];
const SHORT: Record<keyof ThemeOverrides, string> = { brand: 'brand', surface: 'page', background: 'background', shape: 'shape', mode: 'scheme' };
const LONG: Record<keyof ThemeOverrides, string> = { brand: 'brand colour', surface: 'page background', background: 'background style', shape: 'shape', mode: 'colour scheme' };

/** The tokens a theme overrides, in the order the editor shows them. */
export function overrideKeys(overrides: ThemeOverrides | null | undefined): Array<keyof ThemeOverrides> {
  return ORDER.filter(key => overrides?.[key] !== undefined);
}

export const tokenName = (key: keyof ThemeOverrides) => SHORT[key];
export const tokenLongName = (key: keyof ThemeOverrides) => LONG[key];

/** `Overrides: brand, shape +1`: names, never a bare count. */
export function overridesText(overrides: ThemeOverrides | null | undefined): string {
  const names = overrideKeys(overrides).map(tokenName);
  if (names.length === 0) return 'No overrides';
  const shown = names.slice(0, 2).join(', ');
  return `Overrides: ${names.length > 2 ? `${shown} +${names.length - 2}` : shown}`;
}

/** What a visual theme looks like: its base's values with its own overrides laid over them. */
export function resolveTokens(theme: ThemeRecord, presets: ThemePreset[]): ResolvedThemeTokens | undefined {
  if (!theme.tokens) return undefined;
  const base = presets.find(preset => preset.id === theme.tokens!.base)?.tokens;
  return base ? { ...base, ...(theme.tokens.overrides ?? {}) } : undefined;
}

/** What a built-in theme sets, for the line under the picker: `Brand #155e75, soft corners, bold page, glow background`.
 *  A built-in theme has no colour scheme of its own: the scheme is the visitor's (Auto) or the theme's Colour scheme control. */
export function presetHint(tokens: ResolvedThemeTokens): string {
  const corners = { round: 'round', soft: 'soft', sharp: 'sharp' }[tokens.shape] ?? tokens.shape;
  return `Brand ${tokens.brand}, ${corners} corners, ${tokens.surface} page, ${tokens.background} background.`;
}

/** The clients that name the theme (a client with none uses `default`). */
export function clientsUsingTheme(clients: OAuthClient[], themeId: string): string[] {
  return clients.filter(client => (client.theme || 'default') === themeId).map(client => client.id);
}

/** `storefront-web and shop-mobile`, `a, b and c`. */
export function joinNames(names: string[]): string {
  if (names.length <= 1) return names.join('');
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

export interface ThemeEditState {
  base: string;
  overrides: ThemeOverrides;
  variables: Record<string, string>;
  css: string;
}

const sameVariables = (a: Record<string, string>, b: Record<string, string>) => {
  const left = Object.entries(a).sort(([x], [y]) => x.localeCompare(y));
  const right = Object.entries(b).sort(([x], [y]) => x.localeCompare(y));
  return left.length === right.length && left.every(([name, value], index) => name === right[index][0] && value.trim() === right[index][1].trim());
};

/** What differs between the stored theme and the editor, named for the bar: `brand colour, shape, token overrides, custom CSS`. */
export function unsavedNames(saved: ThemeEditState, now: ThemeEditState): string[] {
  const names: string[] = [];
  if (saved.base !== now.base) names.push('base theme');
  for (const key of ORDER) if (saved.overrides[key] !== now.overrides[key]) names.push(LONG[key]);
  if (!sameVariables(saved.variables, now.variables)) names.push('token overrides');
  if (saved.css !== now.css) names.push('custom CSS');
  return names;
}

/** The Base column of the themes list: `Overrides: brand, shape · Token overrides · Custom CSS`. */
export function themeDetailText(theme: ThemeRecord): string {
  const parts = [overridesText(theme.tokens?.overrides)];
  if (Object.keys(theme.tokens?.variables ?? {}).length > 0) parts.push('Token overrides');
  if (theme.css.trim() !== '') parts.push('Custom CSS');
  return parts.join(' · ');
}
