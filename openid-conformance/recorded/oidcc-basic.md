# oidcc-basic: recorded results

Run `20261018-163339`, plan `oidcc-basic-certification-test-plan`. Written by `./run.py run --suite oidcc-basic --record`;
the run's `report.md` has the failing conditions.

| Module | Result | Verdict |
|---|---|---|
| oidcc-server | PASSED | pass |
| oidcc-response-type-missing | PASSED | pass |
| oidcc-userinfo-get | PASSED | pass |
| oidcc-userinfo-post-header | PASSED | pass |
| oidcc-userinfo-post-body | WARNING | expected |
| oidcc-ensure-request-without-nonce-succeeds-for-code-flow | PASSED | pass |
| oidcc-scope-profile | PASSED | pass |
| oidcc-scope-email | PASSED | pass |
| oidcc-scope-address | PASSED | pass |
| oidcc-scope-phone | PASSED | pass |
| oidcc-scope-all | PASSED | pass |
| oidcc-alternate-happy-flow | PASSED | pass |
| oidcc-display-page | PASSED | pass |
| oidcc-display-popup | PASSED | pass |
| oidcc-prompt-login | REVIEW | review |
| oidcc-prompt-none-not-logged-in | PASSED | pass |
| oidcc-prompt-none-logged-in | PASSED | pass |
| oidcc-max-age-1 | REVIEW | review |
| oidcc-max-age-10000 | PASSED | pass |
| oidcc-ensure-request-with-unknown-parameter-succeeds | PASSED | pass |
| oidcc-id-token-hint | PASSED | pass |
| oidcc-login-hint | PASSED | pass |
| oidcc-ui-locales | PASSED | pass |
| oidcc-claims-locales | PASSED | pass |
| oidcc-ensure-request-with-acr-values-succeeds | WARNING | expected |
| oidcc-codereuse | PASSED | pass |
| oidcc-codereuse-30seconds | WARNING | expected |
| oidcc-ensure-registered-redirect-uri | REVIEW | review |
| oidcc-ensure-post-request-succeeds | PASSED | pass |
| oidcc-server-client-secret-post | PASSED | pass |
| oidcc-unsigned-request-object-supported-correctly-or-rejected-as-unsupported | SKIPPED | expected |
| oidcc-claims-essential | PASSED | pass |
| oidcc-ensure-request-object-with-redirect-uri | SKIPPED | expected |
| oidcc-refresh-token | PASSED | pass |
| oidcc-ensure-request-with-valid-pkce-succeeds | PASSED | pass |

Verdicts: `pass`; `expected` (listed in `expected/oidcc-basic.json`); `review` (the suite waits for a screenshot of an error page); `manual` (needs a person); `regression` (a deviation).
