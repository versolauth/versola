import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';

// The brand zone above the card: the identity provider's logo (from the system settings) on a light
// tile, or the Versola wordmark when none is set or it fails to load. A client's own logo belongs
// inside the card.
function PageBrand(props: { logo?: string }) {
  const [failed, setFailed] = createSignal(false);
  return (
    <div class="page-brand" aria-hidden="true">
      <Show
        when={props.logo && !failed()}
        fallback={
          <svg class="page-wordmark" viewBox="-2 0 286 56" height="28" fill="none" stroke="currentColor" stroke-width="8" stroke-linecap="round" stroke-linejoin="round">
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
        <span class="page-brand-tile">
          <img src={props.logo} alt="" style="height:40px" onError={() => setFailed(true)} />
        </span>
      </Show>
    </div>
  );
}

interface FormConfig { step: { csrfToken: string; postLogoutRedirectUri?: string; state?: string }; t: Record<string, string>; locale?: string; locales?: string[]; allT?: Record<string, Record<string, string>>; logo?: string; }
declare global { interface Window { __VERSOLA_FORM__?: FormConfig } }

// The language's own name ("English", "Русский"); falls back to the bare code where Intl lacks it.
const languageName = (code: string) => {
  try {
    const name = new Intl.DisplayNames([code], { type: 'language' }).of(code) ?? code;
    return name.charAt(0).toLocaleUpperCase(code) + name.slice(1);
  } catch (_) {
    return code;
  }
};

function ConfirmLogout(props: { config: FormConfig }) {
  const allT = props.config.allT ?? {};
  const [locale, setLocale] = createSignal(props.config.locale ?? 'en');
  const t = () => ({ ...props.config.t, ...(allT[locale()] ?? {}) });
  return <>
  <PageBrand logo={props.config.logo} />
  <div class="container">
    <Show when={(props.config.locales ?? []).length > 1}>
      <select value={locale()} onChange={e => setLocale(e.currentTarget.value)} aria-label="Language">
        {(props.config.locales ?? []).map(l => <option value={l}>{languageName(l)}</option>)}
      </select>
    </Show>
    <div class="logout-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="M16 17l5-5-5-5" /><path d="M21 12H9" />
      </svg>
    </div>
    <h1>{t().title}</h1><p class="logout-description">{t().description}</p>
    <form method="post" action={`/logout?ui_locale=${encodeURIComponent(locale())}`}>
      <input type="hidden" name="csrf_token" value={props.config.step.csrfToken} />
      <Show when={props.config.step.postLogoutRedirectUri}>
        {uri => <input type="hidden" name="post_logout_redirect_uri" value={uri()} />}
      </Show>
      <Show when={props.config.step.state}>
        {state => <input type="hidden" name="state" value={state()} />}
      </Show>
      <button class="btn btn-primary" type="submit">{t().confirm_button}</button>
    </form>
    <button class="btn btn-secondary" type="button" onClick={() => history.back()}>{t().cancel_button}</button>
  </div>
  </>;
}
const config = window.__VERSOLA_FORM__; const root = document.getElementById('versola-form-root');
if (config && root) render(() => <ConfirmLogout config={config} />, root);