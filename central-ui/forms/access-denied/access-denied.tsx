import { render } from 'solid-js/web';
import { PageBrand } from '../shared/brand';
import { LocaleSelector, createTranslations } from '../shared/locale';
import type { BaseFormConfig } from '../shared/config';

type AccessDeniedStep = { type: 'access-denied'; redirectUri: string };

interface FormConfig extends BaseFormConfig {
  step: AccessDeniedStep;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function AccessDeniedForm(props: { config: FormConfig }) {
  const { t, locale: currentLocale, changeLocale } = createTranslations(props.config);
  const redirectUri = props.config.step.redirectUri;

  return (
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <LocaleSelector config={props.config} current={currentLocale()} onChange={changeLocale} />
      <div class="access-denied-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"/>
          <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
        </svg>
      </div>
      <h1>{t().title}</h1>
      <p class="access-denied-description">{t().description}</p>
      <a href={redirectUri} class="btn btn-primary">
        {t().return_button}
      </a>
    </div>
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <AccessDeniedForm config={config!} />, root);
}
