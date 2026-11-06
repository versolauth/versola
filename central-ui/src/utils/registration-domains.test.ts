import { describe, expect, it } from 'vitest';
import { validateRegistrationDomain, validateRegistrationDomains } from './validators';

describe('validateRegistrationDomain', () => {
  it.each(['example.com', '*.example.com', 'Mail.Example.CO.uk', 'xn--e1afmkfd.xn--p1ai', 'пример.рф', '*.пример.рф', 'a-b.example.com'])(
    'accepts %s', entry => expect(validateRegistrationDomain(entry)).toBeUndefined());

  it.each(['example', '', '*.com.', '*example.com', '*.*.example.com', '-a.com', 'a-.com', 'a..com', 'exa mple.com', 'a_b.com', 'user@example.com', '.example.com', 'example.com.'])(
    'rejects %j', entry => expect(validateRegistrationDomain(entry)).toBeDefined());

  it('rejects a wildcard with no domain behind it', () => {
    expect(validateRegistrationDomain('*.')).toBeDefined();
    // A top-level domain behind the wildcard would cover everyone under it.
    expect(validateRegistrationDomain('*.com')).toBeDefined();
    expect(validateRegistrationDomain('*.example.com')).toBeUndefined();
  });

  it('rejects an entry over 253 characters', () => {
    const long = `${'a'.repeat(60)}.`.repeat(5) + 'com';
    expect(long.length).toBeGreaterThan(253);
    expect(validateRegistrationDomain(long)).toContain('253');
  });
});

describe('validateRegistrationDomains', () => {
  it('accepts an empty list and a valid one', () => {
    expect(validateRegistrationDomains([])).toBeUndefined();
    expect(validateRegistrationDomains(['example.com', '*.example.org'])).toBeUndefined();
  });

  it('rejects duplicates after lower-casing', () => {
    expect(validateRegistrationDomains(['Example.com', 'example.COM'])).toContain('more than once');
  });

  it('keeps a wildcard and its apex distinct', () => {
    expect(validateRegistrationDomains(['example.com', '*.example.com'])).toBeUndefined();
  });

  it('caps the list at 500 entries', () => {
    const ok = Array.from({ length: 500 }, (_, i) => `d${i}.example.com`);
    expect(validateRegistrationDomains(ok)).toBeUndefined();
    expect(validateRegistrationDomains([...ok, 'extra.example.com'])).toContain('500');
  });

  it('reports the first malformed entry', () => {
    expect(validateRegistrationDomains(['example.com', 'nodot'])).toContain('nodot');
  });
});
