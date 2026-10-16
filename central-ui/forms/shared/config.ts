/** The partner's app a user is signing in to; absent for the identity provider's own apps. */
export type ClientInfo = {
  name?: string;
  /** The name in every locale it is stored in, keys as stored; the form picks by its current language. */
  names?: Record<string, string>;
  logo?: string;
  partner: boolean;
};

/** What every form receives in `window.__VERSOLA_FORM__`, besides its own `step`. */
export interface BaseFormConfig {
  t: Record<string, string>;
  locale?: string;
  /** The deployment's default locale, the fallback for a name with no entry for the current language; `en` when absent. */
  defaultLocale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  previewId?: string;
  /** Set by the console's form previews: the visitor's colour scheme toggle is not drawn there (the preview has its own Light / Dark control). */
  preview?: boolean;
  csrf?: string;
  logo?: string;
  client?: ClientInfo;
  /** Display name per locale code, as configured under Locales. */
  localeNames?: Record<string, string>;
}
