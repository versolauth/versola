-- Supersedes V0004's description of family_id. It was the root token's own storage key --
-- the MAC of a refresh token value -- stamped on every generation of the chain and outliving
-- all of them. Nothing ever authenticates by presenting it, so it is an identity rather than
-- a credential, and it is now a random value generated per family: a value derived from a
-- credential should not live as long as the grant, nor end up anywhere the family has to be
-- named.
--
-- No data migration: nothing is deployed against this schema yet.
ALTER TABLE refresh_tokens
    ALTER COLUMN family_id TYPE TEXT USING encode(family_id, 'hex');
