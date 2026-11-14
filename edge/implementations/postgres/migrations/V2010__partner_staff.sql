-- Who may use a partner edge's console, and at what level (#564, design decision 3 of #549). The staff of a partner live
-- here, in the edge's own database, and nowhere at the operator: nothing in auth or central grows with the number of
-- partners. A first-party edge never writes either table.
--
-- `subject` is the `sub` of the member's account at the operator's auth, learned at their first sign-in. `level` is
-- `admin`, `developer` or `viewer`. `display` is how the Team page names them (the name from /userinfo, else the masked
-- identifier). `added_by` is the `sub` of the administrator who invited them, `config` for a first administrator named in
-- `partner.admins`.
CREATE TABLE partner_staff (
    subject  TEXT PRIMARY KEY,
    level    TEXT NOT NULL,
    display  TEXT NOT NULL,
    added_by TEXT NOT NULL,
    added_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- An invitation to a phone number or an email address that nobody has signed in with yet. Nothing is looked up at the
-- operator when it is made (so it cannot be used to find out who has an account there); it is matched at the first sign-in of
-- whoever's /userinfo carries that `phone_number` or `email`, which is then admitted and the invite consumed.
--
-- `identifier_hash` is an HMAC of the normalised identifier under a key only this edge holds, hex-encoded, so the table does
-- not hold a phone number or an address, and a copy of it cannot be tested against a list of them. `identifier_masked` is what
-- the Team page shows (`+7 7•• ••• 11`). One invite per identifier. An invite past `expires_at` admits nobody.
CREATE TABLE partner_staff_invites (
    id                UUID PRIMARY KEY,
    identifier_kind   TEXT NOT NULL,
    identifier_hash   TEXT NOT NULL,
    identifier_masked TEXT NOT NULL,
    level             TEXT NOT NULL,
    invited_by        TEXT NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (identifier_kind, identifier_hash)
);

-- The first administrators named in the configuration (`partner.admins`), by the same keyed hash as an invite. A configured identifier is
-- made an invite at every start until the person has signed in once (`admitted_at`); after that it is not made again, so removing such a
-- person from the team stays removed across restarts and only an administrator's own invite brings them back.
CREATE TABLE partner_staff_config_admins (
    identifier_kind TEXT NOT NULL,
    identifier_hash TEXT NOT NULL,
    applied_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    admitted_at     TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (identifier_kind, identifier_hash)
);

-- What was done to the team: an invite, a change of level, a removal, an admission; and the sessions an administrator ended (`revoke`, `target` the value, `detail` `<by>:<how many>`). The partner's audit trail shows it next to central's
-- events, because the team is the one thing central does not hold. `actor` is a `sub` (`config` for a configured administrator's invite);
-- `target` a `sub` or a masked identifier, never a phone number or an address.
CREATE TABLE partner_staff_events (
    id     UUID PRIMARY KEY,
    at     TIMESTAMP WITH TIME ZONE NOT NULL,
    actor  TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL,
    detail TEXT
);
CREATE INDEX partner_staff_events_at ON partner_staff_events (at DESC);
