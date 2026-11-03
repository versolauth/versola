-- Fail fast rather than stall the writer: CREATE TABLE ... PARTITION OF takes ACCESS EXCLUSIVE on af_event once, and the
-- inserts of the single writer queue behind a request that waits. Flyway runs a migration in one transaction, so this holds
-- for the whole file; a migration that cannot get its lock in 5 seconds fails and is run again.
SET LOCAL lock_timeout = '5s';

-- A default partition for the antifraud event log: an event of a day that has no partition is written here instead of being
-- refused, so a maintenance that cannot run (the owner of af_event is not the service's role, a database that was down over
-- the days of partitions ahead) loses no events. The service moves a day's rows into the day's partition as it creates it
-- (it cannot attach a day's partition while the default holds a row of that day, so it moves them first, and counts them in
-- antifraud_events_moved_from_default_total), and deletes the rows older than the retention from here, since this partition
-- is never dropped. It is not a day's partition and the service ignores it when it counts the days ahead.
--
-- It takes the indexes of af_event (the primary key and the BRIN index on at), as every partition does.
CREATE TABLE af_event_default PARTITION OF af_event DEFAULT;
