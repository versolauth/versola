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
