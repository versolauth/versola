-- The country of the address a step came from, as the DB-IP country database gave it when the step was recorded
-- (antifraud.geo.file), or, for a network that is not hosting, the country its AS is registered in (antifraud.asn.file): an
-- ISO 3166-1 alpha-2 code in upper case. The address itself is not stored.
--
-- Nullable and without a default: both databases are optional, so most deployments have none to write, and the previous
-- release's inserts do not name the column; adding a nullable column to the partitioned table is a catalog change on every
-- partition, not a rewrite. No index: the one reader is the dry run of a `country` list entry, which reads a day of the log
-- in full like the others.
--
-- The ALTER takes an exclusive lock on the table and every partition, and the event writer's inserts queue behind the lock
-- request; behind a long read of an old day it would hold them up for as long as the read runs. Better to fail fast and run
-- the migration again than to stall the writer.
SET LOCAL lock_timeout = '5s';

ALTER TABLE af_event ADD COLUMN country TEXT;
