CREATE TABLE challenge_settings (
    tenant_id                    TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    allowed_prefixes             TEXT[] NOT NULL,
    password_regex               TEXT,
    submission_limits            JSONB NOT NULL,
    otp_length                   INT NOT NULL,
    otp_resend_after             INT NOT NULL,
    passkey_settings             JSONB NOT NULL,
    password_history_size        INT NOT NULL,
    password_num_different       INT NOT NULL,
    auth_conversation_ttl_seconds INT NOT NULL,
    session_ttl_seconds          INT NOT NULL,
    session_idle_ttl_seconds     INT,
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
