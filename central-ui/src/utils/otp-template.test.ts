import { describe, expect, it } from 'vitest';
import { htmlStructureError, missingPlaceholders } from './otp-template';

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
