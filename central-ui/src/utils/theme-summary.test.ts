import { describe, expect, it } from 'vitest';
import { clientsUsingTheme, joinNames, overrideKeys, overridesText, presetHint, resolveTokens, unsavedNames } from './theme-summary';

describe('theme summary', () => {
  it('names the overridden tokens in the editor order', () => {
    expect(overrideKeys({ shape: 'soft', brand: '#112233' })).toEqual(['brand', 'shape']);
    expect(overridesText({ shape: 'soft', brand: '#112233', mode: 'dark' })).toBe('Overrides: brand, shape +1');
    expect(overridesText({ brand: '#112233' })).toBe('Overrides: brand');
    expect(overridesText({})).toBe('No overrides');
  });

  it('lays the overrides over the base', () => {
    const presets = [{ id: 'versola', title: {}, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } as const }];
    expect(resolveTokens({ id: 'a', css: '', tenantId: null, tokens: { base: 'versola', overrides: { brand: '#ff0000' } } }, presets)?.brand).toBe('#ff0000');
    expect(resolveTokens({ id: 'a', css: '', tenantId: null }, presets)).toBeUndefined();
    expect(resolveTokens({ id: 'a', css: '', tenantId: null, tokens: { base: 'gone' } }, presets)).toBeUndefined();
  });

  it('counts a client with no theme as using default', () => {
    const clients = [{ id: 'a', theme: 'x' }, { id: 'b' }, { id: 'c', theme: 'default' }] as never[];
    expect(clientsUsingTheme(clients, 'default')).toEqual(['b', 'c']);
    expect(clientsUsingTheme(clients, 'x')).toEqual(['a']);
  });

  it('joins names the way a sentence does', () => {
    expect(joinNames(['a'])).toBe('a');
    expect(joinNames(['a', 'b'])).toBe('a and b');
    expect(joinNames(['a', 'b', 'c'])).toBe('a, b and c');
  });

  it('names what changed since the stored theme', () => {
    const saved = { base: 'versola', overrides: { brand: '#111111' }, css: '' };
    expect(unsavedNames(saved, saved, 'extra CSS')).toEqual([]);
    expect(unsavedNames(saved, { base: 'studio', overrides: { brand: '#222222', shape: 'sharp' as const }, css: 'x{}' }, 'extra CSS'))
      .toEqual(['base theme', 'brand colour', 'shape', 'extra CSS']);
  });

  it('says what a built-in theme sets: colour, shape, page and background, never a colour scheme', () => {
    expect(presetHint({ brand: '#ff6b4a', shape: 'round', surface: 'bold', mode: 'auto', background: 'glow' })).toBe('Brand #ff6b4a, round corners, bold page, glow background.');
    expect(presetHint({ brand: '#14130f', shape: 'sharp', surface: 'neutral', mode: 'auto', background: 'flat' })).not.toMatch(/dark|light/i);
  });
});
