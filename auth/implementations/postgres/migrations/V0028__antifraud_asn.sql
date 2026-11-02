-- The autonomous system of the address a step came from, and the class of its network, as the ASN database gave them
-- when the step was recorded (antifraud.asn.file; empty while it is off, and for an address in no AS). The address itself is
-- not stored: an AS number names a network of thousands to millions of subscribers and is not personal data.
-- asn is the AS number (every AS in use fits an INTEGER); asn_class is `hosting` or `other`.
--
-- Nullable and without a default: the previous release's inserts do not name them, and adding a nullable column to the
-- partitioned table is a catalog change on every partition, not a rewrite. No index: the one reader is the dry run of an
-- `asn` list entry, which reads a day of the log in full like the others.
ALTER TABLE af_event ADD COLUMN asn INTEGER;
ALTER TABLE af_event ADD COLUMN asn_class TEXT;
