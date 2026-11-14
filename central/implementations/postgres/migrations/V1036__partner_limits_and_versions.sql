-- Lost updates: the operator's profile change, the partner's own settings and an approved profile change all write the same
-- row (`contact` among them). A write names the version it read and loses when the row has moved on.
ALTER TABLE partners ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
