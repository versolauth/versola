import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';

// The language's own name ("English", "Русский"); falls back to the bare code where Intl lacks it.
const languageName = (code: string) => {
  try {
    const name = new Intl.DisplayNames([code], { type: 'language' }).of(code) ?? code;
    return name.charAt(0).toLocaleUpperCase(code) + name.slice(1);
  } catch (_) {
    return code;
  }
};

function LocaleDropdown(props: { locales: string[]; current: string; onChange: (l: string) => void }) {
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
        {languageName(props.current)}
      </button>
      <Show when={open()}>
        <div class="locale-options">
          {props.locales.map((loc) => (
            <button
              type="button"
              class={`locale-option${loc === props.current ? ' locale-option-active' : ''}`}
              onMouseDown={() => { props.onChange(loc); setOpen(false); }}
            >
              {languageName(loc)}
            </button>
          ))}
        </div>
      </Show>
    </div>
  );
}

type AccessDeniedStep = { type: 'access-denied'; redirectUri: string };

interface FormConfig {
  step: AccessDeniedStep;
  t: Record<string, string>;
  locale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  previewId?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function AccessDeniedForm(props: { config: FormConfig }) {
  const allT = props.config.allT ?? {};
  const baseT = props.config.t;
  const [currentLocale, setCurrentLocale] = createSignal(props.config.locale ?? 'en');
  const changeLocale = (loc: string) => {
    setCurrentLocale(loc);
    try { window.parent.postMessage({ type: 'versola:locale-change', locale: loc, previewId: props.config.previewId }, '*'); } catch (_) {}
  };
  const t = () => {
    const loc = currentLocale();
    const locT = allT[loc] ?? {};
    return { ...baseT, ...locT };
  };
  const locales = props.config.locales ?? [];
  const redirectUri = props.config.step.redirectUri;

  return (
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
      <div class="access-denied-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"/>
          <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
        </svg>
      </div>
      <h1>{t().title}</h1>
      <p class="access-denied-description">{t().description}</p>
      <a href={redirectUri} class="btn btn-primary">
        {t().return_button}
      </a>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <AccessDeniedForm config={config!} />, root);
}
