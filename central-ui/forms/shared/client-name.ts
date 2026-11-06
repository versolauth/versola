/**
 * The client's name for the form's current language. `names` holds every locale the client's name is
 * stored in (keys as stored, so `pt-BR` and `pt-br` both occur), `name` is the one the server picked
 * for the request's language.
 *
 * Order: the exact locale (case-insensitive), the same language without region (`pt-BR` -> `pt`),
 * the default locale, `name`, then any entry. Pure, so a language switch only re-runs it.
 */
export function pickClientName(
  locale: string,
  name: string | undefined,
  names: Record<string, string> | undefined,
  defaultLocale = 'en',
): string | undefined {
  const entries = Object.entries(names ?? {}).filter(([, value]) => value && value.trim());
  const find = (code: string) => {
    const wanted = code.toLowerCase();
    return entries.find(([key]) => key.toLowerCase() === wanted)?.[1];
  };
  const language = (code: string) => code.split(/[-_]/)[0];
  return find(locale)
    ?? find(language(locale))
    ?? find(defaultLocale)
    ?? find(language(defaultLocale))
    ?? (name?.trim() ? name : undefined)
    ?? entries[0]?.[1];
}
