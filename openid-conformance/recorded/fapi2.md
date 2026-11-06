# Results

Run of 2026-10-18 against Versola 0.7.1 (plan `fapi2-security-profile-final-test-plan`, variant `plain_fapi` / `private_key_jwt` / `dpop` / `openid_connect`).
Regenerate by running `./run.py run`; each run's `results/<stamp>/report.md` has the same table plus the failing conditions.

| Module | Result | Verdict |
|---|---|---|
| discovery-end-point-verification | PASSED | pass |
| happy-flow | FAILED | expected |
| user-rejects-authentication | not run | manual |
| ensure-authorization-request-without-state-success | PASSED | pass |
| ensure-authorization-request-without-nonce-success | PASSED | pass |
| ensure-authorization-request-with-64-char-nonce-success | PASSED | pass |
| ensure-other-scope-order-succeeds | PASSED | pass |
| test-claims-parameter-identity-claims | SKIPPED | expected |
| access-token-type-header-case-sensitivity | PASSED | pass |
| check-dpop-proof-nbf-exp | PASSED | pass |
| ensure-dpopproof-with-iat-10seconds-before-succeeds | PASSED | pass |
| ensure-dpopproof-with-iat-10seconds-after-succeeds | PASSED | pass |
| ensure-mismatched-dpop-jkt-fails | FAILED | regression |
| ensure-token-endpoint-fails-with-mismatched-dpop-proof-jkt | FAILED | regression |
| ensure-token-endpoint-fails-with-mismatched-dpop-jkt | PASSED | pass |
| ensure-dpopproof-at-par-endpoint-binding-success | PASSED | pass |
| ensure-dpop-auth-code-binding-success | PASSED | pass |
| ensure-different-nonce-inside-and-outside-request-object | PASSED | pass |
| ensure-different-state-inside-and-outside-request-object | PASSED | pass |
| ensure-authorization-request-with-long-nonce | PASSED | pass |
| ensure-authorization-request-with-long-state | PASSED | pass |
| state-only-outside-request-object-not-used | PASSED | pass |
| ensure-request-object-without-redirect-uri-fails | PASSED | pass |
| plain-fapi-tolerate-unregistered-redirect-uri | PASSED | pass |
| ensure-unsigned-authorization-request-without-using-par-fails | PASSED | pass |
| ensure-redirect-uri-in-authorization-request | PASSED | pass |
| ensure-response-type-code-idtoken-fails | FAILED | regression |
| ensure-response-type-token-fails | PASSED | pass |
| ensure-client-id-in-token-endpoint | PASSED | pass |
| ensure-holder-of-key-required | FAILED | expected |
| ensure-authorization-code-is-bound-to-client | PASSED | pass |
| attempt-reuse-authorization-code-after-one-second | WARNING | regression |
| ensure-token-endpoint-fails-with-expired-auth-code | PASSED | pass |
| ensure-signed-client-assertion-with-RS256-fails | PASSED | pass |
| ensure-client-assertion-in-token-endpoint | PASSED | pass |
| ensure-client-assertion-with-exp-is-5-minutes-in-past-fails | PASSED | pass |
| ensure-client-assertion-with-wrong-aud-fails | PASSED | pass |
| ensure-client-assertion-with-no-sub-fails | PASSED | pass |
| ensure-invalid-client-assertions-fail | PASSED | pass |
| dpop-negative-tests | WARNING | regression |
| refresh-token | FAILED | regression |
| par-ensure-reused-request-uri-prior-to-auth-completion-succeeds | REVIEW | review |
| par-attempt-reuse-request_uri | REVIEW | review |
| par-attempt-to-use-expired-request_uri | REVIEW | review |
| par-attempt-to-use-request_uri-for-different-client | REVIEW | review |
| par-authorization-request-containing-request_uri-form-param | PASSED | pass |
| par-attempt-invalid-http-method | PASSED | pass |
| par-ensure-pkce-required | PASSED | pass |
| ensure-pkce-code-verifier-required | PASSED | pass |
| incorrect-pkce-code-verifier-rejected | PASSED | pass |
| par-plain-pkce-rejected | PASSED | pass |
| par-without-duplicate-parameters | PASSED | pass |

Verdicts: `pass`; `expected` (listed in `expected.json`); `review` (the suite waits for a screenshot of an error page); `manual` (needs a person); `regression` (a deviation, see README).
