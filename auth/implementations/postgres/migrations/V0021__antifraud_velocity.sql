-- Velocity counters shared between instances: per instance, key and minute, how many requests of the key the
-- instance saw. Each instance overwrites its own rows for the minutes that changed every few seconds and reads the
-- others' back (VelocityCounters), so a count converges across instances to within a flush and a load interval.
--
-- key_type is phone | ip | device | asn | prefix. The key is a keyed hash for a phone number, the address itself for an
-- ip (personal data: the rows live for a day plus an hour, then the service deletes them) and plain digits for an
-- asn or a range. minute is minutes since the epoch. Rows of an instance that is gone stay, and count, until
-- they expire: they are traffic that was seen.
CREATE TABLE af_velocity (
    key_type    TEXT    NOT NULL,
    key         TEXT    NOT NULL,
    minute      BIGINT  NOT NULL,
    instance_id UUID    NOT NULL,
    count       INTEGER NOT NULL,
    PRIMARY KEY (key_type, key, minute, instance_id)
);

-- Load reads the last day by time, and pruning deletes by age.
CREATE INDEX af_velocity_minute_idx ON af_velocity (minute);
