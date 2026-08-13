package versola.e2e.support

import zio.*
import zio.json.ast.Json

import java.util.UUID

/** Reusable e2e flow helpers shared across specs.
  *
  * Auth-flow JSON configs describe how a client authenticates users.
  * Setup helpers create an isolated client + user per test run.
  */
object Flows:

  // ── Auth-flow configurations ────────────────────────────────────────────

  /** Login credential with inline password, no additional factors. */
  val loginPasswordAuthFlow: Json = Json.Obj(
    "primary" -> Json.Obj(
      "credentials"    -> Json.Arr(Json.Str("login")),
      "inlinePassword" -> Json.Bool(true),
      "factors"        -> Json.Arr(),
    ),
    "passkey"     -> Json.Null,
    "equivalents" -> Json.Obj(),
    "otpType"     -> Json.Str("sms"),
  )

  /** Email credential with OTP factor, no inline password. */
  val emailOtpAuthFlow: Json = Json.Obj(
    "primary" -> Json.Obj(
      "credentials"    -> Json.Arr(Json.Str("email")),
      "inlinePassword" -> Json.Bool(false),
      "factors"        -> Json.Arr(Json.Obj("type" -> Json.Str("otp"), "required" -> Json.Bool(true))),
    ),
    "passkey"     -> Json.Null,
    "equivalents" -> Json.Obj(),
    "otpType"     -> Json.Str("email"),
  )

  /** Phone credential with OTP factor, no inline password. */
  val phoneOtpAuthFlow: Json = Json.Obj(
    "primary" -> Json.Obj(
      "credentials"    -> Json.Arr(Json.Str("phone")),
      "inlinePassword" -> Json.Bool(false),
      "factors"        -> Json.Arr(Json.Obj("type" -> Json.Str("otp"), "required" -> Json.Bool(true))),
    ),
    "passkey"     -> Json.Null,
    "equivalents" -> Json.Obj(),
    "otpType"     -> Json.Str("sms"),
  )

  /** Registration entered from the phone credential card: prove the number, then pick a password. */
  val otpPasswordRegistrationFlow: Json = Json.Obj(
      "credential" -> Json.Str("phone"),
    "steps" -> Json.Arr(
      Json.Obj("type" -> Json.Str("otp")),
      Json.Obj("type" -> Json.Str("setPassword")),
    ),
      "roleIds" -> Json.Arr(Json.Str("user")),
  )

  /** Registration entered from the email credential card: prove the address, then pick a password. */
  val emailOtpPasswordRegistrationFlow: Json = Json.Obj(
    "credential" -> Json.Str("email"),
    "steps" -> Json.Arr(
      Json.Obj("type" -> Json.Str("otp")),
      Json.Obj("type" -> Json.Str("setPassword")),
    ),
    "roleIds" -> Json.Arr(Json.Str("user")),
  )

  // ── Setup result ────────────────────────────────────────────────────────

  /** Everything a test needs to run a flow against a freshly-created client and user. */
  case class Setup(
      clientId: String,
      clientSecret: String,
      redirectUri: String,
      userId: UUID,
      /** Login handle (`login` credential flow). */
      login: Option[String],
      /** Email address (`email` credential flow). */
      email: Option[String],
      /** Phone number (`phone` credential flow). */
      phone: Option[String],
      password: String,
  )

  // ── Setup helpers ───────────────────────────────────────────────────────

  /** Register a client (login + inline password) and a matching user with a permanent password. */
  def setupLoginPassword(redirectUri: String = "http://localhost:3000"): RIO[OAuthClient, Setup] =
    val uid      = UUID.randomUUID().toString.replace("-", "").take(8)
    val login    = s"user-$uid"
    val password = s"Pass-$uid-1!"
    val clientId = s"login-client-$uid"
    for
      oauthClient  <- ZIO.service[OAuthClient]
      clientResult <- oauthClient.registerClient(
        clientId,
        "Login Test Client",
        Set(redirectUri),
        authFlow = Some(loginPasswordAuthFlow),
      ).success
      userId <- oauthClient.registerUser(login = Some(login))
      _      <- oauthClient.flushUserOutbox()
      _      <- oauthClient.setUserPassword(userId, password)
    yield Setup(clientId, clientResult.secret, redirectUri, userId, Some(login), None, None, password)

  /** Register a client (email + OTP) and a matching user with a permanent password. */
  def setupEmailOtp(redirectUri: String = "http://localhost:3000"): RIO[OAuthClient, Setup] =
    val uid      = UUID.randomUUID().toString.replace("-", "").take(8)
    val email    = s"otp-$uid@example.test"
    val password = s"Pass-$uid-1!"
    val clientId = s"otp-client-$uid"
    for
      oauthClient  <- ZIO.service[OAuthClient]
      clientResult <- oauthClient.registerClient(
        clientId,
        "OTP Test Client",
        Set(redirectUri),
        allowedScopes = Set("openid", "email", "offline_access"),
        authFlow = Some(emailOtpAuthFlow),
      ).success
      userId <- oauthClient.registerUser(email = Some(email))
      _      <- oauthClient.flushUserOutbox()
      _      <- oauthClient.setUserPassword(userId, password)
    yield Setup(clientId, clientResult.secret, redirectUri, userId, None, Some(email), None, password)

  /** Register a client (phone + OTP) and a matching user with a permanent password. */
  def setupPhoneOtp(redirectUri: String = "http://localhost:3000"): RIO[OAuthClient, Setup] =
    val uid      = UUID.randomUUID()
    val uidStr   = uid.toString.replace("-", "")
    val phone    = f"+49151${uid.getLeastSignificantBits.abs % 100_000_000L}%08d"
    val password = s"Pass-${uidStr.take(8)}-1!"
    val clientId = s"phone-client-${uidStr.take(8)}"
    for
      oauthClient  <- ZIO.service[OAuthClient]
      clientResult <- oauthClient.registerClient(
        clientId,
        "Phone OTP Test Client",
        Set(redirectUri),
        authFlow = Some(phoneOtpAuthFlow),
      ).success
      userId <- oauthClient.registerUser(phone = Some(phone))
      _      <- oauthClient.flushUserOutbox()
      _      <- oauthClient.setUserPassword(userId, password)
    yield Setup(clientId, clientResult.secret, redirectUri, userId, None, None, Some(phone), password)

  /** Register a client that offers self-service registration next to phone + OTP sign-in.
    *
    * No user is created: registration tests create their own account through the flow, and
    * `Setup.phone` carries an unused number they can claim.
    */
  def setupPhoneRegistration(redirectUri: String = "http://localhost:3000"): RIO[OAuthClient, Setup] =
    val uid = UUID.randomUUID()
    val uidStr = uid.toString.replace("-", "")
    val phone = f"+49152${uid.getLeastSignificantBits.abs % 100_000_000L}%08d"
    val password = s"Pass-${uidStr.take(8)}-1!"
    val clientId = s"registration-client-${uidStr.take(8)}"
    for
      oauthClient <- ZIO.service[OAuthClient]
      clientResult <- oauthClient.registerClient(
        clientId,
        "Registration Test Client",
        Set(redirectUri),
        authFlow = Some(phoneOtpAuthFlow),
        registrationFlow = Some(otpPasswordRegistrationFlow),
      ).success
    yield Setup(clientId, clientResult.secret, redirectUri, new UUID(0L, 0L), None, None, Some(phone), password)

  /** Register a client that offers self-service registration next to email + OTP sign-in. */
  def setupEmailRegistration(redirectUri: String = "http://localhost:3000"): RIO[OAuthClient, Setup] =
    val uid = UUID.randomUUID().toString.replace("-", "")
    val email = s"registration-${uid.take(12)}@example.test"
    val password = s"Pass-${uid.take(8)}-1!"
    val clientId = s"email-registration-client-${uid.take(8)}"
    for
      oauthClient <- ZIO.service[OAuthClient]
      clientResult <- oauthClient.registerClient(
        clientId,
        "Email Registration Test Client",
        Set(redirectUri),
        authFlow = Some(emailOtpAuthFlow),
        registrationFlow = Some(emailOtpPasswordRegistrationFlow),
      ).success
    yield Setup(clientId, clientResult.secret, redirectUri, new UUID(0L, 0L), None, Some(email), None, password)

  // ── Multi-setup helpers ─────────────────────────────────────────────────

  /** Identifies a registered auth flow variant in the shared bootstrap data. */
  enum Id:
    case LoginPassword, EmailOtp, PhoneOtp, PhoneRegistration, EmailRegistration

  /** All shared test data for the e2e suite. */
  case class Setups(setups: Map[Id, Setup], client: OAuthClient):
    def apply(id: Id): Setup =
      setups.getOrElse(id, throw new NoSuchElementException(s"No setup registered for flow $id"))

  /** `ZLayer` that registers all required clients/users, flushes the Central
    * outbox, and syncs auth configuration — used as the e2e bootstrap.
    */
  val layer: ZLayer[OAuthClient, Throwable, Setups] =
    ZLayer.fromZIO:
      for
        client  <- ZIO.service[OAuthClient]
        lp      <- setupLoginPassword()
        otp     <- setupEmailOtp()
        phoneOtp <- setupPhoneOtp()
        registration <- setupPhoneRegistration()
        emailRegistration <- setupEmailRegistration()
        _       <- client.flushUserOutbox()
        _       <- client.upsertChallengeSettings(
          acrVocabulary = Map(Acr.OtpLevel -> List("otp"), Acr.PasswordLevel -> List("password"), Acr.PasskeyLevel -> List("passkey")),
        )
        _       <- client.syncConfiguration()
      yield Setups(
        Map(
          Id.LoginPassword -> lp,
          Id.EmailOtp -> otp,
          Id.PhoneOtp -> phoneOtp,
          Id.PhoneRegistration -> registration,
          Id.EmailRegistration -> emailRegistration,
        ),
        client,
      )
