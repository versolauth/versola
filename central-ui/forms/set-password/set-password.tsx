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

// The partner's app the user is signing in to, inside the card: its icon and name. Only for a partner's app (one with a
// consent flow); the identity provider's own apps are not announced.
function ClientBadge(props: { client?: { name?: string; logo?: string; partner: boolean } }) {
  const [failed, setFailed] = createSignal(false);
  const initial = () => Array.from(props.client?.name?.trim() ?? '')[0]?.toLocaleUpperCase();
  return (
    <Show when={props.client?.partner && (props.client.name || props.client.logo)}>
      <div class="client-badge">
        <Show
          when={props.client?.logo && !failed()}
          fallback={<Show when={initial()}>{(letter) => <span class="client-badge-icon client-badge-initial" aria-hidden="true">{letter()}</span>}</Show>}
        >
          <span class="client-badge-icon" aria-hidden="true">
            <img src={props.client?.logo} alt="" style="height:32px;width:32px;object-fit:contain" onError={() => setFailed(true)} />
          </span>
        </Show>
        <Show when={props.client?.name}>{(name) => <span class="client-badge-name">{name()}</span>}</Show>
      </div>
    </Show>
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

function LocaleDropdown(props: { locales: string[]; current: string; names?: Record<string, string>; onChange: (l: string) => void }) {
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
  client?: { name?: string; logo?: string; partner: boolean };
  localeNames?: Record<string, string>;
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
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} names={props.config.localeNames} onChange={changeLocale} />
        </div>
      </Show>
      <ClientBadge client={props.config.client} />
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
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <SetPasswordForm config={config} />, root);
}
