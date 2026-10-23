-- Velocity counters shared between instances: per instance, key and minute, how many requests of the key the
-- instance saw. Each instance overwrites its own rows for the minutes that changed every few seconds and reads
-- the others' back (VelocityCounters), so a count converges across instances to within a flush and a load interval.
--
-- UNLOGGED: the rows are rewritten every few seconds and are worth a day at most, so they are not worth the write-ahead
-- log. A crash of the database empties the table and the instances go on with their own counts until it fills again;
-- it is not on a replica.
--
-- Every flush updates the rows of the last minutes, and every update leaves a dead tuple: the fillfactor leaves room on
-- the page for the new version (`count` is in no index, so the update stays on the page) and the table is vacuumed
-- after 2% of it has died, not 20%.
--
-- key_type is phone | ip | device | asn | prefix. The key is a keyed hash for a phone number and for the network of an
-- address, whatever the client sent for a device, and plain digits for an asn or a range. minute is minutes since the
-- epoch. Rows of an instance that is gone stay, and count, until they expire: they are traffic that was seen. The
-- service deletes rows older than a day and an hour, in batches.
CREATE UNLOGGED TABLE af_velocity (
    key_type    TEXT    NOT NULL,
    key         TEXT    NOT NULL,
    minute      BIGINT  NOT NULL,
    instance_id UUID    NOT NULL,
    count       INTEGER NOT NULL,
    PRIMARY KEY (key_type, key, minute, instance_id)
) WITH (fillfactor = 70, autovacuum_vacuum_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.05);

-- A load reads a range of minutes; pruning deletes by age.
CREATE INDEX af_velocity_minute_idx ON af_velocity (minute);
