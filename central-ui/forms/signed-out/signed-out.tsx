import { render } from 'solid-js/web';
import { For, Show, onMount } from 'solid-js';

type SignedOutStep = { type: 'signed-out'; logoutUris: string[]; redirectUri?: string };

interface FormConfig {
  step: SignedOutStep;
  t: Record<string, string>;
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
    <div class="container">
      <For each={props.config.step.logoutUris}>
        {(uri) => <iframe src={uri} style={{ display: 'none' }} sandbox="allow-scripts allow-same-origin" />}
      </For>
      <h1>{t().title}</h1>
      <Show when={redirectUri}>
        {(uri) => (
          <a href={uri()} class="btn btn-primary signed-out-continue">{t().continue_button}</a>
        )}
      </Show>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <SignedOut config={config!} />, root);
}
