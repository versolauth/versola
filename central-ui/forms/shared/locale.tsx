import { createSignal, createUniqueId, For, onCleanup, onMount, Show } from 'solid-js';
import type { BaseFormConfig } from './config';

// The language's own name ("English", "Русский"), as configured under Locales; the bare code when
// the server sent none for it.
export const languageName = (code: string, names?: Record<string, string>) => names?.[code] ?? code;

// A button that opens a listbox (the ARIA "select-only combobox" pattern without the text field):
// Enter, Space or the arrows open it with the current language focused; the arrows, Home and End move,
// Enter or Space picks, Escape closes and returns to the button, Tab leaves. Focus sits on the option
// itself, so a pick is a click or a key, never a pointer-down, and leaving the dropdown (a click
// elsewhere, Tab) closes it without any timer.
function LocaleDropdown(props: { locales: string[]; current: string; names?: Record<string, string>; onChange: (locale: string) => void }) {
  const [open, setOpen] = createSignal(false);
  const listId = createUniqueId();
  const triggerId = createUniqueId();
  let root!: HTMLDivElement;
  let trigger!: HTMLButtonElement;
  let list: HTMLUListElement | undefined;

  const options = () => Array.from(list?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);
  const focusOption = (index: number) => {
    const all = options();
    all[(index + all.length) % all.length]?.focus();
  };
  const show = (index?: number) => {
    setOpen(true);
    queueMicrotask(() => focusOption(index ?? Math.max(0, props.locales.indexOf(props.current))));
  };
  const close = (returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) trigger.focus();
  };
  const pick = (locale: string) => {
    props.onChange(locale);
    close(true);
  };

  const onTriggerKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); show(); }
    else if (e.key === 'Escape' && open()) { e.preventDefault(); close(false); }
  };
  const onOptionKey = (e: KeyboardEvent, index: number, locale: string) => {
    switch (e.key) {
      case 'ArrowDown': focusOption(index + 1); break;
      case 'ArrowUp': focusOption(index - 1); break;
      case 'Home': focusOption(0); break;
      case 'End': focusOption(-1); break;
      case 'Enter': case ' ': pick(locale); break;
      case 'Escape': close(true); break;
      case 'Tab': close(false); return;
      default: return;
    }
    e.preventDefault();
  };

  const outside = (e: Event) => { if (!root.contains(e.target as Node)) setOpen(false); };
  onMount(() => document.addEventListener('click', outside));
  onCleanup(() => document.removeEventListener('click', outside));

  return (
    <div class="locale-dropdown" ref={root}>
      <button
        type="button"
        class="locale-trigger"
        id={triggerId}
        ref={trigger}
        aria-haspopup="listbox"
        aria-expanded={open()}
        aria-controls={open() ? listId : undefined}
        onClick={() => (open() ? close(false) : show())}
        onKeyDown={onTriggerKey}
      >
        <svg class="locale-globe" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"/>
        </svg>
        {languageName(props.current, props.names)}
      </button>
      <Show when={open()}>
        <ul class="locale-options" id={listId} role="listbox" ref={list} aria-labelledby={triggerId}>
          <For each={props.locales}>
            {(loc, index) => (
              <li
                role="option"
                tabindex="-1"
                lang={loc}
                class={`locale-option${loc === props.current ? ' locale-option-active' : ''}`}
                aria-selected={loc === props.current}
                onClick={() => pick(loc)}
                onKeyDown={(e) => onOptionKey(e, index(), loc)}
              >
                {languageName(loc, props.names)}
              </li>
            )}
          </For>
        </ul>
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
