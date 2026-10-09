-- The user a conversation's prior session belonged to, known at /authorize where that session is
-- looked up. At completion it decides whether the prior session's refresh tokens may move to the
-- new session (the same user signed in again) or must be expired (another user did), without
-- fetching the session a second time. NULL for a conversation with no prior session, and for one
-- started before this column existed, which is treated as "not the same user".
ALTER TABLE auth_conversations ADD COLUMN prior_session_user_id UUID;
