import { describe, expect, it } from 'vitest';
import {
  draftFromPolicies, emptyDraft, isEmptyPolicies, newPolicyDraft, podsText, policiesFromDraft, summary, validateDraft, type PolicyDraft, type RateLimitPolicies,
} from './rate-limit-policies';

const valid = (patch: Partial<PolicyDraft> = {}): PolicyDraft => ({
  ...newPolicyDraft(), name: 'per-client', limits: [{ limit: '100', windowSeconds: '60' }], ...patch,
});

describe('rate limit policies', () => {
  it('an empty draft sends an empty list, which removes the policies', () => {
    expect(policiesFromDraft(emptyDraft())).toEqual({ policies: [] });
    expect(isEmptyPolicies({ policies: [] })).toBe(true);
    expect(isEmptyPolicies(undefined)).toBe(true);
    expect(draftFromPolicies(undefined)).toEqual(emptyDraft());
  });

  it('sends numbers, trims, leaves burst and cost out when empty, and keeps dimensions in a fixed order', () => {
    const body = policiesFromDraft({ replicas: '', policies: [valid({ name: ' per-client ', by: ['jkt', 'client'], burst: ' 5 ' })] });
    expect(body).toEqual({
      policies: [{ name: 'per-client', by: ['client', 'jkt'], scope: 'edge-wide', limits: [{ limit: 100, windowSeconds: 60 }], burst: 5, mode: 'shadow' }],
    });
  });

  it('round-trips a stored set, with burst and cost 1 reading as empty', () => {
    const set: RateLimitPolicies = {
      policies: [
        { name: 'a', by: ['client'], scope: 'endpoint', limits: [{ limit: 10, windowSeconds: 1 }, { limit: 500, windowSeconds: 3600 }], burst: 1, cost: 3, mode: 'enforce' },
      ],
    };
    const draft = draftFromPolicies(set);
    expect(draft.policies[0]).toMatchObject({ burst: '', cost: '3', mode: 'enforce', scope: 'endpoint' });
    expect(policiesFromDraft(draft).policies[0]).toMatchObject({ name: 'a', cost: 3, mode: 'enforce', limits: set.policies[0].limits });
  });

  it('accepts a valid policy', () => {
    expect(validateDraft({ replicas: '', policies: [valid()] })).toEqual({});
  });

  it('says what the server says about the name, a repeated name and the windows', () => {
    const errors = validateDraft({ replicas: '', policies: [valid({ name: 'Bad Name' }), valid(), valid(), valid({ name: 'two', limits: [{ limit: '1', windowSeconds: '60' }, { limit: '2', windowSeconds: '60' }] })] });
    expect(errors['0.name']).toContain('name must be 1 to 48 characters');
    expect(errors['1.name']).toBeUndefined();
    expect(errors['2.name']).toBe('two rate limit policies have the same name');
    expect(errors['3.limits']).toBe('two limits have the same window');
  });

  it('checks the ranges, burst against the smallest limit and cost against burst', () => {
    const errors = validateDraft({ replicas: '',
      policies: [
        valid({ limits: [{ limit: '0', windowSeconds: '86401' }] }),
        valid({ name: 'b', limits: [{ limit: '10', windowSeconds: '1' }, { limit: '500', windowSeconds: '3600' }], burst: '11' }),
        valid({ name: 'c', burst: '2', cost: '3' }),
        valid({ name: 'd', limits: [], }),
        valid({ name: 'e', cost: '1001', burst: '2000' }),
      ],
    });
    expect(errors['0.limit.0']).toBe('limit must be between 1 and 1000000');
    expect(errors['0.window.0']).toBe('windowSeconds must be between 1 and 86400');
    expect(errors['1.burst']).toBe('burst must not exceed the smallest limit');
    expect(errors['2.cost']).toBe('cost must not exceed burst, or a request could never pass');
    expect(errors['3.limits']).toBe('limits must have at least one window');
    expect(errors['4.cost']).toBe('cost must be between 1 and 1000');
  });

  it('an empty required field is an error and a number with letters is too', () => {
    const errors = validateDraft({ replicas: '', policies: [valid({ name: '', limits: [{ limit: '', windowSeconds: '6o' }] })] });
    expect(Object.keys(errors).sort()).toEqual(['0.limit.0', '0.name', '0.window.0']);
  });

  it('replicas: sent only when set, validated like the server, round-tripped, and read as 1 when absent', () => {
    expect(policiesFromDraft({ ...emptyDraft(), replicas: ' 3 ' })).toEqual({ replicas: 3, policies: [] });
    expect(policiesFromDraft(emptyDraft())).toEqual({ policies: [] });
    expect(validateDraft({ ...emptyDraft(), replicas: '0' }).replicas).toBe('replicas must be between 1 and 1000');
    expect(validateDraft({ ...emptyDraft(), replicas: '1001' }).replicas).toBe('replicas must be between 1 and 1000');
    expect(validateDraft({ ...emptyDraft(), replicas: 'two' }).replicas).toBeDefined();
    expect(validateDraft({ ...emptyDraft(), replicas: '12' })).toEqual({});
    expect(draftFromPolicies({ policies: [], replicas: 4 }).replicas).toBe('4');
    expect(draftFromPolicies({ policies: [] }).replicas).toBe('');
    expect(podsText(undefined)).toContain('One');
    expect(podsText({ policies: [], replicas: 4 })).toBe('4 (each pod enforces 1/4 of a limit)');
  });

  it('summarises a policy for the record card', () => {
    expect(summary({ name: 'a', by: ['client'], scope: 'endpoint', limits: [{ limit: 10, windowSeconds: 1 }, { limit: 500, windowSeconds: 3600 }], burst: 5, mode: 'shadow' }))
      .toBe('Shadow · 10 / 1 s + 500 / 3600 s · by client, per endpoint · burst 5');
    expect(summary({ name: 'b', limits: [{ limit: 1, windowSeconds: 1 }], mode: 'enforce' })).toBe('Enforce · 1 / 1 s · all calls');
  });
});
