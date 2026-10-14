import { render } from 'solid-js/web';
import { createMemo, createSignal, For, Show } from 'solid-js';
import { getRegistrationResponse, isPasskeyCancellation, passkeysSupported } from '../passkey/webauthn';

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

type AccountSession = {
  id: string;
  platform?: string;
  os?: string;
  browser?: string;
  version?: string;
  createdAt: string;
  expiresAt: string;
  current: boolean;
};

type AccountPasskey = {
  id: string;
  name?: string;
  backedUp: boolean;
  lastUsedAt?: string;
  createdAt: string;
};

type DeletionRequest =
  | { kind: 'session'; session: AccountSession }
  | { kind: 'passkey'; id: string };

type AccountSettingsStep = {
  type: 'auth-settings';
  sessions: AccountSession[];
  passkeys: AccountPasskey[];
};

interface FormConfig {
  step: AccountSettingsStep;
  t: Record<string, string>;
  locale?: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  error?: string;
  previewId?: string;
  csrf?: string;
  logo?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

// The page is reached through the edge proxy at `/resources/{resourceId}/settings`.
// Edge authenticates the EDGE_SESSION cookie and forwards the caller context upstream,
// so a same-origin fetch() below needs no token handling of its own - only the trailing
// path has to line up with the routes `AccountSettingsController` registers under `/settings`.
function apiBase(): string {
  const path = window.location.pathname;
  return path.endsWith('/') ? path : `${path}/`;
}

function formatDate(value: string, locale: string): string {
  try {
    return new Date(value).toLocaleString(locale);
  } catch (_) {
    return value;
  }
}

function AccountSettingsForm(props: { config: FormConfig }) {
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
  const base = apiBase();

  const [sessions, setSessions] = createSignal(step.sessions ?? []);
  // The current session (the one the page is being viewed from) is always shown
  // first, regardless of the order the backend returns sessions in.
  const sortedSessions = createMemo(() => [...sessions()].sort((a, b) => Number(b.current) - Number(a.current)));
  const [passkeys, setPasskeys] = createSignal(step.passkeys ?? []);
  const [busySessionId, setBusySessionId] = createSignal<string | null>(null);
  const [busyPasskeyId, setBusyPasskeyId] = createSignal<string | null>(null);
  const [renamingId, setRenamingId] = createSignal<string | null>(null);
  const [renameValue, setRenameValue] = createSignal('');
  const [enrollName, setEnrollName] = createSignal('');
  const [enrolling, setEnrolling] = createSignal(false);
  const [enrollNameError, setEnrollNameError] = createSignal(false);
  const [enrollError, setEnrollError] = createSignal(false);
  const [deletionRequest, setDeletionRequest] = createSignal<DeletionRequest | null>(null);

  const revokeSession = async (session: AccountSession) => {
    if (busySessionId()) return;
    if (session.current) return;
    setBusySessionId(session.id);
    try {
      const res = await fetch(`${base}sessions`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetSessionId: session.id }),
      });
      if (res.ok) {
        setSessions(sessions().filter((s) => s.id !== session.id));
      }
    } finally {
      setBusySessionId(null);
    }
  };

  const requestSessionDeletion = (session: AccountSession) => {
    if (busySessionId() || session.current) return;
    setDeletionRequest({ kind: 'session', session });
  };

  const startRename = (passkey: AccountPasskey) => {
    setRenamingId(passkey.id);
    setRenameValue(passkey.name ?? '');
  };

  const submitRename = async (id: string) => {
    if (busyPasskeyId()) return;
    setBusyPasskeyId(id);
    try {
      const trimmed = renameValue().trim();
      const res = await fetch(`${base}passkeys`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ credentialId: id, name: trimmed || null }),
      });
      if (res.ok) {
        setPasskeys(passkeys().map((p) => (p.id === id ? { ...p, name: trimmed || undefined } : p)));
        setRenamingId(null);
      }
    } finally {
      setBusyPasskeyId(null);
    }
  };

  const deletePasskey = async (id: string) => {
    if (busyPasskeyId()) return;
    setBusyPasskeyId(id);
    try {
      const res = await fetch(`${base}passkeys`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ credentialId: id }),
      });
      if (res.ok) setPasskeys(passkeys().filter((p) => p.id !== id));
    } finally {
      setBusyPasskeyId(null);
    }
  };

  const requestPasskeyDeletion = (id: string) => {
    if (busyPasskeyId()) return;
    setDeletionRequest({ kind: 'passkey', id });
  };

  const confirmDeletion = async () => {
    const request = deletionRequest();
    if (!request) return;
    setDeletionRequest(null);
    if (request.kind === 'session') await revokeSession(request.session);
    else await deletePasskey(request.id);
  };

  const enroll = async () => {
    if (enrolling()) return;
    const trimmed = enrollName().trim();
    if (!trimmed) {
      setEnrollNameError(true);
      return;
    }
    setEnrolling(true);
    setEnrollNameError(false);
    setEnrollError(false);
    try {
      // The body is empty, but it must be JSON: edge merges the caller context into it.
      const startRes = await fetch(`${base}passkeys/register/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      if (!startRes.ok) throw new Error('start failed');
      const { publicKeyOptions, ticket } = await startRes.json();
      const response = JSON.parse(await getRegistrationResponse(publicKeyOptions));
      const finishRes = await fetch(`${base}passkeys/register/finish`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ticket, response, name: trimmed }),
      });
      if (!finishRes.ok) throw new Error('finish failed');
      const passkey = (await finishRes.json()) as AccountPasskey;
      setPasskeys([...passkeys(), passkey]);
      setEnrollName('');
    } catch (error) {
      if (!isPasskeyCancellation(error)) setEnrollError(true);
    } finally {
      setEnrolling(false);
    }
  };

  return (
    <div class="container account-container">
      <Show when={locales.length > 1}>
        <div class="locale-selector">
          <LocaleDropdown locales={locales} current={currentLocale()} onChange={changeLocale} />
        </div>
      </Show>
      <h1>{t().page_title}</h1>

      <section class="account-section">
        <h2>{t().sessions_title}</h2>
        <Show when={sessions().length === 0}>
          <p class="account-empty">{t().no_sessions}</p>
        </Show>
        <ul class="account-list">
          <For each={sortedSessions()}>
            {(session) => (
              <li class="account-row">
                <div class="account-row-info">
                  <span class="account-row-title">
                    {[session.browser, session.os].filter(Boolean).join(' · ') || t().unknown_device}
                    <Show when={session.current}>
                      <span class="account-badge">{t().current_session_badge}</span>
                    </Show>
                  </span>
                  <span class="account-row-subtitle">
                    {formatDate(session.createdAt, currentLocale())}
                    {' ('}
                    {(t().expires_label ?? 'Expires {date}').replace('{date}', formatDate(session.expiresAt, currentLocale()))}
                    {')'}
                  </span>
                </div>
                <Show when={!session.current}>
                  <button
                    type="button"
                    class="btn btn-secondary account-row-action"
                    disabled={busySessionId() === session.id}
                    onClick={() => requestSessionDeletion(session)}
                  >
                    {t().revoke_button}
                  </button>
                </Show>
              </li>
            )}
          </For>
        </ul>
      </section>

      <section class="account-section">
        <h2>{t().passkeys_title}</h2>
        <Show when={passkeys().length === 0}>
          <p class="account-empty">{t().no_passkeys}</p>
        </Show>
        <ul class="account-list">
          <For each={passkeys()}>
            {(passkey) => (
              <li class="account-row">
                <Show
                  when={renamingId() === passkey.id}
                  fallback={
                    <>
                      <div class="account-row-info">
                        <span class="account-row-title">{passkey.name || t().unnamed_passkey}</span>
                        <span class="account-row-subtitle">{formatDate(passkey.createdAt, currentLocale())}</span>
                      </div>
                      <div class="account-row-actions">
                        <button type="button" class="btn btn-secondary account-row-action" onClick={() => startRename(passkey)}>
                          {t().rename_button}
                        </button>
                        <button
                          type="button"
                          class="btn btn-secondary account-row-action"
                          disabled={busyPasskeyId() === passkey.id}
                          onClick={() => requestPasskeyDeletion(passkey.id)}
                        >
                          {t().delete_button}
                        </button>
                      </div>
                    </>
                  }
                >
                  <div class="account-row-info account-rename">
                    <input
                      class="input-field"
                      type="text"
                      value={renameValue()}
                      onInput={(e) => setRenameValue(e.currentTarget.value)}
                      autofocus
                    />
                  </div>
                  <div class="account-row-actions">
                    <button
                      type="button"
                      class="btn btn-primary account-row-action"
                      disabled={busyPasskeyId() === passkey.id}
                      onClick={() => submitRename(passkey.id)}
                    >
                      {t().save_button}
                    </button>
                    <button type="button" class="btn btn-secondary account-row-action" onClick={() => setRenamingId(null)}>
                      {t().cancel_button}
                    </button>
                  </div>
                </Show>
              </li>
            )}
          </For>
        </ul>

        <Show when={passkeysSupported()}>
          <div class="account-enroll">
            <input
              class={`input-field${enrollNameError() ? ' input-error' : ''}`}
              type="text"
              placeholder={t().name_placeholder}
              value={enrollName()}
              onInput={(e) => { setEnrollName(e.currentTarget.value); setEnrollNameError(false); }}
              disabled={enrolling()}
              autocomplete="off"
              required
            />
            <Show when={enrollNameError()}>
              <div class="error-text account-enroll-error">{t().name_required}</div>
            </Show>
            <Show when={enrollError()}>
              <div class="error-text account-enroll-error">{t().enroll_failed}</div>
            </Show>
            <button type="button" class="btn btn-primary" disabled={enrolling()} onClick={enroll}>
              {t().add_passkey_button}
            </button>
          </div>
        </Show>
      </section>

      <button type="button" class="btn btn-secondary account-go-back" onClick={() => history.back()}>
        {t().go_back_button}
      </button>

      <Show when={deletionRequest()}>
        {(request) => (
          <div
            class="confirmation-backdrop"
            role="presentation"
            onClick={(event) => { if (event.target === event.currentTarget) setDeletionRequest(null); }}
          >
            <div class="confirmation-dialog" role="dialog" aria-modal="true" aria-labelledby="confirmation-title">
              <h2 id="confirmation-title">{t().confirm_button}</h2>
              <p>{request().kind === 'session' ? t().delete_session_confirm : t().delete_passkey_confirm}</p>
              <div class="confirmation-actions">
                <button type="button" class="btn btn-secondary" onClick={() => setDeletionRequest(null)}>
                  {t().cancel_button}
                </button>
                <button
                  type="button"
                  class="btn btn-primary"
                  disabled={request().kind === 'session'
                    ? busySessionId() === request().session.id
                    : busyPasskeyId() === request().id}
                  onClick={confirmDeletion}
                >
                  {t().confirm_button}
                </button>
              </div>
            </div>
          </div>
        )}
      </Show>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <AccountSettingsForm config={config} />, root);
}
