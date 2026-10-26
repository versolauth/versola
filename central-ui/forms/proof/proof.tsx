import { render } from 'solid-js/web';
import { createSignal, onCleanup, onMount, Show } from 'solid-js';
import { PageBrand, ClientBadge } from '../shared/brand';
import { LocaleSelector, createTranslations } from '../shared/locale';
import type { BaseFormConfig } from '../shared/config';
import { solve } from './pow';

type ProofStep = { type: 'proof'; kind: string; token: string; params: Record<string, string> };

interface FormConfig extends BaseFormConfig {
  step: ProofStep;
  error?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

/**
 * Asked before a code is sent to a request the server does not trust. The browser does the work (`kind` `pow`: a hash
 * with enough leading zero bits) and sends the solution by itself; the person sees a moment of progress.
 */
function ProofForm(props: { config: FormConfig }) {
  const { t, locale: currentLocale, changeLocale } = createTranslations(props.config);
  const [solution, setSolution] = createSignal('');
  const [unsupported, setUnsupported] = createSignal(false);
  let formRef!: HTMLFormElement;
  let cancelled = false;
  onCleanup(() => { cancelled = true; });

  onMount(async () => {
    const { kind, params } = props.config.step;
    // The console's preview must not navigate away.
    if (props.config.previewId || props.config.preview) return;
    if (kind !== 'pow') { setUnsupported(true); return; }
    const nonce = await solve(params.seed, Number(params.difficulty), () => cancelled);
    if (nonce === null) return;
    setSolution(nonce);
    formRef.requestSubmit();
  });

  return (
    <>
      <PageBrand logo={props.config.logo} />
      <div class="container">
        <LocaleSelector config={props.config} current={currentLocale()} onChange={changeLocale} />
        <ClientBadge client={props.config.client} locale={currentLocale()} defaultLocale={props.config.defaultLocale} />
        <h1>{t().title}</h1>
        <p class="proof-description">{t().description}</p>
        <Show when={props.config.error}>
          <div class="error-text error-text-block" role="alert">{t()[props.config.error!] ?? props.config.error}</div>
        </Show>
        <Show when={unsupported()}>
          <div class="error-text error-text-block" role="alert">{t().unsupported}</div>
        </Show>
        <div class="proof-progress" role="status" aria-live="polite">
          <span class="proof-spinner" aria-hidden="true" />
          <span>{t().working}</span>
        </div>
        <form ref={formRef} method="post" action={`/challenge/proof?ui_locale=${currentLocale()}`}>
          <input type="hidden" name="csrf" value={props.config.csrf ?? ''} />
          <input type="hidden" name="token" value={props.config.step.token} />
          <input type="hidden" name="solution" value={solution()} />
        </form>
      </div>
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <ProofForm config={config} />, root);
}
