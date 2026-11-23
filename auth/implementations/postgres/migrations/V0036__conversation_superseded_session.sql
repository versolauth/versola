-- The session the browser had in another cell when its sign-in was handed to this one (versolauth/versola#1048): the
-- public id of that session and its user. A sign-in that finishes here ends that session through central, as one
-- that finishes in the cell of the session ends it itself (prior_session_id). NULL for a sign-in that was not handed
-- over, or was handed over from a browser with no session.
ALTER TABLE auth_conversations ADD COLUMN superseded_session_id TEXT;
ALTER TABLE auth_conversations ADD COLUMN superseded_session_user_id UUID;
