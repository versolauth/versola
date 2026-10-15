import { render } from 'solid-js/web';
import { Show } from 'solid-js';
import { PageBrand } from '../shared/brand';
import { LocaleSelector, createTranslations } from '../shared/locale';
import type { BaseFormConfig } from '../shared/config';

type ServiceUnavailableStep = { type: 'service-unavailable'; redirectUri?: string };

interface FormConfig extends BaseFormConfig {
  step: ServiceUnavailableStep;
}

declare global {
  interface Window { __VERSOLA_FORM__?: FormConfig }
}

function ServiceUnavailableForm(props: { config: FormConfig }) {
  const { t, locale: currentLocale, changeLocale } = createTranslations(props.config);

  return (
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <LocaleSelector config={props.config} current={currentLocale()} onChange={changeLocale} />
      <div class="service-unavailable-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
      </div>
      <h1>{t().title}</h1>
      <p class="service-unavailable-description">{t().description}</p>
      <Show when={props.config.step.redirectUri}>
        {(redirectUri) => <a href={redirectUri()} class="btn btn-primary service-unavailable-return">{t().return_button}</a>}
      </Show>
    </div>
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) render(() => <ServiceUnavailableForm config={config} />, root);