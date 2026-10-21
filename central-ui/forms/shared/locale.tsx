import { createSignal, Show } from 'solid-js';
import type { BaseFormConfig } from './config';
import { Listbox } from './listbox';
import { schemeLocked } from './scheme';
import { SchemeToggle } from './scheme-toggle';

// The language's own name ("English", "Русский"), as configured under Locales; the bare code when
// the server sent none for it.
export const languageName = (code: string, names?: Record<string, string>) => names?.[code] ?? code;

function LocaleDropdown(props: { locales: string[]; current: string; names?: Record<string, string>; onChange: (locale: string) => void }) {
  return (
    <Listbox
      triggerClass="locale-trigger"
      items={props.locales.map(loc => ({ value: loc, label: languageName(loc, props.names), lang: loc }))}
      current={props.current}
      onChange={props.onChange}
    >
      <svg class="locale-globe" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
        <circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"/>
      </svg>
      {languageName(props.current, props.names)}
    </Listbox>
  );
}

/** The strings of a language: the form's base strings overlaid with that language's. */
const stringsFor = (config: BaseFormConfig, locale: string) => ({ ...config.t, ...(config.allT?.[locale] ?? {}) });

/**
 * The card's corner controls: the colour scheme toggle and the language switcher, in one row so the language
 * switcher keeps its place at the right edge. Each is left out when it has nothing to offer: one language only;
 * a theme that fixes the scheme, or a console preview (whose own Light / Dark control must win).
 */
export function LocaleSelector(props: { config: BaseFormConfig; current: string; onChange: (locale: string) => void }) {
  const showLocale = () => (props.config.locales ?? []).length > 1;
  const showScheme = !props.config.preview && !schemeLocked();
  return (
    <Show when={showLocale() || showScheme}>
      <div class="locale-selector">
        <Show when={showScheme}>
          <SchemeToggle t={stringsFor(props.config, props.current)} />
        </Show>
        <Show when={showLocale()}>
          <LocaleDropdown locales={props.config.locales!} current={props.current} names={props.config.localeNames} onChange={props.onChange} />
        </Show>
      </div>
    </Show>
  );
}

/**
 * The form's current language and its strings: the base strings overlaid with the current locale's.
 * Changing the language also tells a parent window (the console's preview) which one was picked.
 */
export function createTranslations(config: BaseFormConfig) {
  const allT = config.allT ?? {};
  const [locale, setLocale] = createSignal(config.locale ?? 'en');
  const t = () => ({ ...config.t, ...(allT[locale()] ?? {}) });
  const changeLocale = (next: string) => {
    setLocale(next);
    try { window.parent.postMessage({ type: 'versola:locale-change', locale: next, previewId: config.previewId }, '*'); } catch (_) {}
  };
  return { t, locale, changeLocale };
}
