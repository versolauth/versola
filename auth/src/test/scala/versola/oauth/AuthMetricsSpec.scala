package versola.oauth

import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.ConversationRecord
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import zio.*
import zio.http.URL
import zio.metrics.{Metric, MetricLabel}
import zio.prelude.NonEmptySet
import zio.test.*

import java.util.UUID

object AuthMetricsSpec extends ZIOSpecDefault:
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val responseType = NonEmptySet.single(ResponseTypeEntry.Code)
  private val emptyRecord = ConversationRecord(
    clientId = ClientId("metrics-test"),
    redirectUri = redirectUri,
    scope = Set(ScopeToken.OpenId),
    codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
    codeChallengeMethod = CodeChallengeMethod.S256,
    state = None,
    userId = None,
    credential = None,
    step = versola.oauth.conversation.model.ConversationStep.Credential(Nil, false, false),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = responseType,
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = versola.oauth.client.model.AuthFlow(
      primary = versola.oauth.client.model.PrimaryAuthFlow(Nil, false, Nil),
      passkey = None,
      equivalents = Map.empty,
      otpType = versola.oauth.client.model.OtpType.email,
    ),
    userAgent = None,
    userAgentCookie = None,
    version = 0,
    amr = Map.empty,
    needsPasswordChange = false,
    targetAcr = None,
    csrfToken = "csrf",
    priorSessionId = None,
    resources = Nil,
  )

  private def count(name: String, labels: Set[MetricLabel]): UIO[Double] =
    Metric.counter(name).tagged(labels).value.map(_.count)

  def spec = suite("AuthMetrics")(
    test("records authorize outcomes with stable labels") {
      val labels = Set(MetricLabel("outcome", "error"), MetricLabel("reason", "login_required"))
      for
        before <- count("auth_authorize_total", labels)
        _ <- AuthMetrics.authorizeError("login_required")
        after <- count("auth_authorize_total", labels)
      yield assertTrue(after - before == 1.0)
    },
    test("records conversation starts and completions by kind") {
      val labels = Set(MetricLabel("kind", "login"))
      for
        beforeStarted <- count("auth_conversation_started_total", labels)
        beforeCompleted <- count("auth_conversation_completed_total", labels)
        _ <- AuthMetrics.conversationStarted(emptyRecord)
        _ <- AuthMetrics.conversationCompleted(emptyRecord)
        afterStarted <- count("auth_conversation_started_total", labels)
        afterCompleted <- count("auth_conversation_completed_total", labels)
      yield assertTrue(afterStarted - beforeStarted == 1.0, afterCompleted - beforeCompleted == 1.0)
    },
    test("records passed and failed step results") {
      val step = "metrics-test-" + UUID.randomUUID().toString
      val passed = Set(MetricLabel("step", step), MetricLabel("result", "passed"))
      val failed = Set(MetricLabel("step", step), MetricLabel("result", "failed"))
      for
        beforePassed <- count("auth_conversation_step_total", passed)
        beforeFailed <- count("auth_conversation_step_total", failed)
        _ <- AuthMetrics.stepPassed(step)
        _ <- AuthMetrics.stepFailed(step)
        afterPassed <- count("auth_conversation_step_total", passed)
        afterFailed <- count("auth_conversation_step_total", failed)
      yield assertTrue(afterPassed - beforePassed == 1.0, afterFailed - beforeFailed == 1.0)
    },
  )
