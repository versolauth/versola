ALTER TABLE auth_conversations ADD COLUMN IF NOT EXISTS needs_password_change BOOLEAN NOT NULL DEFAULT false;
