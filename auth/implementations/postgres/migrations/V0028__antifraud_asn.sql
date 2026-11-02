-- The autonomous system of the address a step came from, and the class of its network, as the ASN database gave them
-- when the step was recorded (antifraud.asn.file; empty while it is off, and for an address in no AS). The address itself is
-- not stored: an AS number names a network of thousands to millions of subscribers and is not personal data.
-- asn is the AS number (no AS number assigned today exceeds an INTEGER; 2^31 and above are assignable 32-bit numbers,
-- and the column is widened the day one is announced); asn_class is `hosting` or `other`.
--
-- Nullable and without a default: the previous release's inserts do not name them, and adding a nullable column to the
-- partitioned table is a catalog change on every partition, not a rewrite. No index: the one reader is the dry run of an
-- `asn` list entry, which reads a day of the log in full like the others.
--
-- The ALTER takes an exclusive lock on the table and every partition, and the event writer's inserts queue behind the lock
-- request; behind a long read of an old day it would hold them up for as long as the read runs. Better to fail fast and run
-- the migration again than to stall the writer: Flyway runs this file in one transaction, so the limit holds for both ALTERs.
SET LOCAL lock_timeout = '5s';

ALTER TABLE af_event ADD COLUMN asn INTEGER;
ALTER TABLE af_event ADD COLUMN asn_class TEXT;
