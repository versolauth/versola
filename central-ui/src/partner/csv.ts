/** Characters a spreadsheet reads as the start of a formula (also the full-width forms some locales type), plus tab and CR. */
const FORMULA_START = /^[=+\-@\t\r＝＋－＠]/;

/** One CSV cell, quoted. A value that would run as a formula in Excel or Sheets is made text with a leading apostrophe (OWASP CSV injection). */
export function csvCell(value: string): string {
  const safe = FORMULA_START.test(value) ? `'${value}` : value;
  return `"${safe.replace(/"/g, '""')}"`;
}
