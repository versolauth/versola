-- Self-service change of a phone number or email (versolauth/versola#634).
--
-- `identifier_change_intents` records that a signed-in user asked, on the account page, to change
-- one identifier to a given new value. `/authorize` honours the reserved
-- `urn:versola:acr:{phone,email}-change` values only when a matching intent exists, so a client
-- cannot start the flow on its own. At most one intent per user (a newer one replaces the older);
-- it is consumed once, when the authorization request that follows it is accepted, and expires
-- after a few minutes. A change of phone number or email is rare, so unlike the login-state
-- tables of V0014 it stays logged: the write cost is negligible and an intent survives a crash.
CREATE TABLE identifier_change_intents (
    user_id    UUID PRIMARY KEY,
    client_id  TEXT NOT NULL,
    kind       TEXT NOT NULL,
    value      TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX identifier_change_intents_expires_at_idx
    ON identifier_change_intents (expires_at);

-- The change a login conversation carries: the new identifier and how far its verification got.
-- Nullable: every conversation that is not a change keeps it NULL.
ALTER TABLE auth_conversations ADD COLUMN identifier_change JSONB;
