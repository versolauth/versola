-- Tenant passkey enrolment policy: whether and how firmly a signed-in user without a passkey is asked to enrol one
-- (mode off | offered | required_after_grace | required, plus the grace in skips and/or days).
--
-- Nullable with no backfill: NULL means no tenant policy, and each client's `passkeyEnroll` factor decides as it did
-- before, so the previous release (which does not know the column) and every existing tenant keep their behaviour.
ALTER TABLE challenge_settings ADD COLUMN passkey_policy JSONB;
