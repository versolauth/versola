# Golden responses of the partner API

One file per answer of the real partner edge (`edge/open-api/partner-api.yaml`). `golden.spec.ts` compares the shape of what the
mock answers (`src/partner/mock-backend.ts`) with them: every key the mock sends must be in the golden file, with a compatible
type, and every key the golden file has must be in the mock's answer unless the spec lists it as optional. The mock cannot invent
a field the edge does not send, nor forget one it always sends.

These files were captured from a real run (`scripts/walk-partner-edge.mjs`, which also drives the console against a staged edge:
`FLOWS=1 EDGE_URL=… EMAIL=… CODE=… node scripts/walk-partner-edge.mjs`). Lists are cut to the rows that show every shape. The one-time
enrollment token is replaced by `REDACTED-ENROLLMENT-TOKEN`; an edge is registered with that one token (no bundle, no signing key: the edge makes its own); `revocations.json` is from the edge-local contract (`team.revoke` events in the audit). A few keys come from the contract rather than the run, because the staged edge had no value for
them or predates them: `logoUri`, `policyUri`, `tosUri`, `backChannelLogoutUri` and `approval.note` of a client (the edge leaves out
what it has none of), and the whole of `resources.json` and `settings.json` (a fresh partner has
no resources and empty defaults). To capture another read-only set from a running edge:

    EDGE_URL=https://edge.partner.example.com COOKIE='EDGE_SESSION=…' node scripts/capture-golden.mjs

Ids and dates are not compared, only shapes.
