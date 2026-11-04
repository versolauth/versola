-- Fail fast rather than stall the writer: the statements below take ACCESS EXCLUSIVE on what they create, and nothing
-- else, but Flyway runs the file in one transaction and a migration that cannot get a lock in 5 seconds fails and is
-- run again (as V0030 does) rather than queue the antifraud writer's inserts behind it.
SET LOCAL lock_timeout = '5s';

-- The audit log of auth (#469, SOC 2 S1.1): one row per authentication, token, consent, account and certificate event,
-- as `AuditEvents` records it. Partitioned by day like `af_event`, and for the same reason: retention is dropping a
-- partition, which is instant and leaves nothing to vacuum. The partitions are created and dropped by the service
-- (`DailyPartitions`), not by a migration; the hot window is `audit.retention` of auth's config (7 days). Long-term
-- storage is an operator's logical replication of this table to another database (deploy.md, "Audit log"), so a row
-- is never updated or moved: an insert-only subscriber would see a duplicate key.
--
-- Column by column: `kind`, `outcome`, `severity`, `actor_type` and `source` are closed vocabularies (`AuthAuditKinds` of auth,
-- `AuditOutcome` and `AuditSeverity` of `versola.util.audit`, ...), stored as text so that the replica needs no enum of its
-- own and central can store what another service sends. `reason` is the `setError` code of a failed request.
-- `actor_id` is the user, admin, client or service that acted; `user_id` the account the event is about (the
-- per-user report reads by it). `details` is the kind's own payload (`AuditDetails`), always supplied by the
-- writer, hence no default. `ip` is INET so the summary can group by it; `user_agent` is the short
-- "browser version / os" form `UserAgentDetails` makes, never the raw header.
CREATE TABLE audit_event (
    id          UUID        NOT NULL,
    at          TIMESTAMPTZ NOT NULL,
    tenant_id   TEXT,
    source      TEXT        NOT NULL,
    node_id     TEXT        NOT NULL,
    kind        TEXT        NOT NULL,
    outcome     TEXT        NOT NULL,
    reason      TEXT,
    severity    TEXT        NOT NULL,
    actor_type  TEXT        NOT NULL,
    actor_id    TEXT,
    user_id     UUID,
    client_id   TEXT,
    session_id  TEXT,
    ip          INET,
    user_agent  TEXT,
    trace_id    TEXT,
    target_type TEXT,
    target_id   TEXT,
    details     JSONB       NOT NULL,
    PRIMARY KEY (id, at)
) PARTITION BY RANGE (at);

-- The window of a report, and what the prune of the default partition walks; BRIN because the rows arrive in time order.
CREATE INDEX audit_event_at_idx ON audit_event USING brin (at);

-- The per-user report (`GET /audit/users/{userId}/events` and `/summary`): every read of the service goes through it.
-- Partial: the rows with no user (a client-credentials grant, a failed client authentication) are not what it serves.
CREATE INDEX audit_event_user_idx ON audit_event (user_id, at) WHERE user_id IS NOT NULL;

-- What an address did, for the investigation that starts from an address rather than from an account.
CREATE INDEX audit_event_ip_idx ON audit_event (ip, at) WHERE ip IS NOT NULL;

-- An event of a day that has no partition is written here instead of being refused. Unlike `af_event_default`, the rows are never moved into the day's partition: a move is a delete
-- and an insert, and the replication the operator keeps is insert-only, so the day whose rows sit here is not
-- attached and the gauge `audit_events_in_default` says so. The rows older than the retention are deleted from here
-- by the service's own maintenance, which is the one thing allowed to delete (see the trigger below).
--
-- It takes the indexes of audit_event, as every partition does.
CREATE TABLE audit_event_default PARTITION OF audit_event DEFAULT;

-- Append-only. An UPDATE or DELETE of a row is refused unless the transaction is the service's maintenance, which
-- marks itself with `SET LOCAL versola.audit_maintenance = 'on'` (a custom GUC: unknown settings under a prefix are
-- allowed, and `current_setting(..., true)` reads NULL when it was never set). Retention itself drops partitions,
-- which is DDL and fires no row trigger, so it needs no mark.
--
-- AFTER rather than BEFORE: a BEFORE ROW trigger on a partitioned table needs PostgreSQL 13, and the minimum this
-- schema supports is 12 (deploy.md, the antifraud event log). An AFTER ROW trigger on a partitioned table is cloned
-- to every partition, present and attached later (PostgreSQL 11 and later), and raising from it aborts the
-- statement all the same.
CREATE FUNCTION audit_event_immutable() RETURNS trigger AS $$
BEGIN
    IF current_setting('versola.audit_maintenance', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'audit_event is append-only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_immutable
    AFTER UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();
