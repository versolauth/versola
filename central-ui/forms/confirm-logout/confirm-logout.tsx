import { render } from 'solid-js/web';
import { Show } from 'solid-js';
import { PageBrand } from '../shared/brand';
import type { BaseFormConfig } from '../shared/config';
import { LocaleSelector, createTranslations } from '../shared/locale';

interface FormConfig extends BaseFormConfig {
  step: { csrfToken: string; postLogoutRedirectUri?: string; state?: string };
}
declare global { interface Window { __VERSOLA_FORM__?: FormConfig } }

function ConfirmLogout(props: { config: FormConfig }) {
  const { t, locale, changeLocale } = createTranslations(props.config);
  return <>
  <PageBrand logo={props.config.logo} />
  <div class="container">
    <LocaleSelector config={props.config} current={locale()} onChange={changeLocale} />
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