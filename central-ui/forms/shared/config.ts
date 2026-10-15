/** The partner's app a user is signing in to; absent for the identity provider's own apps. */
export type ClientInfo = { name?: string; logo?: string; partner: boolean };

/** What every form receives in `window.__VERSOLA_FORM__`, besides its own `step`. */
export interface BaseFormConfig {
  t: Record<string, string>;
  locale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  previewId?: string;
  csrf?: string;
  logo?: string;
  client?: ClientInfo;
  /** Display name per locale code, as configured under Locales. */
  localeNames?: Record<string, string>;
}
