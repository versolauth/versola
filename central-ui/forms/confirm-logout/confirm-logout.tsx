import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';

interface FormConfig { step: { csrfToken: string; postLogoutRedirectUri?: string; state?: string }; t: Record<string, string>; locale?: string; locales?: string[]; allT?: Record<string, Record<string, string>>; }
declare global { interface Window { __VERSOLA_FORM__?: FormConfig } }

function ConfirmLogout(props: { config: FormConfig }) {
  const allT = props.config.allT ?? {};
  const [locale, setLocale] = createSignal(props.config.locale ?? 'en');
  const t = () => ({ ...props.config.t, ...(allT[locale()] ?? {}) });
  return <div class="container">
    <Show when={(props.config.locales ?? []).length > 1}>
      <select value={locale()} onChange={e => setLocale(e.currentTarget.value)} aria-label="Language">
        {(props.config.locales ?? []).map(l => <option value={l}>{l}</option>)}
      </select>
    </Show>
    <h1>{t().title}</h1><p class="logout-description">{t().description}</p>
    <form method="post" action="/logout">
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
  </div>;
}
const config = window.__VERSOLA_FORM__; const root = document.getElementById('versola-form-root');
if (config && root) render(() => <ConfirmLogout config={config} />, root);