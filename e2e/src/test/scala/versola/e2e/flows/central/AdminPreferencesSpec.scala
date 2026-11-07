package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*


/** The administrator's own time zone: `GET` and `PATCH /users/preferences` as central serves them to an administrator whom edge has
  * identified (the `actorId` query parameter, which `asAdmin` adds as edge does). The zone is the `zoneinfo` claim of the administrator's
  * profile, so it is read back from auth's claims through central's user lookup, and no call names another user.
  */
object AdminPreferencesSpec extends E2ESpec:

  private def zoneinfoOf(users: ApiResult): Task[Option[String]] =
    users.obj.map(_.objs("users").headOption.flatMap(_.obj("claims")).flatMap(_.str("zoneinfo")))

  def spec = suite("Admin preferences: the administrator's own time zone")(
    test("a zone is stored as the zoneinfo claim of the caller's profile, read back, and cleared with null") {
      for
        (_, auth) <- setup(Flows.Id.EmailOtp)
        email <- CentralApi.email("preferences")
        userId <- auth.registerUser(email = Some(email))
        _ <- auth.flushUserOutbox()
        admin = auth.central.asAdmin(userId.toString)
        before <- admin.get("/users/preferences")
        stored <- admin.patch("/users/preferences", Json.Obj("zoneinfo" -> Json.Str("Asia/Almaty")))
        after <- admin.get("/users/preferences")
        profile <- auth.central.get("/users", "id" -> userId.toString)
        profileZone <- zoneinfoOf(profile)
        cleared <- admin.patch("/users/preferences", Json.Obj("zoneinfo" -> Json.Null))
        gone <- admin.get("/users/preferences")
        goneProfile <- auth.central.get("/users", "id" -> userId.toString)
        goneZone <- zoneinfoOf(goneProfile)
        beforeBody <- before.obj
        afterBody <- after.obj
        goneBody <- gone.obj
      yield assertTrue(
        before.status == Status.Ok,
        beforeBody.get("zoneinfo").contains(Json.Null),
        stored.status == Status.NoContent,
        afterBody.str("zoneinfo").contains("Asia/Almaty"),
        profileZone.contains("Asia/Almaty"),
        cleared.status == Status.NoContent,
        goneBody.get("zoneinfo").contains(Json.Null),
        goneZone.isEmpty,
      )
    },
    test("a name that is not in the time zone database is refused and the stored zone stays") {
      for
        (_, auth) <- setup(Flows.Id.EmailOtp)
        email <- CentralApi.email("preferences-bad")
        userId <- auth.registerUser(email = Some(email))
        _ <- auth.flushUserOutbox()
        admin = auth.central.asAdmin(userId.toString)
        _ <- admin.patch("/users/preferences", Json.Obj("zoneinfo" -> Json.Str("Europe/Berlin")))
        offset <- admin.patch("/users/preferences", Json.Obj("zoneinfo" -> Json.Str("+05:00")))
        unknown <- admin.patch("/users/preferences", Json.Obj("zoneinfo" -> Json.Str("Mars/Olympus")))
        read <- admin.get("/users/preferences")
        body <- read.obj
      yield assertTrue(
        offset.status == Status.BadRequest,
        unknown.status == Status.BadRequest,
        body.str("zoneinfo").contains("Europe/Berlin"),
      )
    },
    test("only the caller's own record is read and changed: another user named in the request is left alone") {
      for
        (_, auth) <- setup(Flows.Id.EmailOtp)
        mine <- CentralApi.email("preferences-mine").flatMap(email => auth.registerUser(email = Some(email)))
        theirs <- CentralApi.email("preferences-theirs").flatMap(email => auth.registerUser(email = Some(email)))
        _ <- auth.flushUserOutbox()
        admin = auth.central.asAdmin(mine.toString)
        _ <- admin.patch(
          "/users/preferences",
          Json.Obj("id" -> Json.Str(theirs.toString), "userId" -> Json.Str(theirs.toString), "zoneinfo" -> Json.Str("Asia/Tokyo")),
          "id" -> theirs.toString,
        )
        theirProfile <- auth.central.get("/users", "id" -> theirs.toString)
        theirZone <- zoneinfoOf(theirProfile)
        myProfile <- auth.central.get("/users", "id" -> mine.toString)
        myZone <- zoneinfoOf(myProfile)
        noActor <- auth.central.get("/users/preferences")
        anonymous <- auth.central.anonymous.get("/users/preferences")
      yield assertTrue(
        theirZone.isEmpty,
        myZone.contains("Asia/Tokyo"),
        noActor.status == Status.BadRequest,
        anonymous.status == Status.Unauthorized,
      )
    },
  )
