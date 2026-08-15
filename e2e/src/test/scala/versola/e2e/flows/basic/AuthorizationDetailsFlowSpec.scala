package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.test.*

/** RFC 9396 (Rich Authorization Requests) end-to-end coverage.
  *
  *   - happy path: a registered type + a conforming `authorization_details` object is granted
  *     and echoed back in the token response
  *   - an `authorization_details` object naming a type not registered in central is rejected
  *     at `/authorize` with `error=invalid_authorization_details`
  *   - registering a type whose `schema` is itself not a valid JSON Schema is rejected by
  *     central at write time, so it can never reach `/authorize`
  *   - the same validation applies to `authorization_details` pushed to `/par` (RFC 9126 §2.1),
  *     where a rejection is reported to the client directly instead of being redirected
  */
object AuthorizationDetailsFlowSpec extends E2ESpec:

  private val paymentSchema =
    """{
      |  "$schema": "https://json-schema.org/draft/2020-12/schema",
      |  "type": "object",
      |  "properties": {
      |    "type": { "type": "string" },
      |    "actions": { "type": "array", "items": { "type": "string" } },
      |    "instructedAmount": {
      |      "type": "object",
      |      "properties": { "currency": { "type": "string" }, "amount": { "type": "string" } },
      |      "required": ["currency", "amount"]
      |    }
      |  },
      |  "required": ["type", "instructedAmount"]
      |}""".stripMargin

  def spec = suite("Authorization Details (RFC 9396)")(
    test("happy path: registered type + conforming detail is granted and echoed in the token") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        typeName = s"payment-${s.clientId}"
        _ <- auth.registerAuthorizationDetailType(typeName, paymentSchema).success
        _ <- auth.syncConfiguration()

        requested = s"""[{"type":"$typeName","actions":["initiate"],"instructedAmount":{"currency":"EUR","amount":"10.00"}}]"""
        authorize <- auth.authorize(
          clientId = Some(s.clientId),
          redirectUri = Some(s.redirectUri),
          authorizationDetails = Some(requested),
        ).assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, challenge.csrf)
          .assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(token.authorizationDetails.exists(_.elements.nonEmpty))
        .label(s"expected authorization_details in token response, got ${token.authorizationDetails}")
    },

    test("unregistered type redirects with error=invalid_authorization_details") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        requested = s"""[{"type":"no-such-type-${s.clientId}","amount":"10.00"}]"""
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          authorizationDetails = Some(requested),
        ).assertErrorRedirect("invalid_authorization_details")
      yield assertCompletes
    },

    test("registering a type with an invalid JSON Schema is rejected by central") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        typeName = s"invalid-schema-${s.clientId}"
        // "type": 123 is not a valid JSON Schema "type" value (must be a string or array of strings).
        result <- auth.registerAuthorizationDetailType(typeName, """{"type": 123}""")
      yield assertTrue(result.response.status.isClientError)
        .label(s"expected a client error registering an invalid schema, got status=${result.response.status}")
    },

    test("pushed request carries authorization_details through to the token") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        typeName = s"par-payment-${s.clientId}"
        _ <- auth.registerAuthorizationDetailType(typeName, paymentSchema).success
        _ <- auth.syncConfiguration()

        requested = s"""[{"type":"$typeName","actions":["initiate"],"instructedAmount":{"currency":"EUR","amount":"10.00"}}]"""
        pushed <- auth.pushAuthorizationRequest(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
          authorizationDetails = Some(requested),
        ).success

        authorize <- auth.authorizePushed(s.clientId, pushed).assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, challenge.csrf)
          .assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(token.authorizationDetails.exists(_.elements.nonEmpty))
        .label(s"expected authorization_details in token response, got ${token.authorizationDetails}")
    },

    test("/par rejects an unregistered type directly instead of redirecting") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        requested = s"""[{"type":"no-such-type-${s.clientId}","amount":"10.00"}]"""
        result <- auth.pushAuthorizationRequest(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
          authorizationDetails = Some(requested),
        )
      yield assertTrue(result match
        case PushedAuthorizationResult.Failure(response, _, error) =>
          response.status == Status.BadRequest && error.contains("invalid_authorization_details")
        case _: PushedAuthorizationResult.Success => false,
      ).label(s"expected /par to reject an unregistered type, got $result")
    },

    test("/par rejects a detail that violates the registered schema") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        typeName = s"par-strict-${s.clientId}"
        _ <- auth.registerAuthorizationDetailType(typeName, paymentSchema).success
        _ <- auth.syncConfiguration()

        // `instructedAmount` is required by paymentSchema.
        requested = s"""[{"type":"$typeName","actions":["initiate"]}]"""
        result <- auth.pushAuthorizationRequest(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
          authorizationDetails = Some(requested),
        )
      yield assertTrue(result match
        case PushedAuthorizationResult.Failure(response, _, error) =>
          response.status == Status.BadRequest && error.contains("invalid_authorization_details")
        case _: PushedAuthorizationResult.Success => false,
      ).label(s"expected /par to reject a schema violation, got $result")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
