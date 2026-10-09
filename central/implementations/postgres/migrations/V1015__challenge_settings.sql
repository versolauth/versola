CREATE TABLE challenge_settings (
    tenant_id                    TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    allowed_prefixes             TEXT[] NOT NULL,
    submission_limits            JSONB NOT NULL,
    otp_length                   INT NOT NULL,
    otp_resend_after             INT NOT NULL,
    passkey_settings             JSONB NOT NULL,
    auth_conversation_ttl_seconds INT NOT NULL,
    session_ttl_seconds          INT NOT NULL,
    session_idle_ttl_seconds     INT,
    user_agent_ttl_seconds       INT NOT NULL,
    ip_header                    TEXT NOT NULL,
    acr_vocabulary               JSONB,
    post_logout_redirect_uris    TEXT[] NOT NULL,
    -- Header the tenant's reverse proxy sets with the client certificate it terminated mTLS
    -- for. NULL means the proxy does not terminate mTLS for this tenant.
    mtls_certificate_header      TEXT,
    -- How the certificate in mtls_certificate_header is encoded. Set together with
    -- mtls_certificate_header, which the challenge-settings endpoint enforces: half a source
    -- is not a weaker source but none, since auth can neither read a header it has no
    -- encoding for nor find one an encoding does not name.
    mtls_certificate_encoding    TEXT,
    -- RFC 9449 §8: whether auth's token and userinfo endpoints demand a server-issued
    -- `DPoP-Nonce` on every proof they check, for clients of this tenant.
    --
    -- Tenant-scoped rather than a per-deployment config value because turning it on breaks every
    -- client that has not implemented the `use_dpop_nonce` retry, so it has to be enablable for
    -- one tenant's clients at a time. Registered off: a nonce costs each client an extra round
    -- trip per endpoint, and unlike a proxied API call a token request is not something a
    -- captured proof buys much against.
    require_dpop_nonce           BOOLEAN NOT NULL,
    -- Which published key this tenant's tokens are signed with. A bare algorithm name could not
    -- address a key: during a rotation two keys share one `alg`, and a stored `alg` can disagree
    -- with the key behind the kid. The algorithm is read from the key's own JWK instead.
    --
    -- NULL keeps the fallback -- auth matches `jwt.private-key` against the synced JWKS.
    --
    -- The reference means a key cannot be deleted out from under a tenant still signing with it.
    signing_key_id               TEXT REFERENCES jwks (kid),
    client_assertion_max_lifetime_seconds INT NOT NULL,
    -- Issue #353: which FAPI profile a tenant's clients are held to. `standard`/`fapi2` today,
    -- stored as text rather than a Postgres enum so a future profile needs no type migration.
    -- Fixed when the tenant is created.
    security_profile             TEXT NOT NULL,
    PRIMARY KEY (tenant_id)
);

-- challenge_settings_change — fired on challenge_settings row changes
CREATE OR REPLACE FUNCTION notify_challenge_settings_change()
RETURNS trigger AS $$
DECLARE
  rec RECORD;
BEGIN
  rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  PERFORM pg_notify(
    'challenge_settings_change',
    json_build_object('tenantId', rec.tenant_id, 'id', rec.tenant_id, 'op', TG_OP)::text
  );
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER challenge_settings_notify
AFTER INSERT OR UPDATE OR DELETE ON challenge_settings
FOR EACH ROW EXECUTE FUNCTION notify_challenge_settings_change();
