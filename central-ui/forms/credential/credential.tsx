import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';

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

type PrimaryCredential = 'email' | 'phone' | 'login';
type CredentialStep = {
  type: 'credential';
  primaryCredentials: PrimaryCredential[];
  inlinePassword: boolean;
  passkey: boolean;
};

interface FormConfig {
  step: CredentialStep;
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

function CredentialForm(props: { config: FormConfig }) {
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
  const primaries = step.primaryCredentials ?? [];
  const isLoginFlow = primaries.includes('login');
  const single = primaries.length === 1 ? primaries[0] : null;
  const challengeKind = single ?? 'credential';
  const combinedPlaceholder = () => primaries.map((p) => t()[`${p}_placeholder`] ?? p).join(' / ');

  // login always carries a password; email/phone show it only when inlinePassword is set
  const showPassword = () => isLoginFlow || !!step.inlinePassword;

  const formAction = () =>
    showPassword() ? `/challenge/${challengeKind}-password` : `/challenge/${challengeKind}`;

  return (
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
      <h1>{t().title}</h1>

      <form method="post">
        <Show when={single === 'email'}>
          <input type="email" name="email" class="input-field" placeholder={t().email_placeholder} required />
        </Show>
        <Show when={single === 'phone'}>
          <input type="tel" name="phone" class="input-field" placeholder={t().phone_placeholder} pattern="^\+[1-9]\d{6,14}$" required />
        </Show>
        <Show when={single === 'login'}>
          <input type="text" name="login" class="input-field" placeholder={t().login_placeholder} required />
        </Show>
        <Show when={single === null && primaries.length > 0}>
          <input type="text" name="login" class="input-field" placeholder={combinedPlaceholder()} required />
        </Show>

        <Show when={showPassword()}>
          <input type="password" name="password" class="input-field" placeholder={t().password_placeholder} required />
        </Show>

        <button type="submit" formAction={formAction()} class="btn btn-primary">
          {t().continue}
        </button>

        <Show when={step.passkey}>
          <div class="credential-option" data-credential="passkey">
            <div class="divider"><span>{t().divider}</span></div>
            <button type="button" class="btn btn-secondary">
              <span class="passkey-icon" aria-hidden="true" />
              <span>{t().passkey_button}</span>
            </button>
          </div>
        </Show>
      </form>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <CredentialForm config={config} />, root);
}
