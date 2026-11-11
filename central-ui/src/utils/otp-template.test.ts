import { describe, expect, it } from 'vitest';
import { MAX_SUBJECT_LENGTH, htmlStructureError, missingPlaceholders, subjectError, subjectsToSave } from './otp-template';

describe('missingPlaceholders', () => {
  it('names the placeholders of the purpose that are absent', () => {
    expect(missingPlaceholders('Your code is {{code}}', 'otp')).toEqual([]);
    expect(missingPlaceholders('Your code', 'otp')).toEqual(['{{code}}']);
    expect(missingPlaceholders('{{password}}', 'password')).toEqual(['{{expiresHours}}']);
  });
});

describe('htmlStructureError', () => {
  it('accepts well-formed HTML', () => {
    expect(htmlStructureError('<html><body><p>Code: <b>{{code}}</b></p><br></body></html>')).toBe('');
    expect(htmlStructureError('<p><a href="https://example.com">x</a><input disabled></p>')).toBe('');
    expect(htmlStructureError('<!-- note --><p>x</p>')).toBe('');
  });

  it('refuses broken structure and attributes', () => {
    expect(htmlStructureError('<p>Code: {{code}}')).toBe('Unclosed tag <p>');
    expect(htmlStructureError('<p>Code</b>')).toBe('Unexpected closing tag </b>');
    expect(htmlStructureError('<p class>x</p>')).toBe('Attribute "class" on <p> must have a value');
    expect(htmlStructureError('<p href="x>y</p>')).toBe('Attribute "href" on <p> has an unterminated value');
    expect(htmlStructureError('a < b <p>x</p>')).toBe('HTML contains an invalid tag');
  });
});

describe('subjectError', () => {
  it('accepts an empty subject, which means the SMTP settings\' subject, and a one-line one', () => {
    expect(subjectError('')).toBe('');
    expect(subjectError('   ')).toBe('');
    expect(subjectError('{{code}} is your code for {{appName}}')).toBe('');
  });

  it('refuses a line break and a subject over the limit', () => {
    expect(subjectError('Code\r\nBcc: evil@example.com')).toBe('The subject must be one line.');
    expect(subjectError('x'.repeat(MAX_SUBJECT_LENGTH))).toBe('');
    expect(subjectError('x'.repeat(MAX_SUBJECT_LENGTH + 1))).toBe(`The subject must be at most ${MAX_SUBJECT_LENGTH} characters.`);
  });
});

describe('subjectsToSave', () => {
  it('keeps what is filled in, trimmed, and drops empty ones', () => {
    expect(subjectsToSave({ en: ' Your code ', ru: '  ' }, 'email')).toEqual({ en: 'Your code' });
  });

  it('sends none for an SMS template, which has no subject', () => {
    expect(subjectsToSave({ en: 'Your code' }, 'sms')).toEqual({});
  });
});
