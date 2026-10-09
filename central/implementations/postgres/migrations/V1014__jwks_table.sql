CREATE TABLE jwks (
    kid TEXT PRIMARY KEY,
    jwk JSONB NOT NULL,
    -- AES-GCM over PKCS#8, under the same `clientSecretsSecret` that protects client and resource
    -- secrets at rest, so this introduces no new key material or trust boundary.
    --
    -- NULL is meaningful: it marks a key as verify-only, which is what every key seeded from
    -- `bootstrap.jwks` is -- central was never given its private half.
    private_key BYTEA
);

CREATE OR REPLACE FUNCTION notify_jwks_change()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('jwks_change', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER jwks_notify
AFTER INSERT OR UPDATE OR DELETE ON jwks
FOR EACH STATEMENT EXECUTE FUNCTION notify_jwks_change();
