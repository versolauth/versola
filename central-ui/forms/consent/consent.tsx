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

type ConsentScope = {
  scope: string;
  description?: string;
  descriptionLocalizations?: Record<string, string>;
  claims: string[];
  claimLocalizations?: Record<string, string>[];
  deselectable: boolean;
};

type ConsentStep = {
  type: 'consent';
  clientName?: string;
  logoUri?: string;
  policyUri?: string;
  tosUri?: string;
  scopes: ConsentScope[];
  allowPartial: boolean;
  denyUri: string;
};

interface FormConfig {
  step: ConsentStep;
  t: Record<string, string>;
  locale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  error?: string;
  previewId?: string;
  csrf?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function ConsentForm(props: { config: FormConfig }) {
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
  const scopes = step.scopes ?? [];

  // Non-deselectable scopes are always submitted; the rest start selected and the user may
  // opt out of them when the client allows a partial grant.
  const [selected, setSelected] = createSignal(new Set(scopes.map(s => s.scope)));
  const toggle = (scope: string) => {
    const next = new Set(selected());
    if (next.has(scope)) next.delete(scope); else next.add(scope);
    setSelected(next);
  };
  const grantedScope = () => scopes.filter(s => selected().has(s.scope)).map(s => s.scope).join(' ');
  const scopeDescription = (scope: ConsentScope) =>
    scope.descriptionLocalizations?.[currentLocale()] || scope.description || scope.scope;
  const claimDescriptions = (scope: ConsentScope) =>
    (scope.claimLocalizations ?? []).map((localizations, index) =>
      localizations[currentLocale()] || scope.claims[index] || '',
    ).filter(Boolean);

  return (
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
      <Show when={step.logoUri}>
        {uri => <img class="consent-logo" src={uri()} alt="" />}
      </Show>
      <h1>{t().title}</h1>
      <p class="consent-description">
        {(t().description ?? '{client} wants to access your account.').replace('{client}', step.clientName ?? t().unknown_client ?? 'An application')}
      </p>
      <Show when={props.config.error}>
        <div class="error-text" style="margin-bottom: 8px;">{t()[props.config.error!] ?? props.config.error}</div>
      </Show>
      <form method="post">
        <input type="hidden" name="csrf" value={props.config.csrf ?? ''} />
        <input type="hidden" name="scope" value={grantedScope()} />
        <div
          class="consent-scopes-container"
          role="region"
          aria-label={t().scopes_label ?? 'Requested scopes'}
          tabindex="0"
        >
          <ul class="consent-scopes">
            {scopes.map(s => (
              <li class="consent-scope">
                <label class="consent-scope-label">
                  <input
                    type="checkbox"
                    checked={selected().has(s.scope)}
                    disabled={!(step.allowPartial && s.deselectable)}
                    onChange={() => toggle(s.scope)}
                  />
                  <span>
                    <span class="consent-scope-name">{scopeDescription(s)}</span>
                    <Show when={claimDescriptions(s).length > 0}>
                      <span class="consent-scope-claims">{claimDescriptions(s).join(', ')}</span>
                    </Show>
                  </span>
                </label>
              </li>
            ))}
          </ul>
        </div>
        <button type="submit" formAction={`/challenge/consent?ui_locale=${currentLocale()}`} class="btn btn-primary">
          {t().allow_button}
        </button>
      </form>
      <form method="post" action={`/challenge/consent/deny?ui_locale=${currentLocale()}`}>
        <input type="hidden" name="csrf" value={props.config.csrf ?? ''} />
        <button type="submit" class="btn btn-secondary">{t().deny_button}</button>
      </form>
      <Show when={step.policyUri || step.tosUri}>
        <p class="consent-legal">
          <Show when={step.policyUri}>
            {uri => <a href={uri()} target="_blank" rel="noopener noreferrer">{t().policy_link}</a>}
          </Show>
          <Show when={step.policyUri && step.tosUri}><span> · </span></Show>
          <Show when={step.tosUri}>
            {uri => <a href={uri()} target="_blank" rel="noopener noreferrer">{t().tos_link}</a>}
          </Show>
        </p>
      </Show>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <ConsentForm config={config} />, root);
}
