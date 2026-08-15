-- Registration state for a conversation started through the register button.
-- registration_flow is a snapshot of the client's flow taken when the conversation is
-- created, so a configuration change mid-conversation cannot alter the steps in flight.
-- registration_step is the index of the pending step and is null while the user is signing in.
ALTER TABLE auth_conversations
    ADD COLUMN registration_flow JSONB,
    ADD COLUMN registration_step INT;
