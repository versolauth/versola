-- Shadow mode (tuning `mode = shadow`) lets every request through and records what the decision would have been.
-- `decision` is what was done and `would_action` what the engine decided: they differ in shadow, and `would_action` is
-- what a rollout reads to see what enforcing would do. NULL where there was no decision to record (and for events written
-- before this column existed).
ALTER TABLE af_event ADD COLUMN would_action TEXT;

-- Wrong solutions of a proof-of-work challenge and challenges passed, per conversation. The attempts used to travel in the
-- challenge token, which a client could keep replaying with a count of zero, and a passed token could be used again until a
-- code was sent; both are now counted here, by the statements that change them (see ConversationRepository), not by the
-- optimistic overwrite of the whole record, which leaves these two columns alone.
-- The DEFAULT is there because the table has rows, and the previous release, which keeps serving while `versola migrate` runs
-- and may be rolled back to, inserts conversations without naming these columns; it can go once that release is out of use.
ALTER TABLE auth_conversations
    ADD COLUMN challenge_failed INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN challenge_solved INTEGER NOT NULL DEFAULT 0;
