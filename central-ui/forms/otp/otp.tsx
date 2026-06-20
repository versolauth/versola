import { render } from 'solid-js/web';
import { createSignal, onCleanup, onMount, Show } from 'solid-js';

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

type OtpStep = { type: 'otp'; length?: number; resendAfter?: number; lockedSeconds?: number };

interface FormConfig {
  step: OtpStep;
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
  let submitRef!: HTMLButtonElement;

  const handleInput = (value: string) => {
    const code = value.replace(/[^0-9]/g, '').slice(0, otpLength);
    setOtp(code);
    if (code.length === otpLength) {
      submitRef.form?.requestSubmit(submitRef);
    }
  };

  const [remaining, setRemaining] = createSignal(Math.max(props.config.step.resendAfter ?? 60, props.config.step.lockedSeconds ?? 0));
  const [lockRemaining, setLockRemaining] = createSignal(props.config.step.lockedSeconds ?? 0);
  const timer = setInterval(() => {
    setRemaining((s) => (s > 0 ? s - 1 : 0));
    setLockRemaining((s) => (s > 0 ? s - 1 : 0));
  }, 1000);
  onCleanup(() => clearInterval(timer));

  onMount(() => {
    if (!props.config.previewId && lockRemaining() <= 0) inputRef.focus();
  });

  return (
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
      <h1>{t().title}</h1>
      <p class="otp-description">{t().description}</p>
      <Show when={lockRemaining() > 0}>
        <div class="error-text" style="margin-bottom: 8px;">
          {(t().locked_for ?? 'Input locked. Try again in {seconds}s.').replace('{seconds}', String(lockRemaining()))}
        </div>
      </Show>
      <Show when={props.config.error && lockRemaining() <= 0}>
        <div class="error-text" style="margin-bottom: 8px;">{t()[props.config.error!] ?? props.config.error}</div>
      </Show>
      <form method="post">
        <div class="otp-wrapper" onClick={() => lockRemaining() <= 0 && inputRef.focus()}>
          <div class="otp-dots">
            {Array.from({ length: otpLength }, (_, i) => {
              const digit = otp()[i];
              return (
                <div class={`otp-cell${digit !== undefined ? ' otp-cell-filled' : ''}${i === otp().length && lockRemaining() <= 0 ? ' otp-cell-active' : ''}`}>
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
            onInput={(e) => handleInput(e.currentTarget.value)}
            disabled={lockRemaining() > 0}
            required
          />
        </div>
        <button ref={submitRef} type="submit" formAction={`/challenge/otp?ui_locale=${currentLocale()}`} class="btn btn-primary" disabled={lockRemaining() > 0}>
          {t().verify_button}
        </button>
      </form>
      <div class="divider"><span>{t().divider}</span></div>
      <Show
        when={remaining() <= 0}
        fallback={
          <p class="resend-timer">
            {(t().resend_in ?? 'Resend available in {seconds}s').replace('{seconds}', String(remaining()))}
          </p>
        }
      >
        <form method="post" action={`/challenge/otp/resend?ui_locale=${currentLocale()}`}>
          <button type="submit" class="btn btn-secondary">
            {t().resend_button}
          </button>
        </form>
      </Show>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <OtpForm config={config} />, root);
}
