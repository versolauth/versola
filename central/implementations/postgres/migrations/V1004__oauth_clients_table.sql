CREATE TABLE oauth_clients (
    id                TEXT NOT NULL PRIMARY KEY,
    tenant_id         TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- Locale-keyed names: {"en": "...", "ru": "..."}.
    client_name       JSONB NOT NULL,
    redirect_uris     TEXT[] NOT NULL,
    scope             TEXT[] NOT NULL,
    secret            BYTEA,
    previous_secret   BYTEA,
    access_token_ttl  BIGINT NOT NULL,
    refresh_token_ttl BIGINT NOT NULL,
    permissions       TEXT[] NOT NULL,
    auth_flow         JSONB,
    otp_template_id   TEXT NOT NULL,
    front_channel_logout_uri TEXT,
    front_channel_logout_session_required BOOLEAN NOT NULL,
    back_channel_logout_uri TEXT,
    -- RFC 8705 §2.1 `tls_client_auth`. NULL means the client does not authenticate with a
    -- certificate.
    mtls_auth JSONB,
    -- RFC 8705 §3.4. Only consulted for clients that authenticate some other way: a client
    -- with mtls_auth set binds regardless, see OAuthClientRecord.bindsAccessTokens.
    certificate_bound_access_tokens BOOLEAN NOT NULL,
    theme TEXT NOT NULL REFERENCES themes(id),
    registration_flow JSONB,
    logo_uri TEXT,
    policy_uri TEXT,
    tos_uri TEXT,
    consent_flow JSONB,
    -- RFC 9449 section 5.2 `dpop_bound_access_tokens`: a client registered with this flag always
    -- uses DPoP, so the token endpoint refuses a token request from it that carries no proof
    -- instead of falling back to a bearer token. A client is registered without it unless a
    -- deployment asks otherwise, so DPoP stays opt-in per request.
    dpop_bound_access_tokens BOOLEAN NOT NULL,
    -- RFC 7523 §2.2 `private_key_jwt`: the JWK Set the client signs its assertions with. NULL
    -- means the client does not use the method. Stored as the document registered rather than
    -- as parsed key material, so JWK members central has no opinion on survive the round trip.
    -- Mutually exclusive with mtls_auth, which registration enforces.
    jwks JSONB,
    -- RFC 9101 §10.5 `require_signed_request_object` and RFC 9126 §6.2
    -- `require_pushed_authorization_requests`: what a client must use to state an authorization
    -- request. Registered off; the requirement only takes effect once an operator turns it on.
    require_signed_request_object BOOLEAN NOT NULL,
    require_pushed_authorization_requests BOOLEAN NOT NULL,
    -- RFC 9449 §5.1 proof key policy, per client: which signing algorithms a proof from it may
    -- use, and how long an RSA proof key's modulus must be.
    --
    -- An empty dpop_signing_algs is "no narrowing" -- the client is held to whatever
    -- dpop_signing_alg_values_supported advertises. A NULL dpop_min_rsa_key_size leaves the
    -- RFC 7518 §3.3 floor of 2048 bits, which auth applies whether or not a client registered
    -- anything; the column can only raise it.
    dpop_signing_algs TEXT[] NOT NULL,
    dpop_min_rsa_key_size INTEGER,
    -- The private key an edge fronting this client signs with: RFC 7523 §2.2 client assertions
    -- at the token endpoint, and RFC 9101 request objects at the authorization endpoint. NULL
    -- means no edge authenticates as this client by key -- it either uses the secret, or is not
    -- fronted.
    --
    -- The public half lives in the jwks column, which is what auth verifies against;
    -- registration requires the two to match, since a key auth holds no counterpart for can only
    -- ever fail.
    --
    -- Encrypted at rest with the same AES key as the secret column (clientSecretsSecret) and
    -- handed to an edge the same way, re-encrypted to that edge's registered RSA public key --
    -- central is the provisioning authority for a fronted client's credential, and a private
    -- key is that credential in the same sense the secret is.
    edge_signing_key BYTEA,
    -- How a client authenticates, as a stored column rather than inferred from the others: a
    -- client that authenticates with a key set and holds no secret is not a public client, and
    -- a secretless row cannot be read as one. The secret of a client that does not authenticate
    -- with one is NULL -- a credential no endpoint accepts is not one to keep encrypted at rest.
    -- Registration states the method; there is no default to guess one from.
    auth_method TEXT NOT NULL,
    -- The certificate and private key an edge fronting this client presents at the TLS
    -- handshake, so that whatever terminates mutual TLS in front of auth recognises the
    -- connection as this client's (RFC 8705 §2). NULL means no edge authenticates as this
    -- client by certificate -- it either uses the secret or the edge signing key, or is not
    -- fronted.
    --
    -- What the certificate is compared against lives in the mtls_auth column: §2.1 matches the
    -- registered subject value against the certificate, §2.2 matches the key inside it against
    -- jwks. Registration requires that column to be set, since no certificate is ever read for a
    -- client that registered neither.
    --
    -- Stored as the registered PEM text, both halves together, encrypted at rest with the same
    -- AES key as the secret column (clientSecretsSecret) and handed to an edge the same way,
    -- re-encrypted to that edge's registered RSA public key -- the mutual-TLS counterpart of
    -- edge_signing_key.
    edge_client_certificate BYTEA,
    -- What a client was registered from, and when. The console's registration wizard picks a
    -- combination -- what the client is, and how much assurance its deployment can carry -- and
    -- applies the settings that combination implies; keeping the choice lets the edit screen
    -- say "this differs from what you asked for". NULL for a client registered through the API
    -- rather than the wizard: a guess would be indistinguishable from a recorded choice.
    template JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    -- OIDC Dynamic Client Registration §2 `application_type`: what kind of program a client is,
    -- held apart from how it authenticates (`auth_method`). The two cannot be read off each
    -- other: an app fronted by edge (#421) is native (App Link redirect, DPoP key on the device)
    -- and confidential at once, since edge authenticates as it with `tls_client_auth`.
    --
    -- No CHECK against the known values, like every other enum-shaped text column here: a value
    -- outside the set fails at the application decoder on read.
    application_type TEXT NOT NULL
);
