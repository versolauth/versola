-- A group's kind is checked where it is read (`EdgeGroupKind.from`), not here. V1026 named the constraint after Postgres's rule.
ALTER TABLE edge_groups DROP CONSTRAINT IF EXISTS edge_groups_kind_check;
