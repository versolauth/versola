package versola.central.configuration

import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Callers written before `issueEdgeClientCertificate` (loadgen, e2e, the console) send a body
  * without it; it must keep meaning "do not issue". */
object CreateClientRequestJsonSpec extends ZIOSpecDefault:

  private val withoutTheField =
    """{"tenantId":"t","id":"c","clientName":{},"redirectUris":[],"allowedScopes":[],"permissions":[],
      |"accessTokenTtl":300,"theme":"d","otpTemplateId":"d","frontChannelLogoutSessionRequired":false,
      |"dpopBoundAccessTokens":false,"dpopSigningAlgs":[],"authMethod":"none","certificateBoundAccessTokens":false,
      |"requireSignedRequestObject":false,"requirePushedAuthorizationRequests":false,
      |"enrollEdgeClientCertificate":false}""".stripMargin

  def spec = suite("CreateClientRequest JSON")(
    test("a body without issueEdgeClientCertificate decodes with it false") {
      val decoded = withoutTheField.fromJson[CreateClientRequest]
      assertTrue(decoded.map(_.issueEdgeClientCertificate) == Right(false))
    },
    // #463: a new member is declared by every caller (console, loadgen, e2e), not defaulted by central.
    test("a body without enrollEdgeClientCertificate is refused") {
      val body = withoutTheField.replace(",\n\"enrollEdgeClientCertificate\":false", "")
      assertTrue(body.fromJson[CreateClientRequest].left.exists(_.contains("enrollEdgeClientCertificate")))
    },
    test("it round-trips when set") {
      val decoded = withoutTheField.fromJson[CreateClientRequest].toOption.get.copy(issueEdgeClientCertificate = true)
      assertTrue(decoded.toJson.fromJson[CreateClientRequest].map(_.issueEdgeClientCertificate) == Right(true))
    },
  )
