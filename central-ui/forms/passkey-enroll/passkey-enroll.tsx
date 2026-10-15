import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';
import { getRegistrationResponse, submitViaForm } from '../passkey/webauthn';

// The language's own name ("English", "Русский"); falls back to the bare code where Intl lacks it.
const languageName = (code: string) => {
  try {
    const name = new Intl.DisplayNames([code], { type: 'language' }).of(code) ?? code;
    return name.charAt(0).toLocaleUpperCase(code) + name.slice(1);
  } catch (_) {
    return code;
  }
};

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
        <svg class="locale-globe" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"/>
        </svg>
        {languageName(props.current)}
      </button>
      <Show when={open()}>
        <div class="locale-options">
          {props.locales.map((loc) => (
            <button
              type="button"
              class={`locale-option${loc === props.current ? ' locale-option-active' : ''}`}
              onMouseDown={() => { props.onChange(loc); setOpen(false); }}
            >
              {languageName(loc)}
            </button>
          ))}
        </div>
      </Show>
    </div>
  );
}

type PasskeyEnrollStep = { type: 'passkey-enroll'; publicKeyOptions: string };

interface FormConfig {
  step: PasskeyEnrollStep;
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

function PasskeyEnrollForm(props: { config: FormConfig }) {
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
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
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
          <div id="passkey-name-error" class="field-error error-text" role="alert">{t().name_invalid}</div>
        </Show>
      </div>

      <Show when={enrollError()}>
        <div class="phone-error-message error-text" role="alert">{t().enroll_failed}</div>
      </Show>

      <button type="button" class="btn btn-primary" disabled={busy()} onClick={handleEnroll}>
        {t().enroll_button}
      </button>
      <button type="button" class="btn btn-secondary" disabled={busy()} onClick={handleSkip}>
        {t().skip_button}
      </button>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <PasskeyEnrollForm config={config!} />, root);
}
