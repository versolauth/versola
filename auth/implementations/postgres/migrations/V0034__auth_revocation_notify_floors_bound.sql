-- The notification a write of `auth_revocations` fires carried `extract(epoch from issued_before)::bigint`.
-- A cast of a numeric to bigint ROUNDS, it does not truncate: a bound written at 12:00:00.7 went out as
-- 12:00:01. Replicas apply the notification to their in-memory list and keep the wider of what they hold and
-- what arrives, so the rounded bound won over the exact one read from the table, and every token minted in
-- the half second after the revocation (`iat` is whole seconds, and the boundary second belongs to the
-- revocation: `RevocationCache.applies`) was refused as if it had been issued before it. A user whose
-- sessions were ended and who signed in again within that second got a token that `/userinfo` and
-- `/introspect` rejected.
--
-- The bound is floored, which is what the cache's rule assumes: a token whose `iat` is the bound's own second
-- is covered, one from a later second is not. `exp` is left as it was: rounding it keeps an entry up to half a
-- second longer than its row, which refuses nothing that was not already about to expire.
--
-- Same payload, same channel, same trigger: only the function body changes, so the previous release keeps
-- reading it and keeps working on the new schema.
CREATE OR REPLACE FUNCTION notify_auth_revocation()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify(
    'auth_revocation',
    json_build_object(
      'key', NEW.revoked_key,
      'exp', extract(epoch from NEW.expires_at)::bigint,
      'before', floor(extract(epoch from NEW.issued_before))::bigint
    )::text
  );
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;
