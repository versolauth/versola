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
    user_agent_ttl_seconds       INT NOT NULL DEFAULT 15552000,
    ip_header                    TEXT NOT NULL,
    acr_vocabulary               JSONB,
    post_logout_redirect_uris    TEXT[] NOT NULL DEFAULT '{}',
    -- Header the tenant's reverse proxy sets with the client certificate it terminated mTLS
    -- for. NULL means the proxy does not terminate mTLS for this tenant.
    mtls_certificate_header      TEXT,
    -- How the certificate in mtls_certificate_header is encoded. Set together with
    -- mtls_certificate_header, which the challenge-settings endpoint enforces: half a source
    -- is not a weaker source but none, since auth can neither read a header it has no
    -- encoding for nor find one an encoding does not name.
    mtls_certificate_encoding    TEXT,
    -- RFC 7523 §3: furthest into the future a client assertion's exp may sit, and so the
    -- window a jti has to be remembered for. Defaults to five minutes, which is what a
    -- client library mints by default and what the replay ring in auth is sized for.
    client_assertion_max_lifetime_seconds INT NOT NULL DEFAULT 300,
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
