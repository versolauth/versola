import { describe, expect, it } from 'vitest';
import {
  draftFromPolicy, emptyDraft, isEmptyPolicy, policyFromDraft, setSummary, startingLimitNote, validateDraft,
} from './overload-policy';

const draftWith = (values: Record<string, string>) => ({ ...emptyDraft(), ...values });

describe('policyFromDraft', () => {
  it('sends {} when nothing is set, so the edge returns to its deployment values', () => {
    expect(policyFromDraft(emptyDraft())).toEqual({});
    expect(policyFromDraft(draftWith({ 'inbound.maxInFlight': '  ' }))).toEqual({});
  });

  it('carries only the fields that were set, nested as the schema has them', () => {
    const body = policyFromDraft(draftWith({
      'inbound.mode': 'enforce',
      'inbound.adaptive.maxLimit': '1536',
      'inbound.startShare': '0.5',
      'outbound.breaker.enabled': 'true',
      'outbound.retryBudget.ratio': '0',
    }));
    expect(body).toEqual({
      inbound: { mode: 'enforce', startShare: 0.5, adaptive: { maxLimit: 1536 } },
      outbound: { breaker: { enabled: true }, retryBudget: { ratio: 0 } },
    });
  });

  it('sends an explicit false: off is a choice, not an absent field', () => {
    expect(policyFromDraft(draftWith({ 'outbound.breaker.enabled': 'false' }))).toEqual({ outbound: { breaker: { enabled: false } } });
  });
});

describe('draftFromPolicy', () => {
  it('round-trips a policy', () => {
    const policy = { inbound: { mode: 'shadow' as const, maxInFlight: 512, adaptive: { tolerance: 2 } }, outbound: { breaker: { enabled: false } } };
    expect(policyFromDraft(draftFromPolicy(policy))).toEqual(policy);
  });

  it('reads an absent or empty policy as all fields empty', () => {
    expect(draftFromPolicy(undefined)).toEqual(emptyDraft());
    expect(draftFromPolicy({})).toEqual(emptyDraft());
  });

  it('ignores a leftover adaptive.enabled, as the server does', () => {
    const legacy = { inbound: { adaptive: { enabled: true, minLimit: 8 } } } as never;
    expect(policyFromDraft(draftFromPolicy(legacy))).toEqual({ inbound: { adaptive: { minLimit: 8 } } });
  });
});

describe('isEmptyPolicy / setSummary', () => {
  it('knows when nothing is set', () => {
    expect(isEmptyPolicy(undefined)).toBe(true);
    expect(isEmptyPolicy({})).toBe(true);
    expect(isEmptyPolicy({ inbound: {} })).toBe(true);
    expect(isEmptyPolicy({ inbound: { mode: 'off' } })).toBe(false);
  });

  it('lists what is set, without the modes', () => {
    expect(setSummary({ inbound: { mode: 'enforce', maxInFlight: 512, adaptive: { maxLimit: 1536 } } }))
      .toEqual(['maxInFlight 512', 'adaptive.maxLimit 1536']);
  });
});

describe('validateDraft', () => {
  it('accepts an empty draft and the edges of every range', () => {
    expect(validateDraft(emptyDraft())).toEqual({});
    expect(validateDraft(draftWith({
      'inbound.maxInFlight': '100000', 'inbound.continuationShare': '1', 'inbound.startShare': '0.01', 'inbound.retryAfterSeconds': '60',
      'inbound.adaptive.minLimit': '1', 'inbound.adaptive.maxLimit': '100000', 'inbound.adaptive.tolerance': '5',
      'inbound.adaptive.smoothing': '1', 'inbound.adaptive.lagTargetMillis': '5',
      'outbound.requestTimeoutMillis': '600000', 'outbound.breaker.probeCalls': '100', 'outbound.retryBudget.minPerSecond': '1000',
      'outbound.retryBudget.ratio': '0',
    }))).toEqual({});
  });

  it('says what the server says, with the range written the way it writes it', () => {
    const errors = validateDraft(draftWith({
      'inbound.maxInFlight': '0',
      'inbound.continuationShare': '1.5',
      'inbound.retryAfterSeconds': '600',
      'inbound.adaptive.tolerance': '0.5',
      'outbound.retryBudget.ratio': '2',
      'outbound.retryBudget.minPerSecond': '1001',
    }));
    expect(errors['inbound.maxInFlight']).toBe('inbound.maxInFlight must be between 1 and 100000');
    expect(errors['inbound.continuationShare']).toBe('inbound.continuationShare must be between 0.01 and 1.0');
    expect(errors['inbound.retryAfterSeconds']).toBe('inbound.retryAfterSeconds must be between 1 and 60');
    expect(errors['inbound.adaptive.tolerance']).toBe('inbound.adaptive.tolerance must be between 1.0 and 5.0');
    expect(errors['outbound.retryBudget.ratio']).toBe('outbound.retryBudget.ratio must be between 0.0 and 1.0');
    expect(errors['outbound.retryBudget.minPerSecond']).toBe('outbound.retryBudget.minPerSecond must be between 0.0 and 1000.0');
  });

  it('refuses text, a fraction in a whole-number field, and NaN or Infinity', () => {
    const errors = validateDraft(draftWith({ 'inbound.maxInFlight': '12.5', 'outbound.maxQueue': 'many', 'outbound.breaker.failureRatio': 'NaN', 'outbound.maxConcurrent': 'Infinity' }));
    expect(Object.keys(errors).sort()).toEqual(['inbound.maxInFlight', 'outbound.breaker.failureRatio', 'outbound.maxConcurrent', 'outbound.maxQueue']);
  });

  it('refuses a start share above the continuation share, and a lowest limit above the highest', () => {
    const errors = validateDraft(draftWith({
      'inbound.startShare': '0.9', 'inbound.continuationShare': '0.5', 'inbound.adaptive.minLimit': '50', 'inbound.adaptive.maxLimit': '10',
    }));
    expect(errors['inbound.startShare']).toBe('inbound.startShare must not exceed inbound.continuationShare: a new login never gets more room than work already begun');
    expect(errors['inbound.adaptive.minLimit']).toBe('inbound.adaptive.minLimit must not exceed inbound.adaptive.maxLimit');
  });

  it('does not compare a share with one that is not set: it is whatever the deployment uses', () => {
    expect(validateDraft(draftWith({ 'inbound.startShare': '0.9' }))).toEqual({});
  });
});

describe('startingLimitNote', () => {
  it('is quiet when the starting limit is inside the bounds', () => {
    expect(startingLimitNote(emptyDraft())).toBe('');
    expect(startingLimitNote(draftWith({ 'inbound.maxInFlight': '512' }))).toBe('');
  });

  it('says where the limit really starts when the value is outside the bounds', () => {
    expect(startingLimitNote(draftWith({ 'inbound.maxInFlight': '8' }))).toBe('The limit starts at 16, the lowest limit, not at 8.');
    expect(startingLimitNote(draftWith({ 'inbound.maxInFlight': '5000' }))).toBe('The limit starts at 1024, the highest limit, not at 5000.');
    expect(startingLimitNote(draftWith({ 'inbound.maxInFlight': '5000', 'inbound.adaptive.maxLimit': '8000' }))).toBe('');
  });
});
