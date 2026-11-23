package versola.user

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import versola.user.model.{Login, UserId}
import versola.util.{Email, Phone}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** The claim of a credential in central's directory (versolauth/versola#1041), as `auth` sends and reads it. */
object UserDirectorySpec extends ZIOSpecDefault:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val phone = Phone("+15551234567")

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  /** A central that answers every claim with `answer`, and the body of the last claim it was sent. */
  private def central(answer: Response) =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](request => seen.set(Some(request)).as(answer)).toRoutes)
      client <- ZIO.service[Client]
    yield (client, seen)

  private def sent(seen: Ref[Option[Request]]) =
    for
      request <- seen.get.someOrFail(RuntimeException("no request captured"))
      body <- request.body.asString
      json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
    yield (request, json)

  def spec = suite("UserDirectory")(
    test("a cell claims the credential under its own cell id and reads the user and the cell that holds it") {
      for
        (client, seen) <- central(Response.json(UserDirectory.ClaimResponse(userId, Some("kz-1")).toJson))
        claim <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).claim(Right(phone))
        (request, body) <- sent(seen)
      yield assertTrue(
        claim == UserDirectory.Claim(userId, Some(TestEnvConfig.cellId)),
        request.method == Method.POST,
        request.url.path.encode.contains("users/registrations"),
        body.get("phone").contains(Json.Str(phone)),
        body.get("cell").contains(Json.Str(TestEnvConfig.cellId)),
        body.get("email").forall(_ == Json.Null),
      )
    },
    test("an auth that is no cell names none, and a user of no cell is read as such") {
      for
        (client, seen) <- central(Response.json(UserDirectory.ClaimResponse(userId, None).toJson))
        claim <- UserDirectory.Impl(TestEnvConfig.coreConfig, tokenService(client)).claim(Left(Email("user@example.com")))
        (_, body) <- sent(seen)
      yield assertTrue(
        claim == UserDirectory.Claim(userId, None),
        body.get("cell").forall(_ == Json.Null),
        body.get("email").contains(Json.Str("user@example.com")),
      )
    },
    test("the owner's cell is read even when it is another than the caller's") {
      for
        (client, _) <- central(Response.json(UserDirectory.ClaimResponse(userId, Some("kz-2")).toJson))
        claim <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).claim(Right(phone))
      yield assertTrue(claim.cell.map(identity[String]).contains("kz-2"))
    },
    // versolauth/versola#1046
    test("a lookup asks who holds the credential without naming a cell, and reads the holder's cell") {
      for
        (client, seen) <- central(Response.json(UserDirectory.ClaimResponse(userId, Some("kz-2")).toJson))
        found <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).lookup(Right(phone))
        (request, body) <- sent(seen)
      yield assertTrue(
        found.map(_.userId) == Some(userId),
        found.flatMap(_.cell).map(identity[String]) == Some("kz-2"),
        request.url.path.encode.contains("users/lookup"),
        body.get("phone").contains(Json.Str(phone)),
        body.get("cell").forall(_ == Json.Null),
      )
    },
    test("a lookup of a credential nobody holds is nobody, and any other refusal of central is a failure") {
      for
        (client, _) <- central(Response.status(Status.NotFound))
        nobody <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).lookup(Right(phone))
      yield assertTrue(nobody.isEmpty)
    },
    test("a lookup central refuses is a failure, not nobody") {
      for
        (client, _) <- central(Response.status(Status.InternalServerError))
        refused <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).lookup(Right(phone)).exit
      yield assertTrue(refused.isFailure)
    },
    test("fails when central rejects the claim, or names a cell that is not a cell id") {
      for
        (client, _) <- central(Response.status(Status.Conflict))
        rejected <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).claim(Right(phone)).exit
      yield assertTrue(rejected.isFailure)
    },
    test("fails when central names a cell that is not a cell id, rather than creating the user here") {
      for
        (client, _) <- central(Response.json(UserDirectory.ClaimResponse(userId, Some("KZ.1")).toJson))
        malformed <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).claim(Right(phone)).exit
      yield assertTrue(malformed.isFailure)
    },
    // versolauth/versola#1048
    suite("the home cell of a login or a user")(
      test("a login is sent as the login alone, and the cell central names is read") {
        for
          (client, seen) <- central(Response.json("""{"cell":"kz-2"}"""))
          home <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).homeOfLogin(Login("alice"))
          (request, body) <- sent(seen)
        yield assertTrue(
          home.map(identity[String]) == Some("kz-2"),
          request.method == Method.POST,
          request.url.path.encode.contains("users/home"),
          body.get("login").contains(Json.Str("alice")),
          body.get("userId").forall(_ == Json.Null),
        )
      },
      test("a user is sent as the user id alone, and the cell central names is read") {
        for
          (client, seen) <- central(Response.json("""{"cell":"kz-2"}"""))
          home <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).homeOfUser(userId)
          (request, body) <- sent(seen)
        yield assertTrue(
          home.map(identity[String]) == Some("kz-2"),
          request.url.path.encode.contains("users/home"),
          body.get("userId").contains(Json.Str(userId.toString)),
          body.get("login").forall(_ == Json.Null),
        )
      },
      test("a 404 is no cell, for a login and for a user") {
        for
          (client, _) <- central(Response.status(Status.NotFound))
          directory = UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client))
          ofLogin <- directory.homeOfLogin(Login("alice"))
          ofUser <- directory.homeOfUser(userId)
        yield assertTrue(ofLogin.isEmpty, ofUser.isEmpty)
      },
      test("a refusal of central is a failure, not no cell") {
        for
          (client, _) <- central(Response.status(Status.InternalServerError))
          directory = UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client))
          ofLogin <- directory.homeOfLogin(Login("alice")).exit
          ofUser <- directory.homeOfUser(userId).exit
        yield assertTrue(ofLogin.isFailure, ofUser.isFailure)
      },
      test("a cell id that is not one is a failure, not a cell") {
        for
          (client, _) <- central(Response.json("""{"cell":"KZ.1"}"""))
          directory = UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client))
          ofLogin <- directory.homeOfLogin(Login("alice")).exit
          ofUser <- directory.homeOfUser(userId).exit
        yield assertTrue(ofLogin.isFailure, ofUser.isFailure)
      },
    ),
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
