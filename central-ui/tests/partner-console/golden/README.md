# Golden responses of the partner API

One file per answer of the real partner edge (`edge/open-api/partner-api.yaml`). `golden.spec.ts` compares the shape of what the
mock answers (`src/partner/mock-backend.ts`) with them: every key the mock sends must be in the golden file, with a compatible
type, and every key the golden file has must be in the mock's answer unless the spec lists it as optional. The mock cannot invent
a field the edge does not send, nor forget one it always sends.

These files were written from the contract's schemas and examples. Replace them with what a real edge answered:

    EDGE_URL=https://edge.partner.example.com COOKIE='EDGE_SESSION=…' node scripts/capture-golden.mjs

The script writes the same file names, with the values of the real run (ids and dates are not compared, only shapes).
