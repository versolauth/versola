-- An administrator ending a user's access revokes by subject ('sub:<user id>'), which
-- differs from the other two kinds in that the user can log in again while the entry is
-- still live. Those new tokens must be honoured, so the entry records the instant it was
-- aimed at and only tokens issued before it are rejected.
--
-- Null for 'jti:' and 'sid:' entries: nothing can issue a token under a key naming one
-- token or one dead session, so there is no later token to spare.
ALTER TABLE revocations ADD COLUMN issued_before TIMESTAMP WITH TIME ZONE;

CREATE OR REPLACE FUNCTION notify_revocation()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify(
    'revocation',
    json_build_object(
      'key', NEW.revoked_key,
      'exp', extract(epoch from NEW.expires_at)::bigint,
      'before', extract(epoch from NEW.issued_before)::bigint
    )::text
  );
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;
