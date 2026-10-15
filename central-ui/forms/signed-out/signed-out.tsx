import { render } from 'solid-js/web';
import { For, Show, onMount, createSignal } from 'solid-js';

// The brand zone above the card. The client's logo sits on a light tile so any logo reads on the
// brand-coloured page; with no logo (or one that fails to load) it is the Versola wordmark, or the
// client's initial when the caller asks for one (the consent screen shows only the partner's icon).
function PageBrand(props: { logo?: string; initial?: string; icon?: boolean }) {
  const [failed, setFailed] = createSignal(false);
  const letter = () => Array.from(props.initial?.trim() ?? '')[0]?.toLocaleUpperCase();
  return (
    <div class="page-brand" aria-hidden="true">
      <Show
        when={props.logo && !failed()}
        fallback={
          <Show
            when={props.initial !== undefined}
            fallback={
              <svg class="page-wordmark" viewBox="-2 0 286 56" height="28" fill="none" stroke="currentColor" stroke-width="8" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="Versola">
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
            <Show when={letter()}>{(l) => <span class="page-brand-tile page-brand-initial">{l()}</span>}</Show>
          </Show>
        }
      >
        <span class={`page-brand-tile${props.icon ? ' page-brand-icon' : ''}`}>
          <img src={props.logo} alt="" style="height:40px" onError={() => setFailed(true)} />
        </span>
      </Show>
    </div>
  );
}

type SignedOutStep = { type: 'signed-out'; logoutUris: string[]; redirectUri?: string };

interface FormConfig {
  step: SignedOutStep;
  t: Record<string, string>;
  logo?: string;
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
