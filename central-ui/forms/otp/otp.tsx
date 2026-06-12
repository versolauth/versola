import { render } from 'solid-js/web';
import { createSignal, onCleanup, Show } from 'solid-js';

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

type OtpStep = { type: 'otp'; length?: number; resendAfter?: number };

interface FormConfig {
  step: OtpStep;
  t: Record<string, string>;
  locale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  previewId?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}



function OtpForm(props: { config: FormConfig }) {
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
  const otpLength = props.config.step.length ?? 6;
  const [otp, setOtp] = createSignal('');
  let inputRef!: HTMLInputElement;

  const [remaining, setRemaining] = createSignal(props.config.step.resendAfter ?? 60);
  const timer = setInterval(() => {
    setRemaining((s) => (s > 0 ? s - 1 : 0));
  }, 1000);
  onCleanup(() => clearInterval(timer));

  return (
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
      <h1>{t().title}</h1>
      <p class="otp-description">{t().description}</p>
      <form method="post">
        <div class="otp-wrapper" onClick={() => inputRef.focus()}>
          <div class="otp-dots">
            {Array.from({ length: otpLength }, (_, i) => {
              const digit = otp()[i];
              return (
                <div class={`otp-cell${digit !== undefined ? ' otp-cell-filled' : ''}${i === otp().length ? ' otp-cell-active' : ''}`}>
                  {digit !== undefined ? digit : <span class="otp-cell-dot" />}
                </div>
              );
            })}
          </div>
          <input
            ref={inputRef}
            type="text"
            name="code"
            class="otp-hidden-input"
            inputmode="numeric"
            autocomplete="one-time-code"
            maxlength={otpLength}
            value={otp()}
            onInput={(e) => setOtp(e.currentTarget.value.replace(/[^0-9]/g, '').slice(0, otpLength))}
            required
          />
        </div>
        <button type="submit" formAction="/challenge/otp" class="btn btn-primary">
          {t().verify_button}
        </button>
        <div class="divider"><span>{t().divider}</span></div>
        <Show
          when={remaining() <= 0}
          fallback={
            <p class="resend-timer">
              {(t().resend_in ?? 'Resend available in {seconds}s').replace('{seconds}', String(remaining()))}
            </p>
          }
        >
          <button type="submit" formAction="/challenge/otp/resend" class="btn btn-secondary">
            {t().resend_button}
          </button>
        </Show>
      </form>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <OtpForm config={config} />, root);
}
