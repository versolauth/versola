-- A confidential client of a `standard` tenant may omit PKCE, so a conversation and the
-- authorization code it ends in can carry no challenge. Both columns stay a pair: a row has
-- either both or neither, which the CHECKs hold so a half-written record cannot be stored.
ALTER TABLE auth_conversations
    ALTER COLUMN code_challenge DROP NOT NULL,
    ALTER COLUMN code_challenge_method DROP NOT NULL,
    ADD CONSTRAINT auth_conversations_pkce_pair
        CHECK ((code_challenge IS NULL) = (code_challenge_method IS NULL));

ALTER TABLE authorization_codes
    ALTER COLUMN code_challenge DROP NOT NULL,
    ALTER COLUMN code_challenge_method DROP NOT NULL,
    ADD CONSTRAINT authorization_codes_pkce_pair
        CHECK ((code_challenge IS NULL) = (code_challenge_method IS NULL));
