import type { PublicJwk } from '../types';

/** What an operator types for a partition, region or cell, checked where the backend checks it (`TopologyService`). */

/** Partitions, regions and cells share one shape of ID, the shape of a routing key's cell ID. */
const ID_PATTERN = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/;
export const ID_RULE = 'Lowercase letters, digits and dashes, not starting or ending with a dash.';

export const validId = (id: string) => id.length <= 64 && ID_PATTERN.test(id);

export function addressError(value: string, httpsOnly: boolean): string {
  try {
    const url = new URL(value);
    if (url.protocol === 'https:' || (url.protocol === 'http:' && !httpsOnly)) return '';
  } catch { /* falls through to the message */ }
  return httpsOnly ? 'An absolute https URL, for example https://auth-kz-1.internal:9008.' : 'An absolute http or https URL, for example http://auth-kz-1.internal:9003.';
}

/** The PEM blocks of a pasted text, one certificate each. */
export function certificatesOf(text: string): string[] {
  return text.match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g) ?? [];
}

/** Members that make a JWK private; central refuses a key that has one. */
const PRIVATE_MEMBERS = ['d', 'p', 'q', 'dp', 'dq', 'qi'];
export const MAX_PUBLIC_KEYS = 2;
const MIN_MODULUS_BITS = 2048;
const MAX_MODULUS_BITS = 8192;

export const PUBLIC_KEYS_HINT = 'One public RSA key as a JWK, or two while a key is being replaced: JSON objects one after another, or a JSON array. '
  + 'The first one is the key other cells encrypt to. Register the cell here before its auth is started: auth signs its calls to central with the private key of one of these.';

/** The top-level JSON values of a text: one value, or several one after another (`{...} {...}`), found by brace depth outside strings. */
function jsonValues(text: string): string[] {
  const values: string[] = [];
  let depth = 0;
  let inString = false;
  let escaped = false;
  let start = -1;
  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === '"') {
      if (depth === 0) return [text];
      inString = true;
    }
    else if (char === '{' || char === '[') {
      if (depth === 0) start = index;
      depth++;
    } else if (char === '}' || char === ']') {
      depth--;
      if (depth === 0 && start >= 0) {
        values.push(text.slice(start, index + 1));
        start = -1;
      } else if (depth < 0) return [text];
    } else if (depth === 0 && !/\s|,/.test(char)) return [text];
  }
  return depth === 0 && !inString ? values : [text];
}

export type PublicKeysResult = { keys: PublicJwk[]; error: '' } | { keys: null; error: string };

/** Reads what an operator pasted for a cell's `publicKeys`, with the checks central makes that need no more than the text. */
export function parsePublicKeys(text: string): PublicKeysResult {
  const trimmed = text.trim();
  if (!trimmed) return { keys: null, error: 'A public key is required: a cell with no key cannot call central.' };
  const parsed: unknown[] = [];
  for (const piece of jsonValues(trimmed)) {
    let value: unknown;
    try {
      value = JSON.parse(piece);
    } catch {
      return { keys: null, error: 'Must be valid JSON: a JWK object, two of them one after another, or an array of them.' };
    }
    if (Array.isArray(value)) parsed.push(...value);
    else parsed.push(value);
  }
  if (parsed.length === 0) return { keys: null, error: 'A public key is required: a cell with no key cannot call central.' };
  if (parsed.length > MAX_PUBLIC_KEYS) return { keys: null, error: `At most ${MAX_PUBLIC_KEYS} keys: one, or two while a key is being replaced.` };
  const keys: PublicJwk[] = [];
  for (const [index, value] of parsed.entries()) {
    const label = parsed.length > 1 ? `Key ${index + 1}: ` : '';
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return { keys: null, error: `${label}Must be a JWK, a JSON object.` };
    const key = value as Record<string, unknown>;
    if (key.kty !== 'RSA') return { keys: null, error: `${label}Must be an RSA key ("kty": "RSA").` };
    for (const member of ['kid', 'n', 'e']) {
      if (typeof key[member] !== 'string' || !(key[member] as string).trim()) return { keys: null, error: `${label}Missing "${member}".` };
    }
    const priv = PRIVATE_MEMBERS.find(member => member in key);
    if (priv) return { keys: null, error: `${label}A private member ("${priv}") is present: paste the public key only.` };
    const bits = Math.floor(((key.n as string).length * 6) / 8) * 8;
    if (bits < MIN_MODULUS_BITS || bits > MAX_MODULUS_BITS) return { keys: null, error: `${label}An RSA key of ${MIN_MODULUS_BITS} to ${MAX_MODULUS_BITS} bits.` };
    keys.push(key as PublicJwk);
  }
  if (new Set(keys.map(key => key.kid)).size !== keys.length) return { keys: null, error: 'The two keys have the same "kid".' };
  if (new Set(keys.map(key => key.n)).size !== keys.length) return { keys: null, error: 'The same key is pasted twice.' };
  return { keys, error: '' };
}

/** A key set as it is shown for editing: one object, or an array of two. */
export function publicKeysText(keys: PublicJwk[]): string {
  if (keys.length === 0) return '';
  return JSON.stringify(keys.length === 1 ? keys[0] : keys, null, 2);
}
