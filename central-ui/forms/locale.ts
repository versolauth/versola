// The language the user picked is remembered in a cookie so the pages that follow without a
// conversation to read it from (expired, signed out, account settings) render in it. It carries
// nothing but the locale code, and the server only honours it if it is a locale the tenant has.
const MAX_AGE_SECONDS = 365 * 24 * 60 * 60;

export function rememberLocale(locale: string): void {
  try {
    document.cookie = `ui_locale=${encodeURIComponent(locale)}; Path=/; Max-Age=${MAX_AGE_SECONDS}; SameSite=Lax; Secure`;
  } catch (_) {}
}
