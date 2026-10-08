package versola.central.configuration

import zio.json.*
import zio.test.*

/** The edge-certificate members are declared by every caller (console, loadgen, e2e), not
  * defaulted by central: a body that leaves one out is refused, so a caller cannot register a
  * client with a certificate mode it never chose. */
object CreateClientRequestJsonSpec extends ZIOSpecDefault:

  private val complete =
    """{"tenantId":"t","id":"c","clientName":{},"redirectUris":[],"allowedScopes":[],"permissions":[],
      |"accessTokenTtl":300,"theme":"d","otpTemplateId":"d","frontChannelLogoutSessionRequired":false,
      |"dpopBoundAccessTokens":false,"dpopSigningAlgs":[],"authMethod":"none","certificateBoundAccessTokens":false,
      |"requireSignedRequestObject":false,"requirePushedAuthorizationRequests":false,
      |"issueEdgeClientCertificate":false,"enrollEdgeClientCertificate":false}""".stripMargin

  private def without(member: String): String =
    complete.replace(s""","$member":false""", "").replace(s""""$member":false,""", "")

  def spec = suite("CreateClientRequest JSON")(
    test("a body that declares every member decodes") {
      val decoded = complete.fromJson[CreateClientRequest]
      assertTrue(
        decoded.map(_.issueEdgeClientCertificate) == Right(false),
        decoded.map(_.enrollEdgeClientCertificate) == Right(false),
      )
    },
    test("a body without issueEdgeClientCertificate is refused") {
      assertTrue(without("issueEdgeClientCertificate").fromJson[CreateClientRequest].left.exists(_.contains("issueEdgeClientCertificate")))
    },
    test("a body without enrollEdgeClientCertificate is refused") {
      assertTrue(without("enrollEdgeClientCertificate").fromJson[CreateClientRequest].left.exists(_.contains("enrollEdgeClientCertificate")))
    },
    test("it round-trips when set") {
      val decoded = complete.fromJson[CreateClientRequest].toOption.get
        .copy(issueEdgeClientCertificate = true, enrollEdgeClientCertificate = true)
      assertTrue(
        decoded.toJson.fromJson[CreateClientRequest].map(r => (r.issueEdgeClientCertificate, r.enrollEdgeClientCertificate)) == Right((true, true)),
      )
    },
  )
