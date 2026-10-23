/** A country the tenant's phone sign-in accepts; mirrors central's `PhoneRegion`. */
export type PhoneRegion = {
  region: string;
  dialCode: string;
  length: number;
  regex: string;
};

/** The regions that share one dial code: the unit the country-code selector offers. */
export type DialCodeGroup = {
  dialCode: string;
  regions: PhoneRegion[];
  /** Digits after the dial code the longest of its regions takes. */
  maxLength: number;
};

/** Distinct dial codes in the order the tenant listed them (first appearance wins). */
export function groupByDialCode(regions: PhoneRegion[]): DialCodeGroup[] {
  const groups: DialCodeGroup[] = [];
  for (const region of regions) {
    const group = groups.find((g) => g.dialCode === region.dialCode);
    if (group) {
      group.regions.push(region);
      group.maxLength = Math.max(group.maxLength, region.length);
    } else {
      groups.push({ dialCode: region.dialCode, regions: [region], maxLength: region.length });
    }
  }
  return groups;
}

/**
 * Whether `digits` is a number of any region in the group. Length and regex belong together per
 * region, so "KZ and RU" takes a 7... and a 9... number but not a 7... number of RU's length.
 * A regex the browser cannot compile (the server's is a Java one) is left to the server.
 */
export function fits(group: DialCodeGroup, digits: string): boolean {
  return group.regions.some((region) => {
    if (digits.length !== region.length || !/^\d+$/.test(digits)) return false;
    try {
      return new RegExp(`^(?:${region.regex})$`).test(digits);
    } catch (_) {
      return true;
    }
  });
}

/** The group a pasted `+<dial code><number>` belongs to, and the number without the code. */
export function detect(
  groups: DialCodeGroup[],
  pasted: string,
): { group: DialCodeGroup; national: string } | null {
  if (!pasted.trim().startsWith('+')) return null;
  const digits = pasted.replace(/\D/g, '');
  const candidates = groups
    .filter((g) => digits.startsWith(g.dialCode.slice(1)))
    .sort((a, b) => b.dialCode.length - a.dialCode.length);
  const whole = candidates.find((g) => fits(g, digits.slice(g.dialCode.length - 1)));
  const group = whole ?? candidates[0];
  return group ? { group, national: digits.slice(group.dialCode.length - 1) } : null;
}

/** Digits of what was typed, without a dial code typed or pasted again and within the length cap. */
export function nationalDigits(group: DialCodeGroup, typed: string): string {
  let digits = typed.replace(/\D/g, '');
  const code = group.dialCode.slice(1);
  if (digits.length > group.maxLength && digits.startsWith(code)) digits = digits.slice(code.length);
  return digits.slice(0, group.maxLength);
}

function groupSizes(length: number): number[] {
  switch (length) {
    case 10: return [3, 3, 2, 2];
    case 9: return [3, 3, 3];
    case 8: return [2, 3, 3];
    case 7: return [3, 2, 2];
    default: return Array(Math.ceil(length / 3)).fill(3);
  }
}

/** Digits split into the groups people read a number of this length in. */
export function format(digits: string, length: number): string {
  const parts: string[] = [];
  let at = 0;
  for (const size of groupSizes(length)) {
    if (at >= digits.length) break;
    parts.push(digits.slice(at, at + size));
    at += size;
  }
  return parts.join(' ');
}

/** Country names in `locale`, falling back to the ISO code where the browser has none. */
export function regionName(region: string, locale: string): string {
  try {
    return new Intl.DisplayNames([locale], { type: 'region' }).of(region) ?? region;
  } catch (_) {
    return region;
  }
}

/** Distinct dial codes from which the selector gets a search box (more than five); a shorter list is just read. */
export const SEARCH_FROM = 6;

/** Whether a country-code search matches the group by country name, ISO code or dial code. */
export function matches(group: DialCodeGroup, query: string, locale: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === '') return true;
  const digits = q.replace(/^\+/, '');
  if (/^\d+$/.test(digits) && group.dialCode.slice(1).startsWith(digits)) return true;
  return group.regions.some(
    (r) => r.region.toLowerCase() === q || regionName(r.region, locale).toLowerCase().includes(q),
  );
}
