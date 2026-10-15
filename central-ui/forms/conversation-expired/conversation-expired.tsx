import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';

// The brand zone above the card: the identity provider's logo (from the system settings) on a light
// tile, or the Versola wordmark when none is set or it fails to load. A client's own logo belongs
// inside the card.
function PageBrand(props: { logo?: string }) {
  const [failed, setFailed] = createSignal(false);
  return (
    <div class="page-brand" aria-hidden="true">
      <Show
        when={props.logo && !failed()}
        fallback={
          <svg class="page-wordmark" viewBox="-2 0 286 56" height="28" fill="none" stroke="currentColor" stroke-width="8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M2 22 L18 48 L34 22" />
            <path d="M50 35 H76 A13 13 0 1 0 72.5 44.3" />
            <path d="M98 22 V48 M98 36 C98 26 104 22 114 22" />
            <path d="M158.5 27 C156.5 23 152.5 22 148.5 22 C142.5 22 138.5 25 138.5 29 C138.5 33 142.5 34.5 148.5 36 C154.5 37.5 158.5 39 158.5 42.5 C158.5 46.5 154.5 48.5 148.5 48.5 C143.5 48.5 139.5 46.5 138 42.5" />
            <circle cx="193.5" cy="35" r="13" />
            <path d="M228.5 6 V48" />
            <circle cx="263.5" cy="35" r="13" />
            <path d="M276.5 22 V48" />
          </svg>
        }
      >
        <span class="page-brand-tile">
          <img src={props.logo} alt="" style="height:40px" onError={() => setFailed(true)} />
        </span>
      </Show>
    </div>
  );
}

// The language's own name ("English", "Русский"); falls back to the bare code where Intl lacks it.
const languageName = (code: string, names?: Record<string, string>) => {
  if (names?.[code]) return names[code];
  try {
    const name = new Intl.DisplayNames([code], { type: 'language' }).of(code) ?? code;
    return name.charAt(0).toLocaleUpperCase(code) + name.slice(1);
  } catch (_) {
    return code;
  }
};

function LocaleDropdown(props: { locales: string[]; current: string; onChange: (locale: string) => void }) {
  const [open, setOpen] = createSignal(false);
  return (
    <div class="locale-dropdown">
      <button type="button" class="locale-trigger" onClick={() => setOpen(!open())} onBlur={() => setTimeout(() => setOpen(false), 120)}>
        <svg class="locale-globe" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"/>
        </svg>
        {languageName(props.current, props.names)}
      </button>
      <Show when={open()}>
        <div class="locale-options">
          {props.locales.map((locale) => (
            <button type="button" class={`locale-option${locale === props.current ? ' locale-option-active' : ''}`} onMouseDown={() => { props.onChange(locale); setOpen(false); }}>
              {languageName(locale)}
            </button>
          ))}
        </div>
      </Show>
    </div>
  );
}

type ConversationExpiredStep = { type: 'conversation-expired'; redirectUri?: string };

interface FormConfig {
  step: ConversationExpiredStep;
  t: Record<string, string>;
  locale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  previewId?: string;
}

declare global {
  interface Window { __VERSOLA_FORM__?: FormConfig }
}

function ConversationExpiredForm(props: { config: FormConfig }) {
  const allT = props.config.allT ?? {};
  const [currentLocale, setCurrentLocale] = createSignal(props.config.locale ?? 'en');
  const changeLocale = (locale: string) => {
    setCurrentLocale(locale);
    try { window.parent.postMessage({ type: 'versola:locale-change', locale, previewId: props.config.previewId }, '*'); } catch (_) {}
  };
  const t = () => ({ ...props.config.t, ...(allT[currentLocale()] ?? {}) });
  const locales = props.config.locales ?? [];

  return (
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector"><LocaleDropdown locales={locales} current={currentLocale()} names={props.config.localeNames} onChange={changeLocale} /></div>
      </Show>
      <div class="conversation-expired-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12 6 12 12 16 14" />
        </svg>
      </div>
      <h1>{t().title}</h1>
      <p class="conversation-expired-description">{t().description}</p>
      <Show when={props.config.step.redirectUri}>
        {(redirectUri) => <a href={redirectUri()} class="btn btn-primary conversation-expired-return">{t().return_button}</a>}
      </Show>
    </div>
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) render(() => <ConversationExpiredForm config={config} />, root);