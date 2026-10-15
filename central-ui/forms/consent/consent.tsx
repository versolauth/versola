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

// The language's own name ("English", "Русский"); falls back to the bare code where Intl lacks it.
const languageName = (code: string) => {
  try {
    const name = new Intl.DisplayNames([code], { type: 'language' }).of(code) ?? code;
    return name.charAt(0).toLocaleUpperCase(code) + name.slice(1);
  } catch (_) {
    return code;
  }
};

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
        <svg class="locale-globe" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"/>
        </svg>
        {languageName(props.current)}
      </button>
      <Show when={open()}>
        <div class="locale-options">
          {props.locales.map((loc) => (
            <button
              type="button"
              class={`locale-option${loc === props.current ? ' locale-option-active' : ''}`}
              onMouseDown={() => { props.onChange(loc); setOpen(false); }}
            >
              {languageName(loc)}
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
  /** The signed-in user, when the server knows how to name them: shown so they can see whose account is being shared. */
  user?: { name?: string; identifier?: string; picture?: string };
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

// The partner's icon at the top of the card: its logo, or its initial on a tile in the theme colour
// when it has none (or it fails to load). Icon only, never a text logo.
function ClientIcon(props: { logoUri?: string; name?: string }) {
  const [failed, setFailed] = createSignal(false);
  const initial = () => Array.from(props.name?.trim() ?? '')[0]?.toLocaleUpperCase();
  return (
    <Show
      when={props.logoUri && !failed()}
      fallback={<Show when={initial()}>{(letter) => <div class="consent-icon consent-icon-initial" aria-hidden="true">{letter()}</div>}</Show>}
    >
      <div class="consent-icon" aria-hidden="true">
        <img src={props.logoUri} alt="" onError={() => setFailed(true)} />
      </div>
    </Show>
  );
}

function UserBadge(props: { user: NonNullable<ConsentStep['user']>; label: string }) {
  const [failed, setFailed] = createSignal(false);
  const shown = () => props.user.name || props.user.identifier || '';
  const initial = () => Array.from(shown().trim())[0]?.toLocaleUpperCase() ?? '';
  return (
    <div class="consent-user">
      <Show
        when={props.user.picture && !failed()}
        fallback={<div class="consent-avatar" aria-hidden="true">{initial()}</div>}
      >
        <img class="consent-avatar" src={props.user.picture} alt="" referrerpolicy="no-referrer" onError={() => setFailed(true)} />
      </Show>
      <div class="consent-user-text">
        <span class="consent-user-label">{props.label}</span>
        <span class="consent-user-name">{shown()}</span>
      </div>
    </div>
  );
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
  // The translated sentence keeps its `{client}` placeholder; splitting on it lets the name be
  // rendered as an element while the surrounding words stay translatable. Odd indices are the name.
  const descriptionParts = () => {
    const name = step.clientName ?? t().unknown_client ?? 'An application';
    const parts = (t().description ?? '{client} wants to access your account.').split('{client}');
    return parts.flatMap((part, index) => index < parts.length - 1 ? [part, name] : [part]);
  };
  const grantedScope = () => scopes.filter(s => selected().has(s.scope)).map(s => s.scope).join(' ');
  const scopeDescription = (scope: ConsentScope) =>
    scope.descriptionLocalizations?.[currentLocale()] || scope.description || scope.scope;
  // A claim worded like its scope ("Postal address" under "Postal address") says nothing new.
  const claimDescriptions = (scope: ConsentScope) =>
    (scope.claimLocalizations ?? []).map((localizations, index) =>
      localizations[currentLocale()] || scope.claims[index] || '',
    ).filter(claim => claim && claim.trim().toLowerCase() !== scopeDescription(scope).trim().toLowerCase());

  return (
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
      <ClientIcon logoUri={step.logoUri} name={step.clientName} />
      <h1>{t().title}</h1>
      <p class="consent-description">
        {descriptionParts().map((part, index) => index % 2 === 1 ? <strong class="consent-client">{part}</strong> : part)}
      </p>
      <Show when={step.user && (step.user.name || step.user.identifier)}>
        <UserBadge user={step.user!} label={t().signed_in_as ?? 'Signed in as'} />
      </Show>
      <Show when={props.config.error}>
        <div class="error-text" role="alert" style="margin-bottom: 16px;">{t()[props.config.error!] ?? props.config.error}</div>
      </Show>
      <form method="post">
        <input type="hidden" name="csrf" value={props.config.csrf ?? ''} />
        <input type="hidden" name="scope" value={grantedScope()} />
        <div class="consent-scopes-container">
          <ul class="consent-scopes" aria-label={t().scopes_label ?? 'Requested scopes'}>
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
                  <Show when={step.allowPartial && !s.deselectable}>
                    <span class="consent-scope-required">{t().required ?? 'Required'}</span>
                  </Show>
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
        <button type="submit" class="btn btn-secondary btn-quiet">{t().deny_button}</button>
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
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <ConsentForm config={config} />, root);
}
