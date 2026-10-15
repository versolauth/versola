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

type SetPasswordStep = { type: 'set-password'; passwordRegex?: string };

interface FormConfig {
  step: SetPasswordStep;
  t: Record<string, string>;
  locale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  error?: string;
  previewId?: string;
  csrf?: string;
  logo?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function PasswordInput(props: {
  id: string;
  name: string;
  label: string;
  autocomplete: string;
  error?: string;
  showLabel: string;
  hideLabel: string;
  onInput?: (value: string, input: HTMLInputElement) => void;
}) {
  const [visible, setVisible] = createSignal(false);
  return (
    <div class="field">
      <label class="field-label" for={props.id}>{props.label}</label>
      <div class="password-field">
        <input
          id={props.id}
          type={visible() ? 'text' : 'password'}
          name={props.name}
          class={`input-field${props.error ? ' input-error' : ''}`}
          placeholder=" "
          autocomplete={props.autocomplete}
          required
          aria-invalid={props.error ? 'true' : undefined}
          aria-describedby={props.error ? `${props.id}-error` : undefined}
          onInput={(e) => props.onInput?.(e.currentTarget.value, e.currentTarget)}
        />
        <button
          type="button"
          class="password-toggle"
          aria-label={visible() ? props.hideLabel : props.showLabel}
          onClick={() => setVisible(!visible())}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <Show
              when={visible()}
              fallback={<><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></>}
            >
              <path d="M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4.1M6.6 6.6A17 17 0 0 0 2 12s3.6 7 10 7c1.7 0 3.2-.4 4.5-1M9.9 9.9a3 3 0 0 0 4.2 4.2"/>
            </Show>
          </svg>
        </button>
      </div>
      <Show when={props.error}>
        <div id={`${props.id}-error`} class="error-text" role="alert">{props.error}</div>
      </Show>
    </div>
  );
}

function SetPasswordForm(props: { config: FormConfig }) {
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
  const [passwordMismatch, setPasswordMismatch] = createSignal(false);
  const passwordRegex = props.config.step.passwordRegex;

  const checkPassword = (value: string) => {
    if (!passwordRegex || value.length === 0) { setPasswordNotAllowed(false); return; }
    try { setPasswordNotAllowed(!new RegExp(passwordRegex).test(value)); } catch (_) {}
  };

  const checkConfirm = (password: string, confirm: string) => {
    setPasswordMismatch(confirm.length > 0 && password !== confirm);
  };

  const handleSubmit = (e: SubmitEvent) => {
    const form = e.currentTarget as HTMLFormElement;
    const password = (form.elements.namedItem('password') as HTMLInputElement | null)?.value ?? '';
    const confirm  = (form.elements.namedItem('confirm')  as HTMLInputElement | null)?.value ?? '';
    if (password !== confirm) { e.preventDefault(); setPasswordMismatch(true); return; }
    if (passwordRegex) {
      try {
        if (!new RegExp(passwordRegex).test(password)) { e.preventDefault(); setPasswordNotAllowed(true); }
      } catch (_) {}
    }
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
        <div class="error-text" role="alert" style="margin-bottom: 16px;">{t()[props.config.error!] ?? props.config.error}</div>
      </Show>
      <form method="post" onSubmit={handleSubmit}>
        <input type="hidden" name="csrf" value={props.config.csrf ?? ''} />
        <PasswordInput
          id="password"
          name="password"
          label={t().password_placeholder}
          autocomplete="new-password"
          error={passwordNotAllowed() ? t().password_not_allowed : undefined}
          showLabel={t().show_password}
          hideLabel={t().hide_password}
          onInput={(value) => checkPassword(value)}
        />
        <PasswordInput
          id="confirm"
          name="confirm"
          label={t().confirm_placeholder}
          autocomplete="new-password"
          error={passwordMismatch() ? t().password_mismatch : undefined}
          showLabel={t().show_password}
          hideLabel={t().hide_password}
          onInput={(value, input) => {
            const form = input.closest('form')!;
            const pw = (form.elements.namedItem('password') as HTMLInputElement).value;
            checkConfirm(pw, value);
          }}
        />
        <button type="submit" formAction={`/challenge/set-password?ui_locale=${currentLocale()}`} class="btn btn-primary">
          {t().continue}
        </button>
      </form>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <SetPasswordForm config={config} />, root);
}
