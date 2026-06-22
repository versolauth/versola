import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';
import { getRegistrationResponse, submitViaForm } from '../passkey/webauthn';

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

type PasskeyEnrollStep = { type: 'passkey-enroll'; publicKeyOptions: string };

interface FormConfig {
  step: PasskeyEnrollStep;
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

  const handleEnroll = async () => {
    if (busy()) return;
    setBusy(true);
    setEnrollError(false);
    try {
      const response = await getRegistrationResponse(step.publicKeyOptions);
      submitViaForm(`/challenge/passkey/enroll?ui_locale=${currentLocale()}`, { response });
    } catch (_) {
      setEnrollError(true);
      setBusy(false);
    }
  };

  const handleSkip = () => {
    if (busy()) return;
    setBusy(true);
    submitViaForm(`/challenge/passkey/skip?ui_locale=${currentLocale()}`, {});
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

      <Show when={enrollError()}>
        <div class="phone-error-message error-text">{t().enroll_failed}</div>
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
