-- How a client authenticates stops being inferred and becomes a stored column.
--
-- It was read out of the other columns: a row with no secret was a public client, and a row
-- with jwks or mtls_auth authenticated with those. That reading cannot express the client
-- this server now registers most often -- one that authenticates with a key set and holds no
-- secret at all -- because a secretless row was, by that reading, public, and a public client
-- is refused client_credentials. Every web client was therefore issued a secret it would
-- never be allowed to use.
--
-- The derivation below is the old reading, applied once: the column starts out saying exactly
-- what the previous code would have concluded. The secrets of every row that turns out not to
-- authenticate with one are then dropped, because that is the change -- a credential no
-- endpoint accepts is not one to keep encrypted at rest.
ALTER TABLE oauth_clients ADD COLUMN auth_method TEXT NOT NULL DEFAULT 'client_secret';

UPDATE oauth_clients
SET auth_method = CASE
  WHEN secret IS NULL                                        THEN 'none'
  WHEN mtls_auth ->> 'type' = 'tls_client_auth'              THEN 'tls_client_auth'
  WHEN mtls_auth ->> 'type' = 'self_signed_tls_client_auth'  THEN 'self_signed_tls_client_auth'
  WHEN jwks IS NOT NULL                                      THEN 'private_key_jwt'
  ELSE 'client_secret'
END;

UPDATE oauth_clients
SET secret = NULL, previous_secret = NULL
WHERE auth_method <> 'client_secret';

-- The default exists only to let the column be added NOT NULL to a populated table; a
-- registration states its method, and one that does not is a caller to reject rather than a
-- client to guess a credential for.
ALTER TABLE oauth_clients ALTER COLUMN auth_method DROP DEFAULT;
