UPDATE challenge_settings
SET passkey_settings = jsonb_set(
  passkey_settings,
  '{origins}',
  '["http://localhost:3000", "http://localhost:9005"]'
)
WHERE tenant_id = 'default';
