package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import versola.util.{Patch, UnitSpecBase}
import zio.json.*
import zio.test.*

object PatchChallengeSettingsRequestSpec extends UnitSpecBase:

  private val tenantId = TenantId("tenant-a")

  private val stored = ChallengeSettingsRecord(
    tenantId = tenantId,
    phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true)),
    emailRegistration = EmailRegistration.Closed,
    defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
    submissionLimits = SubmissionLimits.empty,
    otpLength = 6,
    otpResendAfter = 60,
    passkeySettings = PasskeySettings("localhost", "Test", List("http://localhost"), "preferred"),
    authConversationTtlSeconds = 900,
    sessionTtlSeconds = 86400,
    sessionIdleTtlSeconds = Some(1800),
    userAgentTtlSeconds = 15552000,
    ipHeader = "X-Real-IP",
    acrVocabulary = Some(Map("urn:acr:silver" -> List("pwd"))),
    postLogoutRedirectUris = List("https://example.com/logout"),
    requireDpopNonce = false,
    mtlsCertificateHeader = Some("ssl-client-cert"),
    mtlsCertificateEncoding = Some(MtlsCertificateEncoding.urlEncodedPem),
    signingKeyId = Some("kid-1"),
    clientAssertionMaxLifetimeSeconds = 300,
    securityProfile = SecurityProfile.fapi2,
    logoLight = Some("https://cdn.example/light.svg"),
    logoDark = Some("https://cdn.example/dark.svg"),
  )

  private def patch(members: String): Either[String, PatchChallengeSettingsRequest] =
    s"""{"tenantId":"tenant-a"${if members.isEmpty then "" else "," + members}}""".fromJson[PatchChallengeSettingsRequest]

  private def applied(members: String): Either[String, ChallengeSettingsRecord] =
    patch(members).flatMap(_.applyTo(stored))

  def spec = suite("PatchChallengeSettingsRequest")(
    suite("applyTo")(
      test("an empty patch leaves the record as it is") {
        assertTrue(applied("") == Right(stored))
      },
      test("replaces the members it names and keeps every other") {
        assertTrue(applied(""""otpLength":8""") == Right(stored.copy(otpLength = 8)))
      },
      test("replaces a nested member whole, without merging into the stored one") {
        val result = applied(""""passkeySettings":{"rpId":"example.com","rpName":"Example","origins":[],"userVerification":"required"}""")
        assertTrue(result == Right(stored.copy(passkeySettings = PasskeySettings("example.com", "Example", Nil, "required"))))
      },
      test("null clears an optional member") {
        val result = applied(""""sessionIdleTtlSeconds":null,"acrVocabulary":null,"signingKeyId":null""")
        assertTrue(result == Right(stored.copy(sessionIdleTtlSeconds = None, acrVocabulary = None, signingKeyId = None)))
      },
      test("null clears the mTLS header and encoding together") {
        val result = applied(""""mtlsCertificateHeader":null,"mtlsCertificateEncoding":null""")
        assertTrue(result == Right(stored.copy(mtlsCertificateHeader = None, mtlsCertificateEncoding = None)))
      },
      test("null clears the logo pair together") {
        val result = applied(""""logoLight":null,"logoDark":null""")
        assertTrue(result == Right(stored.copy(logoLight = None, logoDark = None)))
      },
      test("refuses null for a member that is not optional") {
        val result = applied(""""otpLength":null""")
        assertTrue(result == Left("otpLength cannot be cleared: it is not optional"))
      },
      test("refuses clearing only one of the mTLS header and encoding") {
        val result = applied(""""mtlsCertificateHeader":null""")
        assertTrue(result == Left("mtlsCertificateHeader and mtlsCertificateEncoding must be set or cleared together"))
      },
      test("refuses clearing only one of the logo pair") {
        val result = applied(""""logoDark":null""")
        assertTrue(result == Left("logoLight and logoDark must be set or cleared together"))
      },
      test("refuses a resulting record that breaks a rule, naming the rule") {
        val result = applied(""""clientAssertionMaxLifetimeSeconds":5""")
        assertTrue(result == Left("clientAssertionMaxLifetimeSeconds must be between 30 and 900"))
      },
      test("refuses a phone region the record could not be stored with") {
        val result = applied(""""phoneRegions":[{"region":"KZ","dialCode":"+7","length":10,"regex":"[7","registration":true}]""")
        assertTrue(result.left.exists(_.contains("phoneRegions regex of KZ")))
      },
      test("normalizes the email domains it stores") {
        val result = applied(""""emailRegistration":{"mode":"allowlist","domains":[" Example.COM "],"blockSubaddress":false}""")
        assertTrue(result.map(_.emailRegistration.domains) == Right(List("example.com")))
      },
      test("refuses an empty set of default roles") {
        assertTrue(applied(""""defaultRoles":[]""") == Left("defaultRoles must name at least one role"))
      },
      test("accepts naming the stored security profile") {
        assertTrue(applied(""""securityProfile":"fapi2"""") == Right(stored))
      },
      test("refuses another security profile") {
        val result = applied(""""securityProfile":"standard"""")
        assertTrue(result == Left("securityProfile of tenant 'tenant-a' is fapi2 and cannot be changed after the tenant is created"))
      },
    ),
    suite("decode")(
      test("refuses an unknown key rather than ignoring it") {
        assertTrue(patch(""""otpLenght":8""").isLeft)
      },
      test("refuses a body without tenantId") {
        assertTrue("""{"otpLength":8}""".fromJson[PatchChallengeSettingsRequest].isLeft)
      },
      test("reads absent as None, null as Deleted and a value as Modified") {
        val decoded = patch(""""otpLength":8,"signingKeyId":null""")
        assertTrue(
          decoded.map(_.otpLength) == Right(Some(Patch.Modified(8))),
          decoded.map(_.signingKeyId) == Right(Some(Patch.Deleted)),
          decoded.map(_.otpResendAfter) == Right(None),
        )
      },
    ),
  )
