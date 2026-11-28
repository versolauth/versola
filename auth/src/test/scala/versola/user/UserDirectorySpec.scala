package versola.user

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import versola.oauth.session.model.{PublicSessionId, SupersededSession}
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
    override def liveRequest(request: Request): ZIO[Scope, Throwable, Response] = syncRequest(request)
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
    // versolauth/versola#1052
    test("the home of an address is asked by the address alone, for a phone and for an email, and the cell is read") {
      for
        (client, seen) <- central(Response.json(UserDirectory.HomeResponse("kz-2").toJson))
        directory = UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client))
        byPhone <- directory.homeOfAddress(Right(phone))
        (phoneRequest, phoneBody) <- sent(seen)
        byEmail <- directory.homeOfAddress(Left(Email("user@example.com")))
        (emailRequest, emailBody) <- sent(seen)
      yield assertTrue(
        byPhone.map(identity[String]) == Some("kz-2"),
        byEmail.map(identity[String]) == Some("kz-2"),
        phoneRequest.url.path.encode.contains("users/home"),
        emailRequest.url.path.encode.contains("users/home"),
        phoneBody.get("phone").contains(Json.Str(phone)),
        phoneBody.get("email").forall(_ == Json.Null),
        emailBody.get("email").contains(Json.Str("user@example.com")),
        emailBody.get("phone").forall(_ == Json.Null),
        phoneBody.get("login").forall(_ == Json.Null) && phoneBody.get("userId").forall(_ == Json.Null),
      )
    },
    test("an address central names no cell for is nobody's") {
      for
        (client, _) <- central(Response.status(Status.NotFound))
        nobody <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).homeOfAddress(Right(phone))
      yield assertTrue(nobody.isEmpty)
    },
    test("an address central refuses is a failure, not nobody") {
      for
        (client, _) <- central(Response.status(Status.InternalServerError))
        refused <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).homeOfAddress(Right(phone)).exit
      yield assertTrue(refused.isFailure)
    },
    test("a cell id of central's answer this build cannot read is a failure, not nobody") {
      for
        (client, _) <- central(Response.json(UserDirectory.HomeResponse("not a cell").toJson))
        unreadable <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).homeOfAddress(Right(phone)).exit
      yield assertTrue(unreadable.isFailure)
    },
    test("fails when central rejects the claim, or names a cell that is not a cell id") {
      for
        (client, _) <- central(Response.status(Status.Conflict))
        rejected <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).claim(Right(phone)).exit
      yield assertTrue(rejected.isFailure)
    },
    // versolauth/versola#1052
    test("a claim central refuses because this cell does not take users fails naming the cell that does") {
      for
        (client, _) <- central(Response.json("""{"error":"cell_not_taking","cell":"kz-2"}""").status(Status.Conflict))
        refused <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).claim(Right(phone)).either
      yield assertTrue(refused.left.exists {
        case UserDirectory.CellNotTaking(cell) => (cell: String) == "kz-2"
        case _ => false
      })
    },
    test("a claim central refuses because no cell takes users fails as that") {
      for
        (client, _) <- central(Response.json("""{"error":"no_cell_takes_users"}""").status(Status.Conflict))
        refused <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).claim(Right(phone)).either
      yield assertTrue(refused == Left(UserDirectory.NoCellTakesUsers))
    },
    test("a refusal that names a cell that is not a cell id, or says something else, is a failure and not a placement") {
      for
        (malformedClient, _) <- central(Response.json("""{"error":"cell_not_taking","cell":"KZ.1"}""").status(Status.Conflict))
        malformed <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(malformedClient)).claim(Right(phone)).either
        (otherClient, _) <- central(Response.json("""{"error":"something_else"}""").status(Status.Conflict))
        other <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(otherClient)).claim(Right(phone)).either
        (missingClient, _) <- central(Response.json("""{"error":"cell_not_taking"}""").status(Status.Conflict))
        missing <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(missingClient)).claim(Right(phone)).either
      yield assertTrue(
        malformed.isLeft && !malformed.left.exists(_.isInstanceOf[UserDirectory.CellNotTaking]),
        other.isLeft && !other.left.exists(_.isInstanceOf[UserDirectory.CellNotTaking]),
        missing.isLeft && !missing.left.exists(_.isInstanceOf[UserDirectory.CellNotTaking]),
      )
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
    // versolauth/versola#1048: the session a handed sign-in replaced, ended in the cell that holds it.
    suite("sessionSuperseded")(
      test("posts the user and the session to central's superseded endpoint") {
        val session = SupersededSession(userId = userId, sessionId = PublicSessionId("kz-1.session-1"))
        for
          (client, seen) <- central(Response.status(Status.NoContent))
          _ <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).sessionSuperseded(session)
          (request, body) <- sent(seen)
        yield assertTrue(
          request.method == Method.POST,
          request.url.path.encode.endsWith("users/sessions/superseded"),
          body == Json.Obj("userId" -> Json.Str(userId.toString), "sessionId" -> Json.Str("kz-1.session-1")),
        )
      },
      test("is done when central answers 204, or 404 for a session that is gone already") {
        val session = SupersededSession(userId = userId, sessionId = PublicSessionId("kz-1.session-1"))
        for
          (noContent, _) <- central(Response.status(Status.NoContent))
          ended <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(noContent)).sessionSuperseded(session).exit
          (gone, _) <- central(Response.status(Status.NotFound))
          alreadyGone <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(gone)).sessionSuperseded(session).exit
        yield assertTrue(ended.isSuccess, alreadyGone.isSuccess)
      },
      test("fails when central answers 500, so the caller can say the session was left to expire") {
        val session = SupersededSession(userId = userId, sessionId = PublicSessionId("kz-1.session-1"))
        for
          (client, _) <- central(Response.status(Status.InternalServerError))
          refused <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).sessionSuperseded(session).exit
        yield assertTrue(refused.isFailure)
      },
      test("fails when central refuses the request, as it does one it does not authorize") {
        val session = SupersededSession(userId = userId, sessionId = PublicSessionId("kz-1.session-1"))
        for
          (client, _) <- central(Response.status(Status.Unauthorized))
          refused <- UserDirectory.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).sessionSuperseded(session).exit
        yield assertTrue(refused.isFailure)
      },
    ),
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
