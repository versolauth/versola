import { render } from 'solid-js/web';
import { createSignal, onMount, Show } from 'solid-js';
import { PhoneField, type PhoneFieldApi } from './phone-field';
import type { PhoneRegion } from './phone';
import { canSubmitInline, submitInline } from '../shared/inline';
import { getAssertionResponse, isPasskeyCancellation, passkeysSupported, submitViaForm } from '../passkey/webauthn';
import { PageBrand, ClientBadge } from '../shared/brand';
import { LocaleSelector, createTranslations } from '../shared/locale';
import { PasswordInput } from '../shared/password-input';
import type { BaseFormConfig } from '../shared/config';

type PrimaryCredential = 'email' | 'phone' | 'login';
type CredentialStep = {
  type: 'credential';
  primaryCredentials: PrimaryCredential[];
  inlinePassword: boolean;
  passkey: boolean;
  // Only a passkey of the already identified user is asked for (a step-up); the form starts it by itself.
  passkeyOnly?: boolean;
  phoneRegions?: PhoneRegion[];
  passwordRegex?: string;
};

interface FormConfig extends BaseFormConfig {
  step: CredentialStep;
  error?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function CredentialForm(props: { config: FormConfig }) {
  const { t, locale: currentLocale, changeLocale } = createTranslations(props.config);
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
      // A passkey-only step has no other way in: a missing or dismissed passkey ends in the access page.
      if (step.passkeyOnly) {
        denyPasskeyOnly();
        return;
      }
      if (isPasskeyCancellation(error)) {
        setPasskeyBusy(false);
        return;
      }
      setPasskeyErrorKey('passkey_failed');
      setPasskeyBusy(false);
    }
  };

  const denyPasskeyOnly = () =>
    submitViaForm(`/challenge/passkey/unavailable?ui_locale=${currentLocale()}`, { csrf: props.config.csrf ?? '' });

  // A step-up that needs a passkey has nothing to type and no button: the browser's own prompt opens at once.
  // If this device has no passkey for the user, or the prompt is dismissed, the access page follows.
  onMount(() => {
    if (!step.passkeyOnly) return;
    if (passkeysSupported()) void handlePasskey();
    else denyPasskeyOnly();
  });

  let submitting = false;
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
    // Sent from script, so that a request the server wants a proof for solves it unseen and goes on to the next step as
    // any other would. The forms that carry a password never lead to a code.
    if (!e.defaultPrevented && !showPassword() && canSubmitInline(props.config)) {
      e.preventDefault();
      if (submitting) return;
      submitting = true;
      const action = formAction();
      submitInline(action, new URLSearchParams(new FormData(form) as unknown as Record<string, string>), props.config.csrf ?? '', currentLocale()).catch(() => {
        form.action = action;
        HTMLFormElement.prototype.submit.call(form);
      });
    }
  };

  return (
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <LocaleSelector config={props.config} current={currentLocale()} onChange={changeLocale} />
      <ClientBadge client={props.config.client} locale={currentLocale()} defaultLocale={props.config.defaultLocale} />
      <h1>{step.passkeyOnly ? t().passkey_only_title : t().title}</h1>

      <Show when={props.config.error && !isPasskeyError(props.config.error)}>
        <div class="error-text error-text-block" role="alert">{t()[props.config.error!] ?? props.config.error}</div>
      </Show>

      <Show
        when={step.passkeyOnly}
        fallback={
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
                  <>
                    <input
                      id="credential-phone"
                      type="tel"
                      name="phone"
                      class="input-field"
                      placeholder=" "
                      autocomplete="tel"
                      pattern="^\+[1-9]\d{6,14}$"
                      required
                      aria-describedby="credential-phone-hint"
                    />
                    <div id="credential-phone-hint" class="field-hint">{t().phone_hint}</div>
                  </>
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
        }
      >
        <p class="passkey-only-hint" role="status">{t().passkey_only_hint}</p>
      </Show>

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
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <CredentialForm config={config} />, root);
}
