-- Which of its own requests an edge writes a `receive-http` log line for: mode (full, sampled, off), the slow threshold
-- and the share of successful requests kept when sampled. A JSON patch over the values the edge starts with
-- (`HTTP_ACCESS_LOG*`, or the edge's default of `off`), so a NULL here -- no policy set -- means "the deployment's values",
-- and a key left out of the object does too. Per edge and independent of overload_policy, so an operator can turn request
-- logging up while investigating, or down under load, from the console without redeploying the edge. auth and central
-- have no such setting: they always log every request. Shape: versola.util.http.AccessLogPolicy.
-- Nullable, no default: the previous release's inserts do not name it, and the code that sets it validates it first.
ALTER TABLE edges ADD COLUMN access_log_policy JSONB;
