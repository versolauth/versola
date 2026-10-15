import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';
import { PageBrand, ClientBadge } from '../shared/brand';
import { LocaleSelector, createTranslations } from '../shared/locale';
import { PasswordInput } from '../shared/password-input';
import type { BaseFormConfig } from '../shared/config';

type SetPasswordStep = { type: 'set-password'; passwordRegex?: string };

interface FormConfig extends BaseFormConfig {
  step: SetPasswordStep;
  error?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function SetPasswordForm(props: { config: FormConfig }) {
  const { t, locale: currentLocale, changeLocale } = createTranslations(props.config);

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
      <LocaleSelector config={props.config} current={currentLocale()} onChange={changeLocale} />
      <ClientBadge client={props.config.client} locale={currentLocale()} defaultLocale={props.config.defaultLocale} />
      <h1>{t().title}</h1>
      <Show when={props.config.error}>
        <div class="error-text error-text-block" role="alert">{t()[props.config.error!] ?? props.config.error}</div>
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
