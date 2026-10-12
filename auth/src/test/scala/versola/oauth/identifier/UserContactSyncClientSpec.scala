package versola.oauth.identifier

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import versola.user.model.UserId
import versola.util.{Email, Phone}
import zio.*
import zio.http.*
import zio.test.*

import java.util.UUID

object UserContactSyncClientSpec extends ZIOSpecDefault:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val version = UUID.fromString("018f0000-0000-7000-8000-000000000002")

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  private def answering(response: Response) =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request](request => seen.set(Some(request)).as(response)).toRoutes,
      )
      client <- ZIO.service[Client]
    yield (seen, UserContactSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client)))

  def spec = suite("UserContactSyncClient")(
    test("sets a phone in central and returns the version of the change") {
      for
        (seen, service) <- answering(Response.json(s"""{"version":"$version"}"""))
        result <- service.set(userId, Right(Phone("+77011234567")))
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
        body <- request.body.asString
      yield assertTrue(
        result == UserContactResult.Updated(version),
        request.method == Method.PUT,
        request.url.path.encode.endsWith("users/phone"),
        body == s"""{"userId":"$userId","phone":"+77011234567"}""",
      )
    },
    test("sets an email in central") {
      for
        (seen, service) <- answering(Response.json(s"""{"version":"$version"}"""))
        result <- service.set(userId, Left(Email("new@example.com")))
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
        body <- request.body.asString
      yield assertTrue(
        result == UserContactResult.Updated(version),
        body == s"""{"userId":"$userId","email":"new@example.com"}""",
        request.method == Method.PUT,
        request.url.path.encode.endsWith("users/email"),
      )
    },
    test("reports an identifier another user holds") {
      for
        (_, service) <- answering(Response.status(Status.Conflict))
        result <- service.set(userId, Right(Phone("+77011234567")))
      yield assertTrue(result == UserContactResult.AlreadyInUse)
    },
    test("reports a user central does not know") {
      for
        (_, service) <- answering(Response.status(Status.NotFound))
        result <- service.set(userId, Right(Phone("+77011234567")))
      yield assertTrue(result == UserContactResult.UserNotFound)
    },
    test("fails, rather than guesses, when central answers with an error") {
      for
        (_, service) <- answering(Response.status(Status.InternalServerError))
        exit <- service.set(userId, Right(Phone("+77011234567"))).exit
      yield assertTrue(exit.isFailure)
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
