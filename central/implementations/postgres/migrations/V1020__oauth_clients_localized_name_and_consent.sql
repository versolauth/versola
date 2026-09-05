-- Convert client_name from a plain string to a locale-keyed JSON object, keyed by
-- the tenant's default locale (falling back to 'en' if no default locale is configured).
-- The rekeying runs as a separate UPDATE because Postgres rejects subqueries inside an
-- ALTER COLUMN ... USING transform expression.
ALTER TABLE oauth_clients
    ALTER COLUMN client_name TYPE JSONB
    USING jsonb_build_object('en', client_name);

UPDATE oauth_clients
SET client_name = jsonb_build_object(default_locale.code, client_name -> 'en')
FROM (SELECT code FROM locales WHERE is_default LIMIT 1) AS default_locale
WHERE default_locale.code <> 'en';

ALTER TABLE oauth_clients
    ADD COLUMN logo_uri TEXT,
    ADD COLUMN policy_uri TEXT,
    ADD COLUMN tos_uri TEXT,
    ADD COLUMN consent_flow JSONB;
