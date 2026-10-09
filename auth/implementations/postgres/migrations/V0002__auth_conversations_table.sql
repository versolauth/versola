CREATE TABLE auth_conversations (
    id UUID NOT NULL PRIMARY KEY,
    client_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    -- A confidential client of a `standard` tenant may omit PKCE, so a conversation and the
    -- authorization code it ends in can carry no challenge. The columns stay a pair: a row has
    -- either both or neither, which the CHECK below holds so a half-written record cannot be
    -- stored.
    code_challenge TEXT,
    code_challenge_method TEXT,
    state TEXT,
    user_id UUID,
    credential TEXT,
    step JSON NOT NULL,
    requested_claims JSON,
    ui_locales TEXT[],
    nonce TEXT,
    response_type TEXT NOT NULL,
    user_email TEXT,
    user_phone TEXT,
    user_login TEXT,
    user_claims JSON,
    auth_flow JSONB NOT NULL,
    user_agent TEXT,
    user_agent_cookie JSONB,
    version BIGINT NOT NULL,
    amr JSONB NOT NULL,
    needs_password_change BOOLEAN NOT NULL,
    csrf_token TEXT NOT NULL,
    target_acr TEXT,
    prior_session_id BYTEA,
    resources TEXT[] NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Registration state for a conversation started through the register button.
    -- registration_flow is a snapshot of the client's flow taken when the conversation is
    -- created, so a configuration change mid-conversation cannot alter the steps in flight.
    -- registration_step is the index of the pending step and is null while the user is signing in.
    registration_flow JSONB,
    registration_step INT,
    -- RFC 9396 `authorization_details`: the granted detail objects are stored verbatim so they
    -- can be echoed back unchanged in the token response and compared against a later refresh
    -- request (RFC 9396 section 6.1). Nullable, with no default: NULL means no
    -- `authorization_details` was requested, distinct from an empty array.
    authorization_details JSONB[],
    -- The scope actually granted on the consent screen, which may be a subset of the requested
    -- `scope` when the client allows partial grants. NULL until consent has been resolved.
    granted_scope TEXT[],
    -- OIDC `prompt=consent` must re-prompt even when a matching grant is already on file, and the
    -- decision is taken long after `/authorize` has returned, so the request's intent is persisted.
    prompt_consent BOOLEAN NOT NULL DEFAULT FALSE,
    -- RFC 9449 section 10 `dpop_jkt`: the key thumbprint an authorization request commits its code
    -- to, checked against the proof presented at redemption. Carried on the conversation because
    -- the code is issued long after `/authorize` accepted the parameter. Nullable: a request that
    -- names no key leaves redemption unconstrained.
    dpop_jkt TEXT,
    -- JARM `response_mode`: how the authorization response a conversation ends in is returned to
    -- the client, and whether it is signed. Carried on the conversation because the response is
    -- built long after `/authorize` accepted the parameter.
    response_mode TEXT NOT NULL,
    -- The user a conversation's prior session belonged to, known at /authorize where that session is
    -- looked up. At completion it decides whether the prior session's refresh tokens may move to the
    -- new session (the same user signed in again) or must be expired (another user did), without
    -- fetching the session a second time. NULL for a conversation with no prior session.
    prior_session_user_id UUID,
    CONSTRAINT auth_conversations_pkce_pair
        CHECK ((code_challenge IS NULL) = (code_challenge_method IS NULL))
);

CREATE INDEX auth_conversations_credential_idx
    ON auth_conversations (credential)
    WHERE credential IS NOT NULL;

CREATE INDEX auth_conversations_expires_at_idx
    ON auth_conversations (expires_at);