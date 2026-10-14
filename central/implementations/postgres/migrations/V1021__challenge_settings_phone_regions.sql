-- Self-registration is no longer a switch on each client. It is the tenant's decision:
--   * a phone number registers when its region (phone_regions) says so;
--   * an email registers according to email_registration (closed / open / allowlist);
--   * every registered account gets default_roles.
--
-- Phone sign-in no longer takes a list of accepted dial-code prefixes: each accepted country now
-- carries its dial code, the number of digits after it and a regex for them. Old prefixes cannot be
-- turned into either, so they are dropped, not converted -- a tenant that had any starts with no
-- regions (any E.164 number may sign in, no phone number registers) until they are set again.
ALTER TABLE challenge_settings DROP COLUMN allowed_prefixes;
ALTER TABLE challenge_settings ADD COLUMN phone_regions JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE challenge_settings ALTER COLUMN phone_regions DROP DEFAULT;

ALTER TABLE challenge_settings ADD COLUMN email_registration JSONB NOT NULL
    DEFAULT '{"mode":"closed","domains":[],"blockSubaddress":false}'::jsonb;
ALTER TABLE challenge_settings ADD COLUMN default_roles JSONB NOT NULL DEFAULT '["user"]'::jsonb;

-- Keep what each tenant has today: email registration stays open (as it was for a client with an
-- email registration flow) and the roles stay those the flows granted. A tenant none of whose
-- clients registered by email starts closed.
UPDATE challenge_settings cs
SET email_registration = '{"mode":"open","domains":[],"blockSubaddress":false}'::jsonb
WHERE EXISTS (
    SELECT 1 FROM oauth_clients c
    WHERE c.tenant_id = cs.tenant_id AND c.registration_flow ->> 'credential' = 'email'
);

UPDATE challenge_settings cs
SET default_roles = granted.roles
FROM (
    SELECT c.tenant_id, jsonb_agg(DISTINCT role) AS roles
    FROM oauth_clients c, jsonb_array_elements_text(c.registration_flow -> 'roleIds') AS role
    WHERE c.registration_flow IS NOT NULL
    GROUP BY c.tenant_id
) granted
WHERE granted.tenant_id = cs.tenant_id;

ALTER TABLE challenge_settings ALTER COLUMN email_registration DROP DEFAULT;
ALTER TABLE challenge_settings ALTER COLUMN default_roles DROP DEFAULT;

ALTER TABLE oauth_clients DROP COLUMN registration_flow;
