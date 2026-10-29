import { describe, expect, it } from 'vitest';
import {
  draftFromPolicy, emptyDraft, isEmptyPolicy, modeLabel, policyFromDraft, setSummary, validateDraft,
} from './access-log-policy';

describe('access log policy', () => {
  it('an empty draft sends nothing, and an unset policy reads as an empty draft', () => {
    expect(policyFromDraft(emptyDraft())).toEqual({});
    expect(draftFromPolicy(undefined)).toEqual(emptyDraft());
    expect(isEmptyPolicy({})).toBe(true);
    expect(isEmptyPolicy(undefined)).toBe(true);
  });

  it('sends only what was set, as numbers where the field is one', () => {
    const draft = { ...emptyDraft(), mode: 'sampled', successSampleRate: ' 0.1 ' };
    expect(policyFromDraft(draft)).toEqual({ mode: 'sampled', successSampleRate: 0.1 });
    expect(policyFromDraft({ ...emptyDraft(), slowMillis: '250' })).toEqual({ slowMillis: 250 });
  });

  it('round-trips a policy through the draft', () => {
    const policy = { mode: 'full' as const, slowMillis: 1000, successSampleRate: 0.5 };
    expect(policyFromDraft(draftFromPolicy(policy))).toEqual(policy);
    expect(isEmptyPolicy(policy)).toBe(false);
  });

  it('validates with the server messages', () => {
    expect(validateDraft(emptyDraft())).toEqual({});
    expect(validateDraft({ ...emptyDraft(), slowMillis: '-1' }).slowMillis).toBe('slowMillis must be between 0 and 600000');
    expect(validateDraft({ ...emptyDraft(), slowMillis: '600001' }).slowMillis).toBeDefined();
    expect(validateDraft({ ...emptyDraft(), slowMillis: '1.5' }).slowMillis).toBeDefined();
    expect(validateDraft({ ...emptyDraft(), successSampleRate: '1.5' }).successSampleRate).toBe('successSampleRate must be between 0.0 and 1.0');
    expect(validateDraft({ ...emptyDraft(), successSampleRate: 'abc' }).successSampleRate).toBeDefined();
    expect(validateDraft({ ...emptyDraft(), slowMillis: '0', successSampleRate: '1' })).toEqual({});
  });

  it('labels the mode and summarises the other fields', () => {
    expect(modeLabel(undefined)).toBe('Default (off)');
    expect(modeLabel('sampled')).toBe('Sampled (set here)');
    expect(setSummary({ mode: 'full', slowMillis: 250 })).toEqual(['slowMillis 250']);
    expect(setSummary(undefined)).toEqual([]);
  });
});
