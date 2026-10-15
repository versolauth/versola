import { render } from 'solid-js/web';
import { createSignal, onCleanup, onMount, Show } from 'solid-js';

// The brand zone above the card. The client's logo sits on a light tile so any logo reads on the
// brand-coloured page; with no logo (or one that fails to load) it is the Versola wordmark, or the
// client's initial when the caller asks for one (the consent screen shows only the partner's icon).
function PageBrand(props: { logo?: string; initial?: string; icon?: boolean }) {
  const [failed, setFailed] = createSignal(false);
  const letter = () => Array.from(props.initial?.trim() ?? '')[0]?.toLocaleUpperCase();
  return (
    <div class="page-brand" aria-hidden="true">
      <Show
        when={props.logo && !failed()}
        fallback={
          <Show
            when={props.initial !== undefined}
            fallback={
              <svg class="page-wordmark" viewBox="-2 0 286 56" height="28" fill="none" stroke="currentColor" stroke-width="8" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="Versola">
          <path d="M2 22 L18 48 L34 22" />
          <path d="M50 35 H76 A13 13 0 1 0 72.5 44.3" />
          <path d="M98 22 V48 M98 36 C98 26 104 22 114 22" />
          <path d="M158.5 27 C156.5 23 152.5 22 148.5 22 C142.5 22 138.5 25 138.5 29 C138.5 33 142.5 34.5 148.5 36 C154.5 37.5 158.5 39 158.5 42.5 C158.5 46.5 154.5 48.5 148.5 48.5 C143.5 48.5 139.5 46.5 138 42.5" />
          <circle cx="193.5" cy="35" r="13" />
          <path d="M228.5 6 V48" />
          <circle cx="263.5" cy="35" r="13" />
          <path d="M276.5 22 V48" />
        </svg>
            }
          >
            <Show when={letter()}>{(l) => <span class="page-brand-tile page-brand-initial">{l()}</span>}</Show>
          </Show>
        }
      >
        <span class={`page-brand-tile${props.icon ? ' page-brand-icon' : ''}`}>
          <img src={props.logo} alt="" style="height:40px" onError={() => setFailed(true)} />
        </span>
      </Show>
    </div>
  );
}

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

type OtpStep = { type: 'otp'; length?: number; resendAfter?: number; lockedSeconds?: number; destination: string };

interface FormConfig {
  step: OtpStep;
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
  const description = () => {
    const [before, ...rest] = t().description.split('{destination}');
    return (
      <>
        {before}
        <span class="otp-destination">{props.config.step.destination}</span>
        {rest.join('{destination}')}
      </>
    );
  };
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
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
      <h1>{t().title}</h1>
      <p class="otp-description">{description()}</p>
      <Show when={lockRemaining() > 0}>
        <div class="error-text" role="alert" style="margin-bottom: 16px;">
          {(t().locked_for ?? 'Input locked. Try again in {seconds}s.').replace('{seconds}', String(lockRemaining()))}
        </div>
      </Show>
      <Show when={props.config.error && lockRemaining() <= 0}>
        <div class="error-text" role="alert" style="margin-bottom: 16px;">{t()[props.config.error!] ?? props.config.error}</div>
      </Show>
        <form method="post">
        <input type="hidden" name="csrf" value={props.config.csrf ?? ''} />
        <div class="otp-wrapper" onClick={() => lockRemaining() <= 0 && inputRef.focus()}>
          <div class="otp-dots">
            {Array.from({ length: otpLength }, (_, i) => {
              const digit = otp()[i];
              return (
                <div class={`otp-cell${digit !== undefined ? ' otp-cell-filled' : ''}${i === otp().length && lockRemaining() <= 0 ? ' otp-cell-active' : ''}`}>
                  {digit ?? ''}
                </div>
              );
            })}
          </div>
          <input
            ref={inputRef}
            type="text"
            name="code"
            aria-label={t().code_placeholder}
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
          <input type="hidden" name="csrf" value={props.config.csrf ?? ''} />
          <button type="submit" class="btn btn-secondary">
            {t().resend_button}
          </button>
        </form>
      </Show>
    </div>
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <OtpForm config={config} />, root);
}
