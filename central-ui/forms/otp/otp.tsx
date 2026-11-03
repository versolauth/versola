import { render } from 'solid-js/web';
import { createSignal, onCleanup, onMount, Show } from 'solid-js';
import { PageBrand, ClientBadge } from '../shared/brand';
import { LocaleSelector, createTranslations } from '../shared/locale';
import type { BaseFormConfig } from '../shared/config';
import { canSubmitInline, submitInline, type InlineStep } from '../shared/inline';
import { getAssertionResponse, isPasskeyCancellation, passkeysSupported, submitViaForm } from '../passkey/webauthn';

type OtpStep = { type: 'otp'; length?: number; resendAfter?: number; lockedSeconds?: number; destination: string; pending?: boolean; resendInterval?: number; alternatives?: string[] };

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
  const resendAction = () => `/challenge/otp/resend?ui_locale=${currentLocale()}`;
  /** Asks for a code, from script, answering a proof the server wants unseen. */
  const requestCode = async () => {
    if (resending) return;
    resending = true;
    await submitInline(resendAction(), new URLSearchParams({ csrf: props.config.csrf ?? '' }), shownSent);
  };
  /** The code was sent: show it as the page shows one that was sent before it loaded, without leaving. */
  const shownSent = (step: InlineStep): boolean => {
    if (!step.otp) return false;
    resending = false;
    setErrorShown(false);
    setOtp('');
    setRemaining(props.config.step.resendInterval ?? props.config.step.resendAfter ?? 60);
    inputRef.focus();
    return true;
  };
  // A passkey submitted from this page in place of the code, as the credential form submits one: the options of a ceremony,
  // the authenticator's answer posted to `/challenge/passkey`. A cancellation only closes the prompt; a failure, here or on
  // the server (`passkey_failed`), is told in the same dialog the credential form uses.
  const passkeyOffered = () => !!props.config.step.alternatives?.includes('passkey') && passkeysSupported();
  const [passkeyBusy, setPasskeyBusy] = createSignal(false);
  const [passkeyErrorShown, setPasskeyErrorShown] = createSignal(props.config.error === 'passkey_failed');
  const handlePasskey = async () => {
    if (passkeyBusy()) return;
    setPasskeyBusy(true);
    setPasskeyErrorShown(false);
    try {
      const optionsResponse = await fetch(`/challenge/passkey/options?ui_locale=${currentLocale()}`);
      // Conversation expired (410) or the auth service failed (500): reload the conversation page itself so the server
      // renders the proper terminal screen instead of a passkey dialog on top of a stale form.
      if (optionsResponse.status === 410 || optionsResponse.status === 500) {
        window.location.href = '/challenge';
        return;
      }
      if (!optionsResponse.ok) throw new Error('options request failed');
      const response = await getAssertionResponse(await optionsResponse.text());
      submitViaForm(`/challenge/passkey?ui_locale=${currentLocale()}`, { response, csrf: props.config.csrf ?? '' });
    } catch (error) {
      setPasskeyBusy(false);
      if (!isPasskeyCancellation(error)) setPasskeyErrorShown(true);
    }
  };
  const [otp, setOtp] = createSignal('');
  const [errorShown, setErrorShown] = createSignal(true);
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
    // Left here with no code sent because the request had to prove itself where it could not: ask for it as a resend, at
    // once. A page with script is given the proof to solve and solves it, so it pays with work and waits for nothing; the
    // `pause` the step tells is the price of a client without script (a plain post), which this page is not.
    if (props.config.step.pending && canSubmitInline(props.config)) requestCode().catch(() => { resending = false; });
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
      <Show when={props.config.error && props.config.error !== 'passkey_failed' && errorShown() && lockRemaining() <= 0}>
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
          action={resendAction()}
          onSubmit={(e) => {
            // From script, so that a proof the server asks for is solved unseen (see shared/inline).
            if (!canSubmitInline(props.config)) return;
            e.preventDefault();
            const form = e.currentTarget;
            requestCode().catch(() => {
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
      {/* Another way to pass this step, as the server says it may be passed now and this browser can: last, under the code
          and its resend, which stay the way in; from this page, with no page in between. */}
      <Show when={passkeyOffered()}>
        <div class="divider"><span>{t().divider}</span></div>
        <div class="otp-alternative" data-alternative="passkey">
          <button type="button" class="btn btn-secondary" disabled={passkeyBusy()} onClick={handlePasskey}>
            <span class="passkey-icon" aria-hidden="true" />
            <span>{t().passkey_button}</span>
          </button>
        </div>
      </Show>
      <Show when={passkeyErrorShown()}>
        <div class="passkey-dialog-overlay" onClick={() => setPasskeyErrorShown(false)}>
          <div class="passkey-dialog" role="alertdialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
            <h2 class="passkey-dialog-title">{t().passkey_error_title}</h2>
            <p class="passkey-dialog-message">{t().passkey_failed}</p>
            <button type="button" class="btn btn-primary" onClick={() => setPasskeyErrorShown(false)}>
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
  render(() => <OtpForm config={config} />, root);
}
