import { render } from 'solid-js/web';
import { For, Show, onMount } from 'solid-js';
import { PageBrand } from '../shared/brand';
import type { BaseFormConfig } from '../shared/config';

type SignedOutStep = { type: 'signed-out'; logoutUris: string[]; redirectUri?: string };

interface FormConfig extends BaseFormConfig {
  step: SignedOutStep;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

/** Grace period letting front-channel logout iframes fire before auto-continuing. */
const REDIRECT_DELAY_MS = 2000;

function SignedOut(props: { config: FormConfig }) {
  const t = () => props.config.t;
  const redirectUri = props.config.step.redirectUri;

  onMount(() => {
    if (redirectUri) {
      setTimeout(() => { window.location.href = redirectUri; }, REDIRECT_DELAY_MS);
    }
  });

  return (
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <For each={props.config.step.logoutUris}>
        {(uri) => <iframe src={uri} style={{ display: 'none' }} sandbox="allow-scripts allow-same-origin" />}
      </For>
      <div class="signed-out-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10" />
          <path d="M7.5 12.5l3 3 6-6.5" />
        </svg>
      </div>
      <h1>{t().title}</h1>
      <Show when={redirectUri}>
        {(uri) => (
          <a href={uri()} class="btn btn-primary signed-out-continue">{t().continue_button}</a>
        )}
      </Show>
    </div>
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <SignedOut config={config!} />, root);
}
