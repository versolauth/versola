import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';

function LocaleDropdown(props: { locales: string[]; current: string; onChange: (locale: string) => void }) {
  const [open, setOpen] = createSignal(false);
  return (
    <div class="locale-dropdown">
      <button type="button" class="locale-trigger" onClick={() => setOpen(!open())} onBlur={() => setTimeout(() => setOpen(false), 120)}>
        {props.current}
        <svg class="locale-chevron" viewBox="0 0 10 6" fill="none" aria-hidden="true">
          <path d="M1 1l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
      <Show when={open()}>
        <div class="locale-options">
          {props.locales.map((locale) => (
            <button type="button" class={`locale-option${locale === props.current ? ' locale-option-active' : ''}`} onMouseDown={() => { props.onChange(locale); setOpen(false); }}>
              {locale}
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
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector"><LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} /></div>
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
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) render(() => <ConversationExpiredForm config={config} />, root);