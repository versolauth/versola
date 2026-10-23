-- The factors a conversation's target ACR asks of its user, when the class is one the server
-- defines itself and so depends on who the user is (a passkey for a user who has one, else a
-- password or a one-time code). Fixed when the conversation starts. NULL for a class the tenant's
-- vocabulary defines, which is looked up there instead.
ALTER TABLE auth_conversations ADD COLUMN target_factors JSONB;
