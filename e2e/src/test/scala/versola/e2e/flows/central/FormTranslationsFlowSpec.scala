package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.test.*

/** A form translation reaches the login page.
  *
  * `central` lays the translations stored for a form over the form's own texts when it serves the form to `auth`,
  * so a text set through `PUT /configuration/forms/translations` shows on the page without a new version of the form,
  * and goes away when the translation is deleted. The page's `<title>` is the form's `page_title` text, which makes
  * it an easy thing to read back. `credential` and `ru` are built in; the translation is deleted afterwards.
  */
object FormTranslationsFlowSpec extends E2ESpec:

  private val translated = "Вход (e2e)"

  private val title = """<title>([^<]*)</title>""".r

  /** The title of the sign-in page `auth` serves a client in Russian. */
  private def pageTitle(s: Flows.Setup, auth: OAuthClient): Task[Option[String]] =
    for
      authorize <- auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, uiLocales = Some("ru")).assertChallengeRedirect
      page <- auth.getChallenge(authorize.conversationCookie.get)
    yield title.findFirstMatchIn(page.html).map(_.group(1))

  /** Syncs `auth` and reads the title until it is `expected`; the last answer is returned on a timeout, so a failure
    * shows what the page said rather than a bare timeout. `auth` takes the forms from `central` on a sync. */
  private def titleBecomes(s: Flows.Setup, auth: OAuthClient, expected: String): Task[Option[String]] =
    (auth.syncConfiguration() *> pageTitle(s, auth))
      .repeat(Schedule.spaced(200.millis) *> Schedule.recurUntil[Option[String]](_.contains(expected)))
      .timeout(15.seconds)
      .someOrElseZIO(pageTitle(s, auth))
      .withClock(Clock.ClockLive)

  def spec = suite("A form translation reaches the login page")(
    test("a text set through the API shows on the page, and the form's own text returns when it is deleted") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        before <- pageTitle(s, auth)
        result <- (
          for
            put <- auth.putFormTranslation("credential", "ru", Map("page_title" -> translated))
            during <- titleBecomes(s, auth, translated)
            removed <- auth.deleteFormTranslation("credential", "ru")
            after <- titleBecomes(s, auth, before.getOrElse("Вход"))
          yield assertTrue(
            put.status == Status.NoContent,
            before.exists(_ != translated),
            during.contains(translated),
            removed.status == Status.NoContent,
            after == before,
          )
        ).ensuring(auth.deleteFormTranslation("credential", "ru").ignore)
      yield result
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(90.seconds)
