-- The rate limit policies of an edge: who may send how much through it, per client, subject, session, key, resource or
-- endpoint (limits in versola.util.http.RateLimitPolicies). Replaced as a whole set, per edge, independent of the overload
-- and access-log policies. NULL -- none set -- means the edge applies no rate limit. Every policy starts in `shadow` by
-- convention: the edge counts what it would refuse and refuses nothing until enforcement is wired in.
-- Nullable, no default: the previous release's inserts do not name it, and the code that sets it validates it first.
ALTER TABLE edges ADD COLUMN rate_limit_policies JSONB;
