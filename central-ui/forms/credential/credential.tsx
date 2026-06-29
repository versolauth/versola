import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';
import { getAssertionResponse, submitViaForm, passkeysSupported } from '../passkey/webauthn';

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

type PrimaryCredential = 'email' | 'phone' | 'login';
type CredentialStep = {
  type: 'credential';
  primaryCredentials: PrimaryCredential[];
  inlinePassword: boolean;
  passkey: boolean;
  allowedPhonePrefixes?: string[];
  passwordRegex?: string;
};

interface FormConfig {
  step: CredentialStep;
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

function CredentialForm(props: { config: FormConfig }) {
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
  const step = props.config.step;
  const primaries = step.primaryCredentials ?? [];
  const isLoginFlow = primaries.includes('login');
  const single = primaries.length === 1 ? primaries[0] : null;
  const challengeKind = single ?? 'credential';
  const combinedPlaceholder = () => primaries.map((p) => t()[`${p}_placeholder`] ?? p).join(' / ');
  const allowedPhonePrefixes = step.allowedPhonePrefixes ?? [];

  // login always carries a password; email/phone show it only when inlinePassword is set
  const showPassword = () => isLoginFlow || !!step.inlinePassword;

  const formAction = () =>
    `${showPassword() ? `/challenge/${challengeKind}-password` : `/challenge/${challengeKind}`}?ui_locale=${currentLocale()}`;

  const [phoneNotAllowed, setPhoneNotAllowed] = createSignal(false);
  const [passwordNotAllowed, setPasswordNotAllowed] = createSignal(false);
  const [passkeyBusy, setPasskeyBusy] = createSignal(false);

  // All passkey-related errors (client failures and the server's passkey_failed /
  // passkey_orphaned) are surfaced through a dismissible popup rather than inline text.
  const passkeyErrorKeys = ['passkey_failed'];
  const isPasskeyError = (e?: string): boolean => !!e && passkeyErrorKeys.includes(e);
  const [passkeyErrorKey, setPasskeyErrorKey] = createSignal<string | null>(
    isPasskeyError(props.config.error) ? props.config.error! : null,
  );

  const passwordRegex = step.passwordRegex;

  const showPasskey = () => step.passkey && passkeysSupported();

  const handlePasskey = async () => {
    if (passkeyBusy()) return;
    setPasskeyBusy(true);
    setPasskeyErrorKey(null);
    try {
      const optionsJson = await fetch(`/challenge/passkey/options?ui_locale=${currentLocale()}`).then((r) => {
        if (!r.ok) throw new Error('options request failed');
        return r.text();
      });
      const response = await getAssertionResponse(optionsJson);
      submitViaForm(`/challenge/passkey?ui_locale=${currentLocale()}`, { response });
    } catch (_) {
      setPasskeyErrorKey('passkey_failed');
      setPasskeyBusy(false);
    }
  };

  const handleSubmit = (e: SubmitEvent) => {
    const form = e.currentTarget as HTMLFormElement;
    if (single === 'phone' && allowedPhonePrefixes.length > 0) {
      const phone = (form.elements.namedItem('phone') as HTMLInputElement | null)?.value ?? '';
      if (!allowedPhonePrefixes.some((prefix) => phone.startsWith(prefix))) {
        e.preventDefault();
        setPhoneNotAllowed(true);
      }
    }
    if (showPassword() && passwordRegex) {
      const password = (form.elements.namedItem('password') as HTMLInputElement | null)?.value ?? '';
      try {
        if (!new RegExp(passwordRegex).test(password)) {
          e.preventDefault();
          setPasswordNotAllowed(true);
        }
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

      <Show when={props.config.error && !isPasskeyError(props.config.error)}>
        <div class="error-text" style="margin-bottom: 8px;">{t()[props.config.error!] ?? props.config.error}</div>
      </Show>

      <form method="post" onSubmit={handleSubmit}>
        <Show when={single === 'email'}>
          <input type="email" name="email" class="input-field" placeholder={t().email_placeholder} required />
        </Show>
        <Show when={single === 'phone'}>
          <input
            type="tel"
            name="phone"
            class="input-field"
            placeholder={t().phone_placeholder}
            pattern="^\+[1-9]\d{6,14}$"
            required
            onInput={() => phoneNotAllowed() && setPhoneNotAllowed(false)}
          />
          <Show when={phoneNotAllowed()}>
            <div class="phone-error-message error-text">{t().phone_not_allowed}</div>
          </Show>
        </Show>
        <Show when={single === 'login'}>
          <input type="text" name="login" class="input-field" placeholder={t().login_placeholder} required />
        </Show>
        <Show when={single === null && primaries.length > 0}>
          <input type="text" name="login" class="input-field" placeholder={combinedPlaceholder()} required />
        </Show>

        <Show when={showPassword()}>
          <input
            type="password"
            name="password"
            class="input-field"
            placeholder={t().password_placeholder}
            required
            onInput={() => passwordNotAllowed() && setPasswordNotAllowed(false)}
          />
          <Show when={passwordNotAllowed()}>
            <div class="phone-error-message error-text">{t().password_not_allowed}</div>
          </Show>
        </Show>

        <button type="submit" formAction={formAction()} class="btn btn-primary">
          {t().continue}
        </button>

        <Show when={showPasskey()}>
          <div class="credential-option" data-credential="passkey">
            <div class="divider"><span>{t().divider}</span></div>
            <button type="button" class="btn btn-secondary" disabled={passkeyBusy()} onClick={handlePasskey}>
              <span class="passkey-icon" aria-hidden="true" />
              <span>{t().passkey_button}</span>
            </button>
          </div>
        </Show>
      </form>

      <Show when={passkeyErrorKey()}>
        <div class="passkey-dialog-overlay" onClick={() => setPasskeyErrorKey(null)}>
          <div
            class="passkey-dialog"
            role="alertdialog"
            aria-modal="true"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 class="passkey-dialog-title">{t().passkey_error_title}</h2>
            <p class="passkey-dialog-message">{t()[passkeyErrorKey()!] ?? passkeyErrorKey()}</p>
            <button type="button" class="btn btn-primary" onClick={() => setPasskeyErrorKey(null)}>
              {t().close}
            </button>
          </div>
        </div>
      </Show>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <CredentialForm config={config} />, root);
}
