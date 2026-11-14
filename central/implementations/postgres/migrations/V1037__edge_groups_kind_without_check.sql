-- No rule of the business lives in a constraint of the table: the values `edge_groups.kind` may have are the Scala enum
-- (EdgeGroupKind), which fails loudly on a stored value it does not know, and a constraint is hard to add and to remove later.
-- V1026 created the column with an inline CHECK, which Postgres names itself (`edge_groups_kind_check`); the name is looked up
-- rather than assumed, so that this runs on a database where it differs, and does nothing where there is none.
DO $$
DECLARE
  found RECORD;
BEGIN
  FOR found IN
    SELECT conname FROM pg_constraint
    WHERE conrelid = 'edge_groups'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%kind%'
  LOOP
    EXECUTE format('ALTER TABLE edge_groups DROP CONSTRAINT %I', found.conname);
  END LOOP;
END
$$;
