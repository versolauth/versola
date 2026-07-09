import { render } from 'solid-js/web';
import { createSignal, Show, For } from 'solid-js';
import { getRegistrationResponse, submitViaForm } from '../passkey/webauthn';

interface SessionView {
  id: string;
  clientId: string;
  platform: string;
  os?: string;
  browser?: string;
  version?: string;
  createdAt: string;
}

interface PasskeyView {
  id: string;
  name?: string;
  deviceType: string;
  backedUp: boolean;
  backupEligible: boolean;
  createdAt: string;
  lastUsedAt?: string;
}

interface FormConfig {
  sessions: SessionView[];
  passkeys: PasskeyView[];
  passkeyRegistration?: string;
  t: Record<string, string>;
  locale: string;
  locales?: string[];
  allT?: Record<string, Record<string, string>>;
  error?: string;
}

declare global {
  interface Window {
    __VERSOLA_FORM__?: FormConfig;
  }
}

function sessionLabel(session: SessionView, t: Record<string, string>): string {
  const platform = t[`platform_${session.platform}`] ?? session.platform;
  const parts: string[] = [platform];
  if (session.browser) parts.push(session.browser + (session.version ? ` ${session.version}` : ''));
  if (session.os) parts.push(session.os);
  return parts.join(' · ');
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
  } catch {
    return iso;
  }
}

function AuthSettingsForm(props: { config: FormConfig }) {
  const allT = props.config.allT ?? {};
  const baseT = props.config.t;
  const t = () => {
    const loc = props.config.locale;
    return { ...baseT, ...(allT[loc] ?? {}) };
  };

  // Passkey registration state
  const [enrolling, setEnrolling] = createSignal(false);
  const [passkeyName, setPasskeyName] = createSignal('');
  const [enrollError, setEnrollError] = createSignal(false);

  const handleRegisterPasskey = async () => {
    const options = props.config.passkeyRegistration;
    if (!options || enrolling()) return;
    setEnrolling(true);
    setEnrollError(false);
    try {
      const response = await getRegistrationResponse(options);
      const fields: Record<string, string> = { response };
      const name = passkeyName().trim();
      if (name) fields['name'] = name;
      submitViaForm('/auth-settings/passkeys/register', fields);
    } catch {
      setEnrollError(true);
      setEnrolling(false);
    }
  };

  const handleLogout = (sessionId: string) => {
    submitViaForm('/auth-settings/sessions/logout', { id: sessionId });
  };

  const handleDeletePasskey = (credentialId: string) => {
    submitViaForm('/auth-settings/passkeys/delete', { id: credentialId });
  };

  return (
    <div class="container">
      {/* Sessions section */}
      <div class="account-section">
        <h2>{t().sessions_title}</h2>
        <Show when={props.config.sessions.length > 0} fallback={<p class="account-empty">{t().sessions_empty}</p>}>
          <ul class="account-list">
            <For each={props.config.sessions}>
              {(session) => (
                <li class="account-list-item">
                  <div class="account-list-item-info">
                    <span class="account-list-item-primary">{sessionLabel(session, t())}</span>
                    <span class="account-list-item-secondary">
                      {session.clientId} · {formatDate(session.createdAt)}
                    </span>
                  </div>
                  <button
                    type="button"
                    class="btn btn-danger"
                    onClick={() => handleLogout(session.id)}
                  >
                    {t().session_logout}
                  </button>
                </li>
              )}
            </For>
          </ul>
        </Show>
      </div>

      {/* Passkeys section */}
      <div class="account-section">
        <h2>{t().passkeys_title}</h2>
        <Show when={props.config.passkeys.length > 0} fallback={<p class="account-empty">{t().passkeys_empty}</p>}>
          <ul class="account-list">
            <For each={props.config.passkeys}>
              {(passkey) => (
                <li class="account-list-item">
                  <div class="account-list-item-info">
                    <span class="account-list-item-primary">{passkey.name ?? passkey.deviceType}</span>
                    <span class="account-list-item-secondary">
                      {formatDate(passkey.createdAt)}
                      {passkey.lastUsedAt && ` · ${t().passkey_last_used} ${formatDate(passkey.lastUsedAt)}`}
                    </span>
                  </div>
                  <button
                    type="button"
                    class="btn btn-danger"
                    onClick={() => handleDeletePasskey(passkey.id)}
                  >
                    {t().passkey_delete}
                  </button>
                </li>
              )}
            </For>
          </ul>
        </Show>

        {/* Register new passkey */}
        <Show when={!!props.config.passkeyRegistration}>
          <div class="passkey-add-form">
            <Show when={enrollError()}>
              <p class="passkey-error-message">{t().passkey_error}</p>
            </Show>
            <div class="passkey-add-row">
              <input
                type="text"
                class="passkey-name-input"
                placeholder={t().passkey_name_placeholder}
                value={passkeyName()}
                onInput={(e) => setPasskeyName(e.currentTarget.value)}
                disabled={enrolling()}
              />
              <button
                type="button"
                class="btn btn-primary"
                disabled={enrolling()}
                onClick={handleRegisterPasskey}
              >
                {t().passkey_add}
              </button>
            </div>
          </div>
        </Show>
      </div>
    </div>
  );
}

const config = window.__VERSOLA_FORM__;
const root = document.getElementById('versola-form-root');
if (config && root) {
  render(() => <AuthSettingsForm config={config!} />, root);
}
