-- #962 review: the limits a partner has are enforced where the rows are written, not against a cache that a notification
-- refreshes after the commit (two creations at `max - 1` both passed it).
--
-- The partner's row is locked (`FOR NO KEY UPDATE`, which the foreign keys that point at it do not wait on) before its rows
-- are counted, so two inserts of one partner run one after the other and the second counts the first. The count is of what
-- the partner's console shows: the console client is not counted, and not refused (it is registered with the partner).
CREATE FUNCTION oauth_clients_partner_limit()
RETURNS trigger AS $$
DECLARE
  owner    partners%ROWTYPE;
  existing INT;
BEGIN
  SELECT * INTO owner FROM partners WHERE id = NEW.partner_id FOR NO KEY UPDATE;
  IF NOT FOUND OR NEW.id = owner.console_client_id THEN
    RETURN NEW;
  END IF;
  SELECT count(*) INTO existing FROM oauth_clients WHERE partner_id = NEW.partner_id AND id <> owner.console_client_id;
  IF existing >= (owner.limits->>'maxClients')::int THEN
    RAISE EXCEPTION 'partner_client_limit: partner % may register at most % clients', owner.id, owner.limits->>'maxClients'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER oauth_clients_partner_limit
BEFORE INSERT ON oauth_clients
FOR EACH ROW WHEN (NEW.partner_id IS NOT NULL)
EXECUTE FUNCTION oauth_clients_partner_limit();

-- The same for the edges of the partner's group.
CREATE FUNCTION edges_partner_limit()
RETURNS trigger AS $$
DECLARE
  owner    partners%ROWTYPE;
  existing INT;
BEGIN
  SELECT * INTO owner FROM partners WHERE group_id = NEW.group_id FOR NO KEY UPDATE;
  IF NOT FOUND THEN
    RETURN NEW;
  END IF;
  SELECT count(*) INTO existing FROM edges WHERE group_id = NEW.group_id;
  IF existing >= (owner.limits->>'maxEdges')::int THEN
    RAISE EXCEPTION 'partner_edge_limit: partner % may run at most % edges', owner.id, owner.limits->>'maxEdges'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER edges_partner_limit
BEFORE INSERT ON edges
FOR EACH ROW EXECUTE FUNCTION edges_partner_limit();

-- Lost updates: the operator's profile change, the partner's own settings and an approved profile change all write the same
-- row (`contact` among them). A write names the version it read and loses when the row has moved on.
ALTER TABLE partners ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
