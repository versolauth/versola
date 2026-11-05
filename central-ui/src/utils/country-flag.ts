/** A country as the report names it: an ISO 3166-1 alpha-2 code, shown as a flag, a name and the code. */
export type Country = { code: string; flag: string; name: string };

const REGIONAL_INDICATOR_A = 0x1f1e6;

/** The flag emoji of a code (two regional-indicator letters), or null when it is not two ASCII letters. */
export function flagEmoji(code: string): string | null {
  if (!/^[A-Za-z]{2}$/.test(code)) return null;
  return [...code.toUpperCase()].map(letter => String.fromCodePoint(REGIONAL_INDICATOR_A + letter.charCodeAt(0) - 65)).join('');
}

let names: Intl.DisplayNames | null | undefined;

/** The English name of a code from the browser's own data, or null when it does not know the code. */
export function countryName(code: string): string | null {
  if (names === undefined) {
    try {
      names = new Intl.DisplayNames(['en'], { type: 'region', fallback: 'none' });
    } catch {
      names = null;
    }
  }
  try {
    const name = names?.of(code.toUpperCase());
    // CLDR names the reserved code ZZ "Unknown Region": that says nothing, so the code stands in for it.
    return name && name.toUpperCase() !== code.toUpperCase() && name !== 'Unknown Region' ? name : null;
  } catch {
    return null;
  }
}

/** What to draw for a country code: the flag, the name and the upper-case code, or null when the API sent none (no
 *  geo database, a private or unknown address) or something that is no code. A flag is never drawn alone: some
 *  systems show a flag emoji as two letters, so the name and the code are always next to it. */
export function describeCountry(code: string | null | undefined): Country | null {
  if (!code) return null;
  const flag = flagEmoji(code);
  if (!flag) return null;
  const upper = code.toUpperCase();
  return { code: upper, flag, name: countryName(upper) ?? upper };
}
