-- Tokens (and whole sessions) that must stop being accepted before their `exp`.
-- One key column rather than a composite key, so the generic cleanup manager can
-- batch on it: 'jti:<access token id>' kills one token, 'sid:<session id>' kills
-- every token issued under that SSO session, including ones this edge never saw.
CREATE TABLE revocations (
    revoked_key TEXT PRIMARY KEY,
    revoked_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX revocations_expires_at_idx ON revocations (expires_at);

-- Only INSERT notifies: deletes are cleanup of entries that are already past
-- expires_at, and every replica drops those on its own.
CREATE OR REPLACE FUNCTION notify_revocation()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify(
    'revocation',
    json_build_object(
      'key', NEW.revoked_key,
      'exp', extract(epoch from NEW.expires_at)::bigint
    )::text
  );
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER revocations_notify
AFTER INSERT ON revocations
FOR EACH ROW EXECUTE FUNCTION notify_revocation();
