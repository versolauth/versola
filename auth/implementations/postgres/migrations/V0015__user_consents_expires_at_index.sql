-- The cleanup manager sweeps `expires_at < NOW() ORDER BY expires_at LIMIT n`; without an index that is a
-- full scan and sort of the table on every pass. Grants that last until revoked have expires_at NULL and
-- are never swept, so they stay out of the index.
CREATE INDEX user_consents_expires_at_idx ON user_consents (expires_at) WHERE expires_at IS NOT NULL;
