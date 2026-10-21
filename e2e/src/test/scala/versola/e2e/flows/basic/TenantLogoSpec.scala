package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.test.*

/** The logo pair a tenant sets in central is what its login pages show above the card.
  *
  * The unit tests prove the validation and the rendering; this proves the pair survives the wiring: the two columns
  * central stores, the sync channel to auth, the tenant lookup through the client, and the form configuration and
  * favicons the credential step is rendered with.
  *
  * The logo is tenant-wide state the rest of the suite renders pages with, so every test puts the tenant back on no
  * logo -- and the tests run one after another, because they all write that same state.
  */
object TenantLogoSpec extends E2ESpec:

  private val light = "https://assets.example/acme-light.svg"
  private val dark = "https://assets.example/acme-dark.png"

  private def withLogo[A](auth: OAuthClient, logoLight: Option[String], logoDark: Option[String])(use: => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      auth.upsertChallengeSettings(logoLight = logoLight, logoDark = logoDark) *> auth.syncConfiguration(),
    )(_ => (auth.upsertChallengeSettings() *> auth.syncConfiguration()).orDie)(_ => use)

  private def credentialPage(auth: OAuthClient, s: Flows.Setup) =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
    yield challenge.html

  def spec = suite("Tenant logo")(
    test("the credential step carries the tenant's pair in its form config and as favicons per scheme") {
      for
        (s, auth) <- setup(Flows.Id.PhoneOtp)
        html <- withLogo(auth, Some(light), Some(dark))(credentialPage(auth, s))
      yield assertTrue(
        html.contains(s""""logo":{"light":"$light","dark":"$dark"}"""),
        html.contains(s"""<link rel="icon" href="$light" media="(prefers-color-scheme: light)">"""),
        html.contains(s"""<link rel="icon" href="$dark" media="(prefers-color-scheme: dark)">"""),
      )
    },
    test("a tenant without a logo gets the Versola wordmark: no logo in the config, the default favicon") {
      for
        (s, auth) <- setup(Flows.Id.PhoneOtp)
        html <- withLogo(auth, None, None)(credentialPage(auth, s))
      yield assertTrue(!html.contains(""""logo":{"""), !html.contains("""rel="icon" href="https://"""))
    },
    test("central refuses a single file and a URL that is not http(s), and keeps the stored pair") {
      for
        (s, auth) <- setup(Flows.Id.PhoneOtp)
        result <- withLogo(auth, Some(light), Some(dark)) {
          for
            single <- auth.upsertChallengeSettings(logoLight = Some(light), logoDark = None).either
            relative <- auth.upsertChallengeSettings(logoLight = Some("/logo.svg"), logoDark = Some(dark)).either
            _ <- auth.syncConfiguration()
            html <- credentialPage(auth, s)
          yield (single, relative, html)
        }
      yield assertTrue(
        result._1.left.exists(_.getMessage.contains("BadRequest")),
        result._2.left.exists(_.getMessage.contains("BadRequest")),
        result._3.contains(s""""logo":{"light":"$light","dark":"$dark"}"""),
      )
    },
  ) @@ TestAspect.sequential
