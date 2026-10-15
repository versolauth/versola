import { render } from 'solid-js/web';
import { createSignal, Show } from 'solid-js';
import { getRegistrationResponse, submitViaForm } from '../passkey/webauthn';

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

// The partner's app the user is signing in to, inside the card: its icon and name. Only for a partner's app (one with a
// consent flow); the identity provider's own apps are not announced.
function ClientBadge(props: { client?: { name?: string; names?: Record<string, string>; logo?: string; partner: boolean } }) {
  const [failed, setFailed] = createSignal(false);
  const initial = () => Array.from(props.client?.name?.trim() ?? '')[0]?.toLocaleUpperCase();
  return (
    <Show when={props.client?.partner && (props.client.name || (props.client.logo && !failed()))}>
      <div class="client-badge">
        <Show
          when={props.client?.logo && !failed()}
          fallback={<Show when={initial()}>{(letter) => <span class="client-badge-icon client-badge-initial" aria-hidden="true">{letter()}</span>}</Show>}
        >
          <span class="client-badge-icon" aria-hidden="true">
            <img src={props.client?.logo} alt="" referrerpolicy="no-referrer" style="height:32px;width:32px;object-fit:contain" onError={() => setFailed(true)} />
          </span>
        </Show>
        <Show when={props.client?.name}>{(name) => <span class="client-badge-name">{name()}</span>}</Show>
      </div>
    </Show>
  );
}


// The language's own name ("English", "Русский"); falls back to the bare code where Intl lacks it.
const languageName = (code: string, names?: Record<string, string>) => {
  if (names?.[code]) return names[code];
  try {
    const name = new Intl.DisplayNames([code], { type: 'language' }).of(code) ?? code;
    return name.charAt(0).toLocaleUpperCase(code) + name.slice(1);
  } catch (_) {
    return code;
  }
};

function LocaleDropdown(props: { locales: string[]; current: string; names?: Record<string, string>; onChange: (l: string) => void }) {
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
        {languageName(props.current, props.names)}
      </button>
      <Show when={open()}>
        <div class="locale-options">
          {props.locales.map((loc) => (
            <button
              type="button"
              class={`locale-option${loc === props.current ? ' locale-option-active' : ''}`}
              onMouseDown={() => { props.onChange(loc); setOpen(false); }}
            >
              {languageName(loc, props.names)}
            </button>
          ))}
        </div>
      </Show>
    </div>
  );
}

type PasskeyEnrollStep = { type: 'passkey-enroll'; publicKeyOptions: string };

interface FormConfig {
  step: PasskeyEnrollStep;
  t: Record<string, string>;
  locale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  error?: string;
  previewId?: string;
  csrf?: string;
  logo?: string;
  client?: { name?: string; names?: Record<string, string>; logo?: string; partner: boolean };
  localeNames?: Record<string, string>;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function PasskeyEnrollForm(props: { config: FormConfig }) {
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

  const [busy, setBusy] = createSignal(false);
  const [enrollError, setEnrollError] = createSignal(!!props.config.error);
  const [name, setName] = createSignal('');
  const [nameError, setNameError] = createSignal(false);

  const NAME_REGEX = /^[\p{L}\p{N} ()-]+$/u;

  const handleEnroll = async () => {
    if (busy()) return;
    const currentName = name();
    if (!NAME_REGEX.test(currentName) || currentName !== currentName.trim()) {
      setNameError(true);
      setEnrollError(false);
      return;
    }
    setNameError(false);
    setBusy(true);
    setEnrollError(false);
    try {
      const response = await getRegistrationResponse(step.publicKeyOptions);
      submitViaForm(`/challenge/passkey/enroll?ui_locale=${currentLocale()}`, { response, name: currentName, csrf: props.config.csrf ?? '' });
    } catch (_) {
      setEnrollError(true);
      setBusy(false);
    }
  };

  const handleSkip = () => {
    if (busy()) return;
    setBusy(true);
    submitViaForm(`/challenge/passkey/skip?ui_locale=${currentLocale()}`, { csrf: props.config.csrf ?? '' });
  };

  return (
    <>
      <PageBrand logo={props.config.logo} />
    <div class="container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} names={props.config.localeNames} onChange={changeLocale} />
        </div>
      </Show>
      <ClientBadge client={props.config.client} />
      <div class="passkey-enroll-icon" aria-hidden="true">
        <span class="passkey-icon" />
      </div>
      <h1>{t().title}</h1>
      <p class="passkey-enroll-description">{t().description}</p>

      <div class="name-field field">
        <label class="field-label" for="passkey-name">{t().name_placeholder}</label>
        <input
          id="passkey-name"
          class={`input-field${nameError() ? ' input-error' : ''}`}
          type="text"
          value={name()}
          onInput={(e) => { setName(e.currentTarget.value); setNameError(false); }}
          disabled={busy()}
          placeholder=" "
          autocomplete="off"
          aria-invalid={nameError() ? 'true' : undefined}
          aria-describedby={nameError() ? 'passkey-name-error' : undefined}
        />
        <Show when={nameError()}>
          <div id="passkey-name-error" class="field-error error-text" role="alert">{t().name_invalid}</div>
        </Show>
      </div>

      <Show when={enrollError()}>
        <div class="phone-error-message error-text" role="alert">{t().enroll_failed}</div>
      </Show>

      <button type="button" class="btn btn-primary" disabled={busy()} onClick={handleEnroll}>
        {t().enroll_button}
      </button>
      <button type="button" class="btn btn-secondary" disabled={busy()} onClick={handleSkip}>
        {t().skip_button}
      </button>
    </div>
    </>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <PasskeyEnrollForm config={config!} />, root);
}
