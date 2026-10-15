import { describe, expect, it } from 'vitest';
import { pickClientName } from './client-name';

const names = { en: 'Acme', ru: 'Акме', 'pt-BR': 'Acme Brasil', de: 'Acme DE' };

describe('pickClientName', () => {
  it('prefers the exact locale, ignoring case', () => {
    expect(pickClientName('ru', 'Acme', names)).toBe('Акме');
    expect(pickClientName('pt-br', 'Acme', names)).toBe('Acme Brasil');
    expect(pickClientName('PT-BR', 'Acme', { 'pt-br': 'lower' })).toBe('lower');
  });

  it('falls back from a regional locale to its language', () => {
    expect(pickClientName('ru-RU', 'x', names)).toBe('Акме');
    expect(pickClientName('de_AT', 'x', names)).toBe('Acme DE');
  });

  it('then to the default locale, the name field, and any entry', () => {
    expect(pickClientName('fr', 'Server pick', names)).toBe('Acme');
    expect(pickClientName('fr', 'Server pick', { ru: 'Акме', de: 'Acme DE' }, 'de')).toBe('Acme DE');
    expect(pickClientName('fr', 'Server pick', { ru: 'Акме' })).toBe('Server pick');
    expect(pickClientName('fr', undefined, { ru: 'Акме' })).toBe('Акме');
  });

  it('uses the name field when there are no names, and nothing when there is nothing', () => {
    expect(pickClientName('ru', 'Acme', undefined)).toBe('Acme');
    expect(pickClientName('ru', '  ', {})).toBeUndefined();
    expect(pickClientName('ru', undefined, { ru: '' })).toBeUndefined();
  });
});
