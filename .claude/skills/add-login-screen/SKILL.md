---
name: add-login-screen
description: Add or change a login-flow screen (a conversation step with its own Solid.js form in central-ui/forms) across the form, translations, central seeding, auth step model, rendering, routing, submissions, e2e and form tests. Use when asked for a new sign-in, consent, enrollment or other conversation step, or when changing what an existing step sends.
---

# Add a login-flow screen

A screen is a Scala conversation step rendered with a Solid.js form. Several of the registration
points below are hand-kept lists that **fail silently**; they are marked (silent). Copy an existing
step of the same kind: `consent`, `passkey-enroll`, `otp`, `set-password`.

Changing the fields of an **existing** step breaks conversations already stored in the `step` JSON
column; add optional fields with defaults instead (compatibility is the default).

## 1. The form (central-ui)

- `central-ui/forms/<id>/<id>.tsx`, `<id>.css`, `<id>.i18n.json` (id is kebab-case). The form reads
  `window.__VERSOLA_FORM__` (`step`, `t`, `locale`, `locales`, `allT`, `error`, `csrf`, `logo`) and
  mounts into `#versola-form-root`. Copy the locale dropdown from `consent.tsx`.
- `step.type` must equal the Scala `@jsonHint` (step 4).
- It posts to `/challenge/...?ui_locale=<current>` with a hidden `csrf` field (plain `<form
  method="post">`, or `submitViaForm` from `forms/passkey/webauthn.ts`). `error` is a **translation
  key**, not a message.
- `<id>.i18n.json`: `en` and `ru` with identical keys, `page_title` (the HTML title), and
  `service_unavailable` (the render service re-renders the step with that key on a backend failure).
  `forms/i18n.test.ts` enforces key and placeholder parity.
- Add the id to the `forms` array in `central-ui/scripts/build-forms.mjs` (silent: otherwise it is
  never built). Output goes to the gitignored `central/src/main/resources/forms/`.
- Inputs need accessible names; see `frontend.md`.

## 2. Central seeding (silent)

`central/implementations/postgres/src/main/scala/versola/BootstrapService.scala`: add
`"<id>" -> Vector(<BackendProperty>...)` to `defaultForms` (`Vector.empty` if none). A seeding error
is only logged (`Failed to seed form <id>`); bootstrap still succeeds, and auth then serves a 404
"Page not found". After a local change, check the central log for that message.

## 3. Step model (auth)

- `auth/src/main/scala/versola/auth/model/StepId.scala`: a new case object.
- `auth/.../oauth/conversation/model/ConversationStep.scala`: `case class X(...) extends
  ConversationStep(StepId.X)`, with failure flags if the form shows them. The codec is derived and
  stored automatically.
- `ConversationRecord.scala`: an extractor like `ConversationRecord.Consent.unapply`.

## 4. Rendering

`auth/.../oauth/conversation/ConversationRenderService.scala`:
- `StepView`: `@jsonHint("<id>") case class X(...) extends StepView` (the JSON the form receives;
  mask sensitive values here).
- `formFor` (form id), `stepView(...)`, the private `stepName`, `formLogo` if the logo is hidden.
- `stepErrorKey` ends in `case _ => None`, so it is **not exhaustive** (silent): a missing case shows
  no error. Its key is also the logged error code, so add it to the form's i18n.

## 5. Producing and routing the step

- `ConversationService.scala`: add the producer (copy `offerPasskeyEnroll`, `offerSetPassword`);
  it ends in `renderStep(...)`, and `false` means `WriteConflict`.
- `ConversationRouter.scala`: where the step is entered (`afterFactor`, `afterAuthenticationCredential`,
  `runRegistrationStep`, `finish`) and the `dispatch` case `(x: XSubmission, ConversationRecord.X(step))`.
  An unmatched pair falls to `step_mismatch`; a broader earlier pattern shadows yours.
- `Submission.scala`: `XSubmission(..., csrf: String)`.
- `ConversationController.scala`: the `submitXRoute` and **add it to `routes`** (silent: 404 and no
  compile error), a `given FormDecoder[XSubmission]` that decodes `csrf`, and a `validate` case if
  the payload needs validation. CSRF is checked once, in `Router.submit`.
- Every failure ends in `Observability.setError` with a closed-vocabulary code (`observability.md`).
- Metrics: `observeDecision("<name>")` / `AuthMetrics.stepPassed|stepFailed`, and the router's private
  `stepName(ConversationStep)` (snake_case labels; form ids are kebab-case).
- If the screen is configurable per client flow, it may also need `AuthFactorType`, `PassedAuthFactor`
  or `RegistrationStep` (auth `client/model/`, central `clients/`), `central.yaml`, and
  `central-ui` `client-form.ts`. Check with `passkey-enroll` as the model.

## 6. Hand-kept lists elsewhere (silent)

- `e2e/src/test/scala/versola/e2e/support/ConversationStep.scala`: the enum case **and** the `known`
  list (otherwise it shows up as `Unknown(...)`).
- `loadgen/src/main/scala/versola/loadgen/protocol/ConversationStep.scala`: same two places; check
  `HttpAuthClient.scala` and `AuthClient.scala`.

## 7. Tests

- central-ui: a block in `central-ui/tests/login-forms.spec.ts` (what the user can do, what is sent:
  path, `ui_locale`, fields, CSRF) and an entry in its axe list; `forms/i18n.test.ts` runs by itself.
  Show a screenshot of the form to the operator (`frontend.md`; wait for a go-ahead for a new
  screen).
- auth unit: `ConversationRenderServiceSpec` (the `versola-step` meta tag, `getForm` stub),
  `ConversationControllerSpec` (the request-to-submission table), `ConversationRouterSpec`,
  `ConversationServiceSpec`, and the codec round trip in `ConversationRecordSpec`.
- e2e: a spec under `e2e/.../flows/<area>/` and submit helpers in `support/OAuthClient.scala`.
- `/challenge` is not in `auth/open-api/`; no spec change unless the step is configurable via
  `central.yaml`.

## 8. Finish

`npm run build:forms` before building central, then the `pre-pr-check` skill.
