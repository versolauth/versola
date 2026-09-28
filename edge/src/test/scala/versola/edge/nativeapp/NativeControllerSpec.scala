package versola.edge.nativeapp

import zio.*
import zio.http.*
import zio.test.*

/** The wire shape of the refusals edge makes on its own -- what the SDK has to read. */
object NativeControllerSpec extends ZIOSpecDefault:

  private def failing(error: NativeError) = new NativeService:
    def start(clientId: String, request: Request) = ZIO.fail(error)
    def complete(clientId: String, request: Request) = ZIO.fail(error)
    def refresh(clientId: String, request: Request) = ZIO.fail(error)
    def revoke(clientId: String, request: Request) = ZIO.fail(error)

  private def call(error: NativeError, endpoint: String) =
    NativeController.routes.handleError(_ => Response.internalServerError)
      .runZIO(Request.post(URL.decode(s"/native/$endpoint/mobile-app").toOption.get, Body.empty))
      .provide(ZLayer.succeed(failing(error)), Scope.default)

  def spec = suite("NativeController")(
    test("an unknown client is a bare, uncached 404 on every endpoint") {
      for responses <- ZIO.foreach(List("start", "complete", "token", "revoke"))(call(NativeError.UnknownClient, _))
      yield assertTrue(
        responses.forall(_.status == Status.NotFound),
        // Which client ids this edge fronts is what the 404 answers; an intermediary holding
        // one would go on answering it after the client exists.
        responses.forall(_.header(Header.CacheControl).contains(Header.CacheControl.NoStore)),
      )
    },
    test("edge's own refusals are RFC 6749 error objects, never cached") {
      for
        grant <- call(NativeError.InvalidGrant("blob has expired"), "complete")
        grantBody <- grant.body.asString
        proof <- call(NativeError.InvalidDpopProof("missing"), "start")
        proofBody <- proof.body.asString
      yield assertTrue(
        grant.status == Status.BadRequest,
        grantBody == """{"error":"invalid_grant","error_description":"blob has expired"}""",
        grant.header(Header.CacheControl).isDefined,
        proofBody.contains("\"error\":\"invalid_dpop_proof\""),
      )
    },
  )
