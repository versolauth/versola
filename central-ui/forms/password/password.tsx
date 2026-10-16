import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';
import { PageBrand, ClientBadge } from '../shared/brand';
import { LocaleSelector, createTranslations } from '../shared/locale';
import { PasswordInput } from '../shared/password-input';
import type { BaseFormConfig } from '../shared/config';

type PasswordStep = { type: 'password'; passwordRegex?: string };

interface FormConfig extends BaseFormConfig {
  step: PasswordStep;
  error?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function PasswordForm(props: { config: FormConfig }) {
  const { t, locale: currentLocale, changeLocale } = createTranslations(props.config);

  const [passwordNotAllowed, setPasswordNotAllowed] = createSignal(false);
  const passwordRegex = props.config.step.passwordRegex;

  const checkPassword = (value: string) => {
    if (!passwordRegex || value.length === 0) {
      setPasswordNotAllowed(false);
      return;
    }
    try {
      setPasswordNotAllowed(!new RegExp(passwordRegex).test(value));
    } catch (_) {}
  };

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
          autocomplete="current-password"
          error={passwordNotAllowed() ? t().password_not_allowed : undefined}
          showLabel={t().show_password}
          hideLabel={t().hide_password}
          onInput={(value) => checkPassword(value)}
        />
        <button type="submit" formAction={`/challenge/password?ui_locale=${currentLocale()}`} class="btn btn-primary">
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
  render(() => <PasswordForm config={config} />, root);
}
