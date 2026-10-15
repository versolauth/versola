import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';
import { getRegistrationResponse, submitViaForm } from '../passkey/webauthn';
import { PageBrand, ClientBadge } from '../shared/brand';
import { LocaleSelector, createTranslations } from '../shared/locale';
import type { BaseFormConfig } from '../shared/config';

type PasskeyEnrollStep = { type: 'passkey-enroll'; publicKeyOptions: string };

interface FormConfig extends BaseFormConfig {
  step: PasskeyEnrollStep;
  error?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function PasskeyEnrollForm(props: { config: FormConfig }) {
  const { t, locale: currentLocale, changeLocale } = createTranslations(props.config);
  const step = props.config.step;

  const [busy, setBusy] = createSignal(false);
  const [enrollError, setEnrollError] = createSignal(!!props.config.error);
  const [name, setName] = createSignal('');
  const [nameError, setNameError] = createSignal(false);

  const NAME_REGEX = /^[\p{L}\p{N} ()-]+$/u;

  const handleEnroll = async () => {
    if (busy()) return;
    const currentName = name();
    if (!NAME_REGEX.test(currentName) || currentName !== currentName.trim()) {
      setNameError(true);
      setEnrollError(false);
      return;
    }
    setNameError(false);
    setBusy(true);
    setEnrollError(false);
    try {
      const response = await getRegistrationResponse(step.publicKeyOptions);
      submitViaForm(`/challenge/passkey/enroll?ui_locale=${currentLocale()}`, { response, name: currentName, csrf: props.config.csrf ?? '' });
    } catch (_) {
      setEnrollError(true);
      setBusy(false);
    }
  };

  const handleSkip = () => {
    if (busy()) return;
    setBusy(true);
    submitViaForm(`/challenge/passkey/skip?ui_locale=${currentLocale()}`, { csrf: props.config.csrf ?? '' });
  };

  return (
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <LocaleSelector config={props.config} current={currentLocale()} onChange={changeLocale} />
      <ClientBadge client={props.config.client} locale={currentLocale()} defaultLocale={props.config.defaultLocale} />
      <div class="passkey-enroll-icon" aria-hidden="true">
        <span class="passkey-icon" />
      </div>
      <h1>{t().title}</h1>
      <p class="passkey-enroll-description">{t().description}</p>

      <div class="name-field field">
        <label class="field-label" for="passkey-name">{t().name_placeholder}</label>
        <input
          id="passkey-name"
          class={`input-field${nameError() ? ' input-error' : ''}`}
          type="text"
          value={name()}
          onInput={(e) => { setName(e.currentTarget.value); setNameError(false); }}
          disabled={busy()}
          placeholder=" "
          autocomplete="off"
          aria-invalid={nameError() ? 'true' : undefined}
          aria-describedby={nameError() ? 'passkey-name-error' : undefined}
        />
        <Show when={nameError()}>
          <div id="passkey-name-error" class="error-text" role="alert">{t().name_invalid}</div>
        </Show>
      </div>

      <Show when={enrollError()}>
        <div class="error-text error-text-block" role="alert">{t().enroll_failed}</div>
      </Show>

      <button type="button" class="btn btn-primary" disabled={busy()} onClick={handleEnroll}>
        {t().enroll_button}
      </button>
      <button type="button" class="btn btn-secondary" disabled={busy()} onClick={handleSkip}>
        {t().skip_button}
      </button>
    </div>
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <PasskeyEnrollForm config={config!} />, root);
}
