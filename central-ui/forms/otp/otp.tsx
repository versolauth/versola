import { render } from 'solid-js/web';
import { createSignal, onCleanup, onMount, Show } from 'solid-js';
import { PageBrand, ClientBadge } from '../shared/brand';
import { LocaleSelector, createTranslations } from '../shared/locale';
import type { BaseFormConfig } from '../shared/config';
import { canSubmitInline, submitInline } from '../shared/inline';

type OtpStep = { type: 'otp'; length?: number; resendAfter?: number; lockedSeconds?: number; destination: string };

interface FormConfig extends BaseFormConfig {
  step: OtpStep;
  error?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}



function OtpForm(props: { config: FormConfig }) {
  const { t, locale: currentLocale, changeLocale } = createTranslations(props.config);
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
  let resending = false;
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
      <LocaleSelector config={props.config} current={currentLocale()} onChange={changeLocale} />
      <ClientBadge client={props.config.client} locale={currentLocale()} defaultLocale={props.config.defaultLocale} />
      <h1>{t().title}</h1>
      <p class="otp-description">{description()}</p>
      <Show when={lockRemaining() > 0}>
        <div class="error-text error-text-block" role="alert">
          {(t().locked_for ?? 'Input locked. Try again in {seconds}s.').replace('{seconds}', String(lockRemaining()))}
        </div>
      </Show>
      <Show when={props.config.error && lockRemaining() <= 0}>
        <div class="error-text error-text-block" role="alert">{t()[props.config.error!] ?? props.config.error}</div>
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
        <form
          method="post"
          action={`/challenge/otp/resend?ui_locale=${currentLocale()}`}
          onSubmit={(e) => {
            // From script, so that a proof the server asks for is solved unseen (see shared/inline).
            if (!canSubmitInline(props.config)) return;
            e.preventDefault();
            const form = e.currentTarget;
            if (resending) return;
            resending = true;
            submitInline(form.action, new URLSearchParams(new FormData(form) as unknown as Record<string, string>), props.config.csrf ?? '', currentLocale()).catch(() => {
              HTMLFormElement.prototype.submit.call(form);
            });
          }}
        >
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
