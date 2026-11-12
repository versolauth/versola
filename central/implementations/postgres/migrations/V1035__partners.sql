-- Fail fast rather than stall, as V1034 does: the audit table below is rewritten for its new column, and a migration that
-- cannot get its locks in 5 seconds fails and is run again.
SET LOCAL lock_timeout = '5s';

-- Partners (#549, #962): an organisation the operator lets run a Versola edge on its own infrastructure and own clients
-- inside the operator's tenant. A partner is not a tenant: its clients, resources and presets live in `tenant_id` with
-- `partner_id` ownership, and the user base is the operator's. `status` is the kill switch: `suspended` refuses the
-- partner's edges everything (renewal included), omits its clients from the sync to auth and ends their grants;
-- `onboarding` is a partner whose first edge has not yet called central. `limits` is what the operator lets the partner
-- have (PartnerLimits); `settings` what the partner itself keeps for its edge (PartnerSettings). `group_id` is the
-- partner's own edge group, of kind `partner`, one to one. `console_client_id` names the partner's console client; no
-- foreign key, since that client points back here through `oauth_clients.partner_id` and is registered after this row.
CREATE TABLE partners (
    id                TEXT PRIMARY KEY,
    tenant_id         TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    display_name      TEXT NOT NULL,
    logo_uri          TEXT,
    contact           JSONB NOT NULL,
    status            TEXT NOT NULL CHECK (status IN ('onboarding', 'active', 'suspended')),
    limits            JSONB NOT NULL,
    group_id          TEXT NOT NULL UNIQUE REFERENCES edge_groups(id),
    console_client_id TEXT NOT NULL,
    settings          JSONB NOT NULL,
    review_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL
);

-- The group a partner points at is a partner group. A CHECK cannot read another table, so a trigger: the service checks
-- the same before it writes, and this keeps a direct write from binding a partner to the operator's own edges.
CREATE FUNCTION partners_group_is_partner()
RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM edge_groups WHERE id = NEW.group_id AND kind = 'partner') THEN
    RAISE EXCEPTION 'partner % needs an edge group of kind partner, % is not one', NEW.id, NEW.group_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER partners_group_is_partner
BEFORE INSERT OR UPDATE OF group_id ON partners
FOR EACH ROW EXECUTE FUNCTION partners_group_is_partner();

-- partner_change: JSON payload with id and op, as V1009 does for clients; partners are cached in memory and the status is
-- read on every call of a partner's edge, so a change has to reach the cache at once.
CREATE FUNCTION notify_partner_change()
RETURNS trigger AS $$
DECLARE
  rec RECORD;
BEGIN
  rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  PERFORM pg_notify('partner_change', json_build_object('id', rec.id, 'op', TG_OP)::text);
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER partners_notify
AFTER INSERT OR UPDATE OR DELETE ON partners
FOR EACH ROW EXECUTE FUNCTION notify_partner_change();

-- Who owns a client, and where a partner-owned one stands with the operator. NULL partner_id is a first-party client, as
-- every client before this was. `approval` is `pending` or `rejected` only for a client a partner registered through
-- its edge; a client in either state is synced to nobody (not to auth, not to any edge) and so issues no token.
--
-- The DEFAULT is for the rows that exist and for the previous release, whose INSERTs do not name the column while it
-- keeps serving during `versola migrate`; the code writes the value on every insert. It can go in a later release.
ALTER TABLE oauth_clients
    ADD COLUMN partner_id TEXT REFERENCES partners(id),
    ADD COLUMN approval TEXT NOT NULL DEFAULT 'approved' CHECK (approval IN ('pending', 'approved', 'rejected'));

CREATE INDEX oauth_clients_partner_idx ON oauth_clients (partner_id) WHERE partner_id IS NOT NULL;

-- Who owns a resource; NULL is first-party. A preset's owner is its client's (`authorization_presets.client_id`).
ALTER TABLE resources ADD COLUMN partner_id TEXT REFERENCES partners(id);

-- Where an edge is reached by browsers, for the partner's "Sign in with" links and the console. NULL for an edge
-- registered without one, which is every first-party edge so far.
ALTER TABLE edges ADD COLUMN public_url TEXT;

-- A partner's edge is registered without a signing key: it generates its own and sends the public half when it enrols
-- (#962), so central never holds a private key for it. NULL until then, and an edge with no key authenticates nowhere.
-- Every edge so far has one.
ALTER TABLE edges ALTER COLUMN public_key_jwk DROP NOT NULL;

-- What a partner asked the operator for and what the operator decided (#549 decision 7): creating a client always
-- needs approval, so does a change of its name or logo (consent phishing) or anything outside the partner's limits;
-- a profile change is the partner's own name and logo. `requested` is what the partner sent and is never rewritten;
-- `current` is what was registered before a change; `applied` is what the operator registered when approving with
-- overrides. `outside_limits` are the field names that needed the operator. `requested_by` and `decided_by` are the
-- `sub` of the person, from the staff token the edge forwards and from the console's token respectively.
CREATE TABLE partner_approvals (
    id             TEXT PRIMARY KEY,
    partner_id     TEXT NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    kind           TEXT NOT NULL CHECK (kind IN ('client_create', 'client_change', 'profile_change')),
    target_id      TEXT NOT NULL,
    requested      JSONB NOT NULL,
    current        JSONB,
    applied        JSONB,
    outside_limits TEXT[] NOT NULL,
    requested_by   TEXT NOT NULL,
    requested_at   TIMESTAMPTZ NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('waiting', 'approved', 'rejected', 'withdrawn')),
    decided_by     TEXT,
    decided_at     TIMESTAMPTZ,
    note           TEXT
);

-- The operator's queue reads the waiting ones; a partner's console reads its own.
CREATE INDEX partner_approvals_partner_idx ON partner_approvals (partner_id, requested_at);
CREATE INDEX partner_approvals_waiting_idx ON partner_approvals (requested_at) WHERE status = 'waiting';

-- The partner an audit event is about (#962): what a partner's staff did through its edge, and what the operator decided
-- about the partner, so that the partner's own feed can be read by one index. Generated from the event's payload
-- rather than written by the insert, because the insert statement is the one every service shares
-- (versola.util.postgres.AuditInsert) and auth's table has no partner. The same writer puts `partnerId` into `details` of
-- every event that is about a partner. Partitions created later inherit the column and the index.
ALTER TABLE central_audit_event
    ADD COLUMN partner_id TEXT GENERATED ALWAYS AS (details->>'partnerId') STORED;

CREATE INDEX central_audit_event_partner_idx ON central_audit_event (partner_id, at) WHERE partner_id IS NOT NULL;
