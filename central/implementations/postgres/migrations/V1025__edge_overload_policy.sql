-- What an edge does when it is asked for more than it can do: its inbound admission limits (and the adaptive limit
-- inside them) and the limits, timeouts, circuit breaker and retry budget of its outbound calls. A JSON patch over the
-- values the edge starts with (its environment), so a NULL here -- no policy set -- means "the deployment's values",
-- and a key left out of the object does too. Per edge, like require_dpop_nonce, so an operator can change it from the
-- console while the edge is under load without redeploying it. Shape: versola.util.http.OverloadPolicy.
-- Nullable, no default: the previous release's inserts do not name it, and the code that sets it validates it first.
ALTER TABLE edges ADD COLUMN overload_policy JSONB;
