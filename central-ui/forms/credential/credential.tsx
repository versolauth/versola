import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';
import { PhoneField, type PhoneFieldApi } from './phone-field';
import type { PhoneRegion } from './phone';
import { getAssertionResponse, isPasskeyCancellation, passkeysSupported, submitViaForm } from '../passkey/webauthn';

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
  phoneRegions?: PhoneRegion[];
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
  const phoneRegions = step.phoneRegions ?? [];
  let phoneField: PhoneFieldApi | undefined;

  // login always carries a password; email/phone show it only when inlinePassword is set
  const showPassword = () => isLoginFlow || !!step.inlinePassword;

  const formAction = () =>
    `${showPassword() ? `/challenge/${challengeKind}-password` : `/challenge/${challengeKind}`}?ui_locale=${currentLocale()}`;

  const [passwordNotAllowed, setPasswordNotAllowed] = createSignal(false);
  const [passkeyBusy, setPasskeyBusy] = createSignal(false);
  const [logoLoadFailed, setLogoLoadFailed] = createSignal(false);

  // All passkey-related errors (client failures and the server's passkey_failed /
  // passkey_orphaned) are surfaced through a dismissible popup rather than inline text.
  const passkeyErrorKeys = ['passkey_failed'];
  const isPasskeyError = (e?: string): boolean => !!e && passkeyErrorKeys.includes(e);
  const [passkeyErrorKey, setPasskeyErrorKey] = createSignal<string | null>(
    isPasskeyError(props.config.error) ? props.config.error! : null,
  );

  const passwordRegex = step.passwordRegex;

  const checkPassword = (value: string) => {
    if (!passwordRegex || value.length === 0) {
      setPasswordNotAllowed(false);
      return;
    }
    try {
      setPasswordNotAllowed(!new RegExp(passwordRegex).test(value));
    } catch (_) {}
  };

  const showPasskey = () => step.passkey && passkeysSupported();

  const handlePasskey = async () => {
    if (passkeyBusy()) return;
    setPasskeyBusy(true);
    setPasskeyErrorKey(null);
    try {
      const optionsResponse = await fetch(`/challenge/passkey/options?ui_locale=${currentLocale()}`);
      // Conversation expired (410) or the auth service failed (500): reload the
      // conversation page itself so the server renders the proper terminal screen instead
      // of surfacing a generic passkey-failed dialog on top of a stale form.
      if (optionsResponse.status === 410 || optionsResponse.status === 500) {
        window.location.href = '/challenge';
        return;
      }
      if (!optionsResponse.ok) throw new Error('options request failed');
      const optionsJson = await optionsResponse.text();
      const response = await getAssertionResponse(optionsJson);
      submitViaForm(`/challenge/passkey?ui_locale=${currentLocale()}`, { response, csrf: props.config.csrf ?? '' });
    } catch (error) {
      if (isPasskeyCancellation(error)) {
        setPasskeyBusy(false);
        return;
      }
      setPasskeyErrorKey('passkey_failed');
      setPasskeyBusy(false);
    }
  };

  const handleSubmit = (e: SubmitEvent) => {
    const form = e.currentTarget as HTMLFormElement;
    if (single === 'phone' && phoneField && !phoneField.validate()) {
      e.preventDefault();
      return;
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
      <Show when={!props.config.logo || !logoLoadFailed()}>
        <div class="brand-mark" aria-hidden="true">
          <Show
            when={props.config.logo}
            fallback={
              <svg viewBox="0 0 64 64" fill="none">
                <path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z"
                      fill="var(--accent)" fill-opacity="0.06"/>
                <path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z"
                      fill="none" stroke="var(--accent)" stroke-width="2.2"/>
                <text x="32" y="41" font-family="-apple-system, Inter, sans-serif" font-weight="800"
                      font-size="26" fill="var(--accent)" text-anchor="middle">V</text>
              </svg>
            }
          >
            <img src={props.config.logo} alt="" onError={() => setLogoLoadFailed(true)} />
          </Show>
        </div>
      </Show>
      <h1>{t().title}</h1>

      <Show when={props.config.error && !isPasskeyError(props.config.error)}>
        <div class="error-text" role="alert" style="margin-bottom: 16px;">{t()[props.config.error!] ?? props.config.error}</div>
      </Show>

      <form method="post" onSubmit={handleSubmit}>
        <input type="hidden" name="csrf" value={props.config.csrf ?? ''} />
        <Show when={single === 'email'}>
          <div class="field">
            <label class="field-label" for="credential-email">{t().email_placeholder}</label>
            <input id="credential-email" type="email" name="email" class="input-field" placeholder=" " autocomplete="email" required />
          </div>
        </Show>
        <Show when={single === 'phone'}>
          <div class="field">
            <label class="field-label" for="credential-phone">{t().phone_placeholder}</label>
            <Show
              when={phoneRegions.length > 0}
              fallback={
                <input
                  id="credential-phone"
                  type="tel"
                  name="phone"
                  class="input-field"
                  placeholder=" "
                  autocomplete="tel"
                  pattern="^\+[1-9]\d{6,14}$"
                  required
                />
              }
            >
              <PhoneField id="credential-phone" regions={phoneRegions} t={t} locale={currentLocale} apiRef={(api) => (phoneField = api)} />
            </Show>
          </div>
        </Show>
        <Show when={single === 'login'}>
          <div class="field">
            <label class="field-label" for="credential-login">{t().login_placeholder}</label>
            <input id="credential-login" type="text" name="login" class="input-field" placeholder=" " autocomplete="username" required />
          </div>
        </Show>
        <Show when={single === null && primaries.length > 0}>
          <div class="field">
            <label class="field-label" for="credential-login">{combinedPlaceholder()}</label>
            <input id="credential-login" type="text" name="login" class="input-field" placeholder=" " autocomplete="username" required />
          </div>
        </Show>

        <Show when={showPassword()}>
          <PasswordInput
            id="credential-password"
            name="password"
            label={t().password_placeholder}
            autocomplete="current-password"
            error={passwordNotAllowed() ? t().password_not_allowed : undefined}
            showLabel={t().show_password}
            hideLabel={t().hide_password}
            onInput={(value) => checkPassword(value)}
          />
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
