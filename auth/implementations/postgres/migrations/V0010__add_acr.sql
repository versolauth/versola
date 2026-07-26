ALTER TABLE authorization_codes ADD COLUMN acr TEXT;
ALTER TABLE auth_conversations ADD COLUMN target_acr TEXT;
ALTER TABLE refresh_tokens ADD COLUMN acr TEXT;
