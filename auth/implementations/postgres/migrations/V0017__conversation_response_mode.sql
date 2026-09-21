-- JARM `response_mode`: how the authorization response a conversation ends in is returned to
-- the client, and whether it is signed. Carried on the conversation because the response is
-- built long after `/authorize` accepted the parameter. Backfilled from the response type,
-- which is what decided the placement before this column existed: a conversation already in
-- flight keeps the mode it was started under.
ALTER TABLE auth_conversations
    ADD COLUMN response_mode TEXT;

UPDATE auth_conversations
SET response_mode = CASE WHEN response_type LIKE '%IdToken%' THEN 'fragment' ELSE 'query' END
WHERE response_mode IS NULL;

ALTER TABLE auth_conversations
    ALTER COLUMN response_mode SET NOT NULL;
