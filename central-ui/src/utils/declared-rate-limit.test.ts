import { describe, expect, it } from 'vitest';
import {
  declaredFromDraft, declaredSummary, draftFromDeclared, hasErrors, newDeclaredDraft, validateDeclared, type DeclaredDraft, type DeclaredRateLimit,
} from './declared-rate-limit';

const limit: DeclaredRateLimit = { limits: [{ limit: 600, windowSeconds: 60 }, { limit: 10000, windowSeconds: 3600 }], burst: 50, by: 'subject', mode: 'enforce' };
const draft = (patch: Partial<DeclaredDraft> = {}): DeclaredDraft => ({ ...draftFromDeclared(limit)!, ...patch });

describe('declared rate limit wire form', () => {
  it('round-trips, and leaves out what is the default', () => {
    expect(declaredFromDraft(draftFromDeclared(limit))).toEqual(limit);
    const minimal = declaredFromDraft(draftFromDeclared({ limits: [{ limit: 5, windowSeconds: 1 }], burst: 1, by: 'none', mode: 'shadow' }));
    expect(minimal).toEqual({ limits: [{ limit: 5, windowSeconds: 1 }], mode: 'shadow' });
  });

  it('no limit is null both ways', () => {
    expect(draftFromDeclared(undefined)).toBeNull();
    expect(draftFromDeclared(null)).toBeNull();
    expect(declaredFromDraft(null)).toBeNull();
  });

  it('a new limit starts in shadow, for the whole owner, with one empty window', () => {
    expect(newDeclaredDraft()).toEqual({ mode: 'shadow', by: 'none', limits: [{ limit: '', windowSeconds: '' }], burst: '' });
  });
});

describe('validateDeclared', () => {
  it('accepts a well-formed limit on either owner', () => {
    expect(validateDeclared(draft(), 'endpoint')).toEqual({});
    expect(validateDeclared(draft(), 'client')).toEqual({});
    expect(validateDeclared(null, 'client')).toEqual({});
  });

  it('says what central says', () => {
    expect(validateDeclared(draft({ burst: '601' }), 'endpoint').burst).toBe('burst must not exceed the smallest limit');
    expect(validateDeclared(draft({ burst: '0' }), 'endpoint').burst).toBe('burst must be between 1 and 1000000');
    expect(validateDeclared(draft({ limits: [] }), 'endpoint').limits).toBe('policy resource-endpoint: limits must have at least one window');
    expect(validateDeclared(draft({ limits: [] }), 'client').limits).toBe('policy oauth-client: limits must have at least one window');
    expect(validateDeclared(draft({ limits: [{ limit: '5', windowSeconds: '60' }, { limit: '9', windowSeconds: '60' }] }), 'endpoint').limits).toBe('two limits have the same window');
    expect(validateDeclared(draft({ limits: [{ limit: '', windowSeconds: '0' }] }), 'endpoint')).toMatchObject({
      'limit.0': 'limit must be between 1 and 1000000', 'window.0': 'windowSeconds must be between 1 and 86400',
    });
  });

  it('refuses by client on a client, where it would mean the same as none', () => {
    expect(validateDeclared(draft({ by: 'client' }), 'client').by).toMatch(/subject or none/);
    expect(hasErrors(draft({ by: 'client' }), 'endpoint')).toBe(false);
  });

  it('refuses more windows than central allows', () => {
    const many = Array.from({ length: 9 }, (_, i) => ({ limit: '10', windowSeconds: String(i + 1) }));
    expect(validateDeclared(draft({ limits: many, burst: '' }), 'endpoint').limits).toMatch(/at most 8 windows/);
  });
});

describe('declaredSummary', () => {
  it('reads as one line', () => {
    expect(declaredSummary(limit, 'endpoint')).toBe('Enforce · 600 / 60 s + 10000 / 3600 s · each user · burst 50');
    expect(declaredSummary({ limits: [{ limit: 5, windowSeconds: 1 }] }, 'client')).toBe('Shadow · 5 / 1 s · client as a whole');
    expect(declaredSummary(undefined, 'client')).toBe('No limit');
  });
});
