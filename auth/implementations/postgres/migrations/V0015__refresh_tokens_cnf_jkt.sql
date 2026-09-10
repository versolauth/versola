-- RFC 9449 §5: the JWK thumbprint the token is bound to, NULL for unbound (bearer) grants.
ALTER TABLE refresh_tokens ADD COLUMN cnf_jkt TEXT;
