-- RFC 9396 `authorization_details`: the granted detail objects are stored verbatim so they
-- can be echoed back unchanged in the token response and compared against a later refresh
-- request (RFC 9396 section 6.1). Nullable, with no default: NULL means no
-- `authorization_details` was requested, distinct from an empty array.
ALTER TABLE auth_conversations
    ADD COLUMN authorization_details JSONB[];

ALTER TABLE authorization_codes
    ADD COLUMN authorization_details JSONB[];

ALTER TABLE refresh_tokens
    ADD COLUMN authorization_details JSONB[];
