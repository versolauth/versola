-- RFC 8705 §2 gains a second method, so mtls_auth stops being one shape and becomes a
-- discriminated one: {"subjectType":...,"subjectValue":...} is now
-- {"type":"tls_client_auth","subjectType":...,"subjectValue":...}, alongside the new
-- {"type":"self_signed_tls_client_auth"}.
--
-- Every row written before this migration is a §2.1 subject match, since that was the only
-- method there was -- so a row that carries no "type" gets tls_client_auth. Rows already
-- carrying one are left alone, which is what makes this safe to re-run.
--
-- Also corrects V1026's note on jwks: mtls_auth and jwks are no longer mutually exclusive.
-- self_signed_tls_client_auth (§2.2) is the one method that needs both -- the registered key
-- set is what a presented certificate is matched against -- and registration enforces that
-- pairing rather than forbidding it.
UPDATE oauth_clients
SET mtls_auth = jsonb_set(mtls_auth, '{type}', '"tls_client_auth"')
WHERE mtls_auth IS NOT NULL
  AND NOT mtls_auth ? 'type';
