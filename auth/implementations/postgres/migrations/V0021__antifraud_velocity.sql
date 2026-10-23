-- Velocity counters shared between instances: per instance, key and minute, how many requests of the key the
-- instance saw. Each instance overwrites its own rows for the minutes that changed every few seconds and reads
-- what the others changed back (VelocityCounters), so a count converges across instances to within a flush and a
-- load interval.
--
-- UNLOGGED: the rows are rewritten every few seconds and are worth a day at most, so they are not worth the write-ahead
-- log. A crash of the database empties the table and the instances go on with their own counts until it fills again;
-- it is not on a replica.
--
-- key_type is phone | ip | device | asn | prefix. The key is a keyed hash for a phone number and for the network of an
-- address, whatever the client sent for a device, and plain digits for an asn or a range. minute is minutes since the
-- epoch; updated_at (the database's clock) lets a load read only what changed. Rows of an instance that is gone stay,
-- and count, until they expire: they are traffic that was seen. The service deletes rows older than a day and an hour.
CREATE UNLOGGED TABLE af_velocity (
    key_type    TEXT        NOT NULL,
    key         TEXT        NOT NULL,
    minute      BIGINT      NOT NULL,
    instance_id UUID        NOT NULL,
    count       INTEGER     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (key_type, key, minute, instance_id)
);

-- Pruning deletes by age.
CREATE INDEX af_velocity_minute_idx ON af_velocity (minute);

-- A load reads what changed since the last one.
CREATE INDEX af_velocity_updated_idx ON af_velocity (updated_at);
