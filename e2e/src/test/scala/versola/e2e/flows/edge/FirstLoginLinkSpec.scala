package versola.e2e.flows.edge

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Client, Header}
import zio.test.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.{DriverManager, Timestamp}
import java.time.Instant
import java.util.Base64

/** #824: the first-login link of the bootstrap admin, end to end -- edge's `/login/{preset}?link_token=`
  * reaches auth's `/authorize` (through PAR, which `central-admin` requires), the real `one_time_links` table is
  * consulted, and the conversation that starts is the "set a permanent password" step.
  *
  * The token is written the way auth stores it, as its SHA-256, so the spec holds a token it knows without reading
  * auth's log. The row is removed afterwards. Nothing here sets a permanent password; the spec relies on the staged
  * stack's admin not having one (a permanent password makes auth refuse the token). It writes to the auth database
  * named by `E2E_DB_*`, which must be the throwaway stack's.
  */
object FirstLoginLinkSpec extends ZIOSpec[EdgeApi & OAuthClient]:
  override val bootstrap: ZLayer[Any, Any, EdgeApi & OAuthClient] =
    (E2EConfig.live ++ Client.default) >>> (EdgeApi.live ++ OAuthClient.live)

  private val presetId = "central-admin"

  /** What a browser gets by following the edge's redirect to `/authorize`: the step of the conversation it starts. */
  private def stepAfterLogin(params: (String, String)*): ZIO[EdgeApi & OAuthClient, Throwable, ConversationStep] =
    for
      edgeApi <- ZIO.service[EdgeApi]
      auth <- ZIO.service[OAuthClient]
      started <- edgeApi.login(presetId, params*)
      authorizeUrl <- ZIO.fromOption(started.header(Header.Location).map(_.url.encode))
        .orElseFail(RuntimeException(s"/login/$presetId did not redirect (status=${started.status})"))
      authorized <- auth.probe(zio.http.Method.GET, authorizeUrl)
      conversation <- ZIO.fromOption(
        authorized.headers.getAll(Header.SetCookie)
          .collectFirst { case h if h.value.name == "SSO_CONVERSATION" => h.value.content },
      )
        .orElseFail(RuntimeException(s"the OP started no conversation (status=${authorized.status})"))
      challenge <- auth.getChallenge(conversation)
      step <- ZIO.fromOption(challenge.step)
        .orElseFail(RuntimeException("the challenge page carries no versola-step"))
    yield step

  private def newToken: UIO[String] =
    ZIO.succeed:
      val bytes = new Array[Byte](32)
      java.security.SecureRandom().nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def database[A](use: java.sql.Connection => A): Task[A] =
    for
      url <- System.env("E2E_DB_URL").map(_.getOrElse("jdbc:postgresql://localhost:5432/auth"))
      user <- System.env("E2E_DB_USER").map(_.getOrElse("dev"))
      password <- System.env("E2E_DB_PASSWORD").map(_.getOrElse("1234"))
      result <- ZIO.attemptBlocking:
        val connection = DriverManager.getConnection(url, user, password).nn
        try use(connection)
        finally connection.close()
    yield result

  /** Stores the token for the bootstrap admin and removes it when the test is over, whether or not the link was
    * opened. A plain insert: if the admin already holds a `first_login` link (a stack that was just bootstrapped and
    * is not a throwaway one) the insert fails and the real link is left alone, instead of being replaced and then
    * deleted. */
  private def issued(login: String, token: String): ZIO[Scope, Throwable, Unit] =
    val hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
    val store = database { connection =>
      val now = Instant.now()
      val statement = connection.prepareStatement(
        """INSERT INTO one_time_links (user_id, purpose, token_hash, created_at, expires_at)
          |SELECT id, 'first_login', ?, ?, ? FROM users WHERE login = ?""".stripMargin,
      ).nn
      try
        statement.setBytes(1, hash)
        statement.setTimestamp(2, Timestamp.from(now))
        statement.setTimestamp(3, Timestamp.from(now.plusSeconds(3600)))
        statement.setString(4, login)
        statement.executeUpdate()
      finally statement.close()
    }
    ZIO.acquireRelease(store)(_ => remove(hash).ignore).flatMap(updated =>
      ZIO.fail(RuntimeException(s"expected to store a token for the user '$login', stored $updated"))
        .unless(updated == 1).unit,
    )

  private def remove(hash: Array[Byte]): Task[Int] =
    database: connection =>
      val statement = connection.prepareStatement("DELETE FROM one_time_links WHERE token_hash = ?").nn
      try
        statement.setBytes(1, hash)
        statement.executeUpdate()
      finally statement.close()

  def spec = suite("first-login link")(
    test("an unknown token behaves exactly like no token: an ordinary login, no error") {
      for
        plain <- stepAfterLogin()
        unknown <- stepAfterLogin("link_token" -> "not-a-token-auth-ever-issued")
      yield assertTrue(unknown == plain, unknown != ConversationStep.SetPassword, unknown != ConversationStep.AccessDenied)
    },
    // What follows the passed password step depends on the stack's `central-admin` flow: the set-password step on
    // a flow of the password alone, the next factor (here an OTP) on a flow that asks for more. Either way the
    // token has done its job when the login does not start at the credential or the password step.
    test("the issued token skips the password step once; the same link is then an ordinary login") {
      for
        login <- System.env("E2E_LOGIN").map(_.getOrElse("admin"))
        token <- newToken
        plain <- stepAfterLogin()
        result <- ZIO.scoped:
          for
            _ <- issued(login, token)
            first <- stepAfterLogin("link_token" -> token)
            second <- stepAfterLogin("link_token" -> token)
          yield (first, second)
      yield assertTrue(
        result._1 != plain,
        result._1 != ConversationStep.Credential,
        result._1 != ConversationStep.Password,
        result._1 != ConversationStep.AccessDenied,
        result._2 == plain,
      )
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
