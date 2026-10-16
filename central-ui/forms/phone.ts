import { parsePhoneNumberFromString } from 'libphonenumber-js';

/**
 * The backend only validates a phone number: it has to arrive written in E.164, and one that is
 * valid but spelled another way is refused. So whatever a person types is normalised here, with
 * the same library family the backend uses, before it is sent. `null` means it is not a number.
 */
export function toE164(value: string): string | null {
  const parsed = parsePhoneNumberFromString(value.trim());
  return parsed?.isValid() ? parsed.number : null;
}
