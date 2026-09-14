-- `cnf_jkt` could only ever hold an RFC 9449 DPoP thumbprint, but the RFC 7800 claim it feeds
-- is an object whose members name the mechanism that produced them. RFC 8705 adds `x5t#S256`
-- alongside `jkt`, so the column becomes the claim itself rather than one member of it.
ALTER TABLE refresh_tokens
    ADD COLUMN cnf JSONB;

UPDATE refresh_tokens
SET cnf = jsonb_build_object('jkt', cnf_jkt)
WHERE cnf_jkt IS NOT NULL;

ALTER TABLE refresh_tokens
    DROP COLUMN cnf_jkt;
