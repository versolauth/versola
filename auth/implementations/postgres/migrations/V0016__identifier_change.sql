-- Self-service change of a phone number or email (versolauth/versola#634).
--
-- `user_intents` records what a signed-in user asked for on the account page and has not yet had
-- carried out, so that the request which carries it out can find it there first. Today that is a
-- change of phone number or email (`{"type":"change_phone","phone":"+7..."}`): `/authorize` honours
-- the reserved `urn:versola:acr:{phone,email}-change` values only when a matching intent exists, so
-- a client cannot start the flow on its own. At most one intent per user (a newer one replaces the
-- older); it is consumed once and expires after a few minutes. Such requests are rare, so unlike the
-- login-state tables of V0014 the table stays logged.
CREATE TABLE user_intents (
    user_id    UUID PRIMARY KEY,
    client_id  TEXT NOT NULL,
    intent     JSONB NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX user_intents_expires_at_idx
    ON user_intents (expires_at);

-- The change a login conversation carries: the new identifier and how far its verification got.
-- Nullable: every conversation that is not a change keeps it NULL.
ALTER TABLE auth_conversations ADD COLUMN identifier_change JSONB;
