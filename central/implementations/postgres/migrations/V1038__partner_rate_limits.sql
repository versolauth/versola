-- The rate limits of a partner's edges are one setting for its group, with one revision of its own: stored once, here, and served to
-- every edge of the group. They are not the edges' own `rate_limit_policies`, and a change of them does not touch `version`.
ALTER TABLE partners ADD COLUMN rate_limits JSONB;
ALTER TABLE partners ADD COLUMN rate_limits_revision INTEGER NOT NULL DEFAULT 0;
