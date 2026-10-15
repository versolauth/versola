import { createSignal, Show } from 'solid-js';
import type { BaseFormConfig } from './config';

// The language's own name ("English", "Русский"), as configured under Locales; the bare code when
// the server sent none for it.
export const languageName = (code: string, names?: Record<string, string>) => names?.[code] ?? code;

function LocaleDropdown(props: { locales: string[]; current: string; names?: Record<string, string>; onChange: (locale: string) => void }) {
  const [open, setOpen] = createSignal(false);
  return (
    <div class="locale-dropdown">
      <button
        type="button"
        class="locale-trigger"
        onClick={() => setOpen(!open())}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
      >
        <svg class="locale-globe" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"/>
        </svg>
        {languageName(props.current, props.names)}
      </button>
      <Show when={open()}>
        <div class="locale-options">
          {props.locales.map((loc) => (
            <button
              type="button"
              class={`locale-option${loc === props.current ? ' locale-option-active' : ''}`}
              onMouseDown={() => { props.onChange(loc); setOpen(false); }}
            >
              {languageName(loc, props.names)}
            </button>
          ))}
        </div>
      </Show>
    </div>
  );
}

/** The language switcher in the card's corner; nothing when there is only one language. */
export function LocaleSelector(props: { config: BaseFormConfig; current: string; onChange: (locale: string) => void }) {
  return (
    <Show when={(props.config.locales ?? []).length > 1}>
      <div class="locale-selector">
        <LocaleDropdown locales={props.config.locales!} current={props.current} names={props.config.localeNames} onChange={props.onChange} />
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
