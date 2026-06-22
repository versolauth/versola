// Shared WebAuthn client helpers used by the credential (assertion) and
// passkey-enroll (registration) forms. The server speaks the Yubico
// webauthn-server-core JSON dialect: challenge / id / user.id fields are
// base64url strings, and the *response* must be sent back base64url-encoded too.

function base64urlToBuffer(value: string): ArrayBuffer {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function bufferToBase64url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function passkeysSupported(): boolean {
  return typeof window !== 'undefined'
    && !!window.PublicKeyCredential
    && !!navigator.credentials;
}

type PublicKeyDescriptor = { id: string; type: string; transports?: string[] };

// `optionsJson` is the server's toCredentialsGetJson(): { publicKey: { ... } }.
// Runs navigator.credentials.get and returns the assertion response as a JSON string.
export async function getAssertionResponse(optionsJson: string): Promise<string> {
  const { publicKey } = JSON.parse(optionsJson) as { publicKey: any };
  const request: PublicKeyCredentialRequestOptions = {
    ...publicKey,
    challenge: base64urlToBuffer(publicKey.challenge),
    allowCredentials: (publicKey.allowCredentials ?? []).map((c: PublicKeyDescriptor) => ({
      ...c,
      id: base64urlToBuffer(c.id),
    })),
  };
  const credential = (await navigator.credentials.get({ publicKey: request })) as PublicKeyCredential | null;
  if (!credential) throw new Error('No credential returned');
  const response = credential.response as AuthenticatorAssertionResponse;
  return JSON.stringify({
    type: credential.type,
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    response: {
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      authenticatorData: bufferToBase64url(response.authenticatorData),
      signature: bufferToBase64url(response.signature),
      userHandle: response.userHandle ? bufferToBase64url(response.userHandle) : null,
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  });
}

// `optionsJson` is the server's toCredentialsCreateJson(): { publicKey: { ... } }.
// Runs navigator.credentials.create and returns the attestation response as a JSON string.
export async function getRegistrationResponse(optionsJson: string): Promise<string> {
  const { publicKey } = JSON.parse(optionsJson) as { publicKey: any };
  const request: PublicKeyCredentialCreationOptions = {
    ...publicKey,
    challenge: base64urlToBuffer(publicKey.challenge),
    user: { ...publicKey.user, id: base64urlToBuffer(publicKey.user.id) },
    excludeCredentials: (publicKey.excludeCredentials ?? []).map((c: PublicKeyDescriptor) => ({
      ...c,
      id: base64urlToBuffer(c.id),
    })),
  };
  const credential = (await navigator.credentials.create({ publicKey: request })) as PublicKeyCredential | null;
  if (!credential) throw new Error('No credential created');
  const response = credential.response as AuthenticatorAttestationResponse;
  const transports = typeof response.getTransports === 'function' ? response.getTransports() : [];
  return JSON.stringify({
    type: credential.type,
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    response: {
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      attestationObject: bufferToBase64url(response.attestationObject),
      transports,
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  });
}

// Builds a hidden POST form with the given fields and submits it, so the browser
// follows the server's 303 redirect (matching the native form-post flow).
export function submitViaForm(action: string, fields: Record<string, string>): void {
  const form = document.createElement('form');
  form.method = 'post';
  form.action = action;
  for (const [name, value] of Object.entries(fields)) {
    const input = document.createElement('input');
    input.type = 'hidden';
    input.name = name;
    input.value = value;
    form.appendChild(input);
  }
  document.body.appendChild(form);
  form.submit();
}
