package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** The user index on central's admin API.
  *
  * Central holds a searchable index of identifiers — email, phone, login — that maps to the
  * user record auth owns. Writes to it answer `202 Accepted` and land through the user outbox,
  * so anything these tests read back has to be waited for rather than assumed.
  */
object UserApiSpec extends CentralApiSpec:

  private val path = "/users"

  private def find(central: CentralApi, by: (String, String)): Task[Chunk[Json.Obj]] =
    central.get(path, by).flatMap(_.items("users"))

  private def byId(central: CentralApi, userId: String): Task[Option[Json.Obj]] =
    find(central, "id" -> userId).map(_.headOption)

  /** Polls the index until the record satisfies `condition`.
    *
    * Every write here is asynchronous, so a single read after a write would be a race: it
    * would usually pass on an idle machine and fail under load, which is the worst kind of
    * test. The timeout is what turns "not yet" into a real failure.
    */
  private def eventually(central: CentralApi, userId: String)(
      condition: Json.Obj => Boolean,
  ): Task[Option[Json.Obj]] =
    byId(central, userId)
      .repeat(Schedule.spaced(100.millis) *> Schedule.recurUntil[Option[Json.Obj]](_.exists(condition)))
      .timeout(10.seconds)
      .map(_.flatten)

  private def create(central: CentralApi, body: Json.Obj): Task[String] =
    central.post(path, body).flatMap(_.stringAt("id"))

  /** Creates a user and waits until auth knows about them, which is what claim writes need. */
  private def createSynced(central: CentralApi, body: Json.Obj): Task[String] =
    create(central, body).tap(_ => central.flushUserOutbox)

  def spec = suite("Central API: users")(
    test("a created user answers the id the rest of the system knows them by") {
      for
        central <- api
        login <- CentralApi.login("probe")
        created <- central.post(path, Fixtures.user(login = Some(login)))
        id <- created.stringAt("id")
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(scala.util.Try(java.util.UUID.fromString(id)).isSuccess)
          .label("every other user endpoint takes this id as a UUID, so creation has to answer one")
    },
    test("a user created with all three identifiers is indexed under each of them") {
      for
        central <- api
        email <- CentralApi.email("probe")
        phone <- CentralApi.phone
        login <- CentralApi.login("probe")
        id <- create(central, Fixtures.user(Some(email), Some(phone), Some(login)))
        byEmail <- find(central, "email" -> email)
        byPhone <- find(central, "phone" -> phone)
        byLogin <- find(central, "login" -> login)
      yield assertTrue(byEmail.flatMap(_.str("id")).contains(id)) &&
        assertTrue(byPhone.flatMap(_.str("id")).contains(id)) &&
        assertTrue(byLogin.flatMap(_.str("id")).contains(id))
          .label("a user signs in with whichever identifier they remember, so all three must resolve")
    },
    test("a user found by id reads back with the identifiers they were created with") {
      for
        central <- api
        email <- CentralApi.email("probe")
        login <- CentralApi.login("probe")
        id <- create(central, Fixtures.user(email = Some(email), login = Some(login)))
        record <- byId(central, id)
      yield assertTrue(record.flatMap(_.str("email")).contains(email)) &&
        assertTrue(record.flatMap(_.str("login")).contains(login))
    },
    test("a user created with only one identifier reports the others as absent") {
      for
        central <- api
        login <- CentralApi.login("probe")
        id <- create(central, Fixtures.user(login = Some(login)))
        record <- byId(central, id)
      yield assertTrue(record.flatMap(_.str("login")).contains(login)) &&
        assertTrue(record.flatMap(_.str("email")).isEmpty) &&
        assertTrue(record.flatMap(_.str("phone")).isEmpty)
    },
    test("a new user carries no claims") {
      for
        central <- api
        login <- CentralApi.login("probe")
        id <- create(central, Fixtures.user(login = Some(login)))
        record <- byId(central, id)
      yield assertTrue(record.flatMap(_.obj("claims")).map(_.fields.isEmpty).contains(true))
    },
    test("a second user cannot claim an email already indexed") {
      for
        central <- api
        email <- CentralApi.email("probe")
        _ <- create(central, Fixtures.user(email = Some(email)))
        second <- central.post(path, Fixtures.user(email = Some(email)))
      yield assertTrue(second.status == Status.Conflict)
        .label("two users behind one email means a sign-in that cannot be resolved to a person")
    },
    test("a second user cannot claim a login already indexed") {
      for
        central <- api
        login <- CentralApi.login("probe")
        _ <- create(central, Fixtures.user(login = Some(login)))
        second <- central.post(path, Fixtures.user(login = Some(login)))
      yield assertTrue(second.status == Status.Conflict)
    },
    test("a second user cannot claim a phone already indexed") {
      for
        central <- api
        phone <- CentralApi.phone
        _ <- create(central, Fixtures.user(phone = Some(phone)))
        second <- central.post(path, Fixtures.user(phone = Some(phone)))
      yield assertTrue(second.status == Status.Conflict)
    },
    test("a conflicting creation leaves the original user untouched") {
      for
        central <- api
        email <- CentralApi.email("probe")
        login <- CentralApi.login("probe")
        id <- create(central, Fixtures.user(email = Some(email), login = Some(login)))
        _ <- central.post(path, Fixtures.user(email = Some(email)))
        record <- byId(central, id)
      yield assertTrue(record.flatMap(_.str("login")).contains(login))
        .label("a rejected creation must not have partially overwritten the index it collided with")
    },
    test("a search with no criteria is refused") {
      for
        central <- api
        rejected <- central.get(path)
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("an unfiltered search would page through every user in the deployment")
    },
    test("a search for an identifier nobody holds answers an empty result") {
      for
        central <- api
        login <- CentralApi.login("probe-absent")
        found <- find(central, "login" -> login)
      yield assertTrue(found.isEmpty)
        .label("an empty result, not a 404: the caller asked a question, and the answer is 'nobody'")
    },
    test("a search by id that is not a UUID is refused") {
      for
        central <- api
        rejected <- central.get(path, "id" -> "not-a-uuid")
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("UUID"))
    },
    test("a search by a phone number that cannot be parsed is refused") {
      for
        central <- api
        rejected <- central.get(path, "phone" -> "12345")
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("the index stores numbers in one canonical form, so an unparsable one cannot match anything")
    },
    test("a search by an id nobody holds answers an empty result") {
      for
        central <- api
        id <- CentralApi.uuid
        found <- find(central, "id" -> id.toString)
      yield assertTrue(found.isEmpty)
    },
    test("only one criterion is honoured when several are given") {
      for
        central <- api
        login <- CentralApi.login("probe")
        id <- create(central, Fixtures.user(login = Some(login)))
        other <- CentralApi.login("probe-absent")
        found <- central.get(path, "id" -> id, "login" -> other).flatMap(_.items("users"))
      yield assertTrue(found.flatMap(_.str("id")).contains(id))
        .label("id is checked first; a caller passing both gets a lookup, not an intersection")
    },
    test("a body that is not JSON is refused") {
      for
        central <- api
        rejected <- central.raw(Method.POST, path, "ada@example.test")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("a patch changes the indexed email") {
      for
        central <- api
        email <- CentralApi.email("probe")
        replacement <- CentralApi.email("probe-new")
        id <- create(central, Fixtures.user(email = Some(email)))
        patched <- central.patch(path, Json.Obj("id" -> Json.Str(id), "email" -> Json.Str(replacement)))
        record <- eventually(central, id)(_.str("email").contains(replacement))
        underOld <- find(central, "email" -> email)
      yield assertTrue(patched.status == Status.Accepted) &&
        assertTrue(record.flatMap(_.str("email")).contains(replacement)) &&
        assertTrue(underOld.isEmpty)
          .label("the old address must stop resolving, or a sign-in with it would still find the user")
    },
    test("a patch adds an identifier a user did not have") {
      for
        central <- api
        login <- CentralApi.login("probe")
        email <- CentralApi.email("probe")
        id <- create(central, Fixtures.user(login = Some(login)))
        _ <- central.patch(path, Json.Obj("id" -> Json.Str(id), "email" -> Json.Str(email)))
        record <- eventually(central, id)(_.str("email").contains(email))
      yield assertTrue(record.flatMap(_.str("login")).contains(login)) &&
        assertTrue(record.flatMap(_.str("email")).contains(email))
    },
    test("a patch clears an identifier when it is set to null") {
      for
        central <- api
        login <- CentralApi.login("probe")
        email <- CentralApi.email("probe")
        id <- create(central, Fixtures.user(email = Some(email), login = Some(login)))
        _ <- central.patch(path, Json.Obj("id" -> Json.Str(id), "login" -> Json.Null))
        record <- eventually(central, id)(_.str("login").isEmpty)
      yield assertTrue(record.flatMap(_.str("login")).isEmpty) &&
        assertTrue(record.flatMap(_.str("email")).contains(email))
          .label("merge-patch: null clears exactly the named member and nothing else")
    },
    test("a patch naming no member leaves the user as it was") {
      for
        central <- api
        email <- CentralApi.email("probe")
        id <- create(central, Fixtures.user(email = Some(email)))
        patched <- central.patch(path, Json.Obj("id" -> Json.Str(id)))
        record <- byId(central, id)
      yield assertTrue(patched.status == Status.Accepted) &&
        assertTrue(record.flatMap(_.str("email")).contains(email))
          .label("absent means unchanged, so an empty patch has to be a no-op rather than a wipe")
    },
    test("a patch cannot move an identifier onto a user that already holds it") {
      for
        central <- api
        held <- CentralApi.email("probe")
        _ <- create(central, Fixtures.user(email = Some(held)))
        login <- CentralApi.login("probe")
        other <- create(central, Fixtures.user(login = Some(login)))
        _ <- central.patch(path, Json.Obj("id" -> Json.Str(other), "email" -> Json.Str(held)))
        record <- byId(central, other).delay(1.second)
      yield assertTrue(record.flatMap(_.str("email")).isEmpty)
        .label("stealing an indexed email would make one address resolve to two people")
    },
    test("a patch of a user that does not exist does not create one") {
      for
        central <- api
        id <- CentralApi.uuid
        email <- CentralApi.email("probe")
        _ <- central.patch(path, Json.Obj("id" -> Json.Str(id.toString), "email" -> Json.Str(email)))
        found <- find(central, "email" -> email).delay(1.second)
      yield assertTrue(found.isEmpty)
        .label("a patch must not be a back door for creating an unindexed user; central answers 500 here today")
    },
    test("a patch without an id is refused") {
      for
        central <- api
        email <- CentralApi.email("probe")
        rejected <- central.patch(path, Json.Obj("email" -> Json.Str(email)))
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("claims written through the admin API become searchable on the user") {
      for
        central <- api
        login <- CentralApi.login("probe")
        id <- createSynced(central, Fixtures.user(login = Some(login)))
        patched <- central.patch(
          s"$path/claims",
          Json.Obj(
            "id" -> Json.Str(id),
            "claims" -> Json.Obj("given_name" -> Json.Str("Ada"), "family_name" -> Json.Str("Lovelace")),
          ),
        )
        record <- eventually(central, id)(_.obj("claims").exists(_.str("given_name").contains("Ada")))
        claims = record.flatMap(_.obj("claims"))
      yield assertTrue(patched.status == Status.Accepted) &&
        assertTrue(claims.flatMap(_.str("given_name")).contains("Ada")) &&
        assertTrue(claims.flatMap(_.str("family_name")).contains("Lovelace"))
    },
    test("a claims patch leaves the claims it does not name alone") {
      for
        central <- api
        login <- CentralApi.login("probe")
        id <- createSynced(central, Fixtures.user(login = Some(login)))
        _ <- central.patch(
          s"$path/claims",
          Json.Obj("id" -> Json.Str(id), "claims" -> Json.Obj("given_name" -> Json.Str("Ada"))),
        )
        _ <- eventually(central, id)(_.obj("claims").exists(_.str("given_name").contains("Ada")))
        _ <- central.patch(
          s"$path/claims",
          Json.Obj("id" -> Json.Str(id), "claims" -> Json.Obj("family_name" -> Json.Str("Lovelace"))),
        )
        record <- eventually(central, id)(_.obj("claims").exists(_.str("family_name").contains("Lovelace")))
        claims = record.flatMap(_.obj("claims"))
      yield assertTrue(claims.flatMap(_.str("given_name")).contains("Ada")) &&
        assertTrue(claims.flatMap(_.str("family_name")).contains("Lovelace"))
          .label("a form that saves one field must not clear the ones it did not render")
    },
    test("a claims patch overwrites the value of a claim already set") {
      for
        central <- api
        login <- CentralApi.login("probe")
        id <- createSynced(central, Fixtures.user(login = Some(login)))
        _ <- central.patch(
          s"$path/claims",
          Json.Obj("id" -> Json.Str(id), "claims" -> Json.Obj("given_name" -> Json.Str("Ada"))),
        )
        _ <- eventually(central, id)(_.obj("claims").exists(_.str("given_name").contains("Ada")))
        _ <- central.patch(
          s"$path/claims",
          Json.Obj("id" -> Json.Str(id), "claims" -> Json.Obj("given_name" -> Json.Str("Grace"))),
        )
        record <- eventually(central, id)(_.obj("claims").exists(_.str("given_name").contains("Grace")))
      yield assertTrue(record.flatMap(_.obj("claims")).flatMap(_.str("given_name")).contains("Grace"))
    },
    test("a claims patch without an id is refused") {
      for
        central <- api
        rejected <- central.patch(s"$path/claims", Json.Obj("claims" -> Json.Obj()))
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an anonymous caller cannot search for users") {
      for
        central <- api
        rejected <- central.anonymous.get(path, "login" -> "anyone")
      yield assertTrue(rejected.status == Status.Unauthorized)
        .label("this endpoint turns an email into a user id, which is exactly what an attacker wants")
    },
    test("an anonymous caller cannot create a user") {
      for
        central <- api
        login <- CentralApi.login("probe")
        rejected <- central.anonymous.post(path, Fixtures.user(login = Some(login)))
        found <- find(central, "login" -> login)
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(found.isEmpty)
    },
    test("a caller presenting the wrong secret cannot repoint a user's email") {
      for
        central <- api
        email <- CentralApi.email("probe")
        replacement <- CentralApi.email("probe-attacker")
        id <- create(central, Fixtures.user(email = Some(email)))
        rejected <- central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM")
          .patch(path, Json.Obj("id" -> Json.Str(id), "email" -> Json.Str(replacement)))
        record <- byId(central, id).delay(1.second)
      yield assertTrue(rejected.status == Status.Unauthorized) &&
        assertTrue(record.flatMap(_.str("email")).contains(email))
          .label("repointing an email is an account takeover, since password recovery follows it")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)
