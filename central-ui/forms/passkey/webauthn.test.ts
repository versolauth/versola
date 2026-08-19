import { describe, expect, it } from 'vitest';
import { isPasskeyCancellation } from './webauthn';

describe('isPasskeyCancellation', () => {
  it('recognizes the standard user-cancelled WebAuthn error', () => {
    expect(isPasskeyCancellation(new DOMException('The operation was cancelled', 'NotAllowedError'))).toBe(true);
  });

  it('recognizes AbortError used by some browsers for cancellation', () => {
    expect(isPasskeyCancellation(new DOMException('The operation was aborted', 'AbortError'))).toBe(true);
  });

  it('does not hide other passkey failures', () => {
    expect(isPasskeyCancellation(new DOMException('The authenticator failed', 'OperationError'))).toBe(false);
    expect(isPasskeyCancellation(new Error('options request failed'))).toBe(false);
  });
});