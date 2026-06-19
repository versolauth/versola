import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';

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
        {props.current}
        <svg class="locale-chevron" viewBox="0 0 10 6" fill="none" aria-hidden="true">
          <path d="M1 1l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
      <Show when={open()}>
        <div class="locale-options">
          {props.locales.map((loc) => (
            <button
              type="button"
              class={`locale-option${loc === props.current ? ' locale-option-active' : ''}`}
              onMouseDown={() => { props.onChange(loc); setOpen(false); }}
            >
              {loc}
            </button>
          ))}
        </div>
      </Show>
    </div>
  );
}

type PasswordStep = { type: 'password'; passwordRegex?: string };

interface FormConfig {
  step: PasswordStep;
  t: Record<string, string>;
  locale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  error?: string;
  previewId?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function PasswordForm(props: { config: FormConfig }) {
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

  const [passwordNotAllowed, setPasswordNotAllowed] = createSignal(false);
  const passwordRegex = props.config.step.passwordRegex;

  const handleSubmit = (e: SubmitEvent) => {
    if (!passwordRegex) return;
    const form = e.currentTarget as HTMLFormElement;
    const password = (form.elements.namedItem('password') as HTMLInputElement | null)?.value ?? '';
    try {
      if (!new RegExp(passwordRegex).test(password)) {
        e.preventDefault();
        setPasswordNotAllowed(true);
      }
    } catch (_) {}
  };

  return (
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
      <h1>{t().title}</h1>
      <Show when={props.config.error}>
        <div class="error-text" style="margin-bottom: 8px;">{t()[props.config.error!] ?? props.config.error}</div>
      </Show>
      <form method="post" onSubmit={handleSubmit}>
        <input
          type="password"
          name="password"
          class="input-field"
          placeholder={t().password_placeholder}
          autocomplete="current-password"
          required
          onInput={() => passwordNotAllowed() && setPasswordNotAllowed(false)}
        />
        <Show when={passwordNotAllowed()}>
          <div class="phone-error-message">{t().password_not_allowed}</div>
        </Show>
        <button type="submit" formAction={`/challenge/password?ui_locale=${currentLocale()}`} class="btn btn-primary">
          {t().continue}
        </button>
      </form>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <PasswordForm config={config} />, root);
}
