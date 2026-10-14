-- Phone sign-in no longer takes a list of accepted dial-code prefixes: each accepted country now
-- carries its dial code, the number of digits after it and a regex for them. Old prefixes cannot be
-- turned into either, so they are dropped, not converted -- a tenant that had any starts with no
-- regions (any E.164 number) until they are set again.
ALTER TABLE challenge_settings DROP COLUMN allowed_prefixes;
ALTER TABLE challenge_settings ADD COLUMN phone_regions JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE challenge_settings ALTER COLUMN phone_regions DROP DEFAULT;
