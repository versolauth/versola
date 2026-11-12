package versola.edge.partner

import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

/** The team of a partner (#549 decision 3): who is admitted at a first sign-in, by what, and what an administrator may do. The
  * service is the real one, over a repository kept in memory and the real identifiers; what is under test is its rules. */
object StaffServiceSpec extends ZIOSpecDefault:

  private val key = Array.fill[Byte](32)(7)
  private val identifiers = Identifiers.Impl(key)

  private val service: ZIO[Any, Nothing, (StaffService, StaffRepository)] =
    StaffMemoryRepository.make.map(repository => (StaffService.Impl(repository, identifiers, 7.days), repository))

  private def userInfo(fields: (String, Json)*): Json.Obj = Json.Obj(fields*)
  private val phone = "+77011234511"

  /** `admit` with a `/userinfo` that counts how often it is read. */
  private def admit(staff: StaffService, subject: String, info: Json.Obj) =
    for
      reads <- Ref.make(0)
      admission <- staff.admit(subject, reads.update(_ + 1).as(info))
      count <- reads.get
    yield (admission, count)

  def spec = suite("StaffService")(
    suite("first sign-in")(
      test("a first administrator named in the configuration is admitted at their first sign-in, by the phone number of /userinfo") {
        for
          (staff, repository) <- service
          _ <- staff.applyAdmins(List("+7 701 123-45-11", "ops@partner.example"))
          (admission, _) <- admit(staff, "sub-1", userInfo("phone_number" -> Json.Str(phone)))
          members <- repository.members
          invites <- repository.invites
        yield assertTrue(
          admission == StaffService.Admission.Member(members.head),
          members.map(m => (m.subject, m.level, m.addedBy)) == List(("sub-1", StaffLevel.admin, "config")),
          // the other administrator's invite is still there, and the one that was taken is gone
          invites.map(_.kind) == List(IdentifierKind.email),
        )
      },
      test("an email of /userinfo admits as well, whatever its case") {
        for
          (staff, _) <- service
          _ <- staff.invite("admin-1", "Dev@Partner.Example", StaffLevel.developer, None)
          (admission, _) <- admit(staff, "sub-1", userInfo("email" -> Json.Str("dev@partner.example")))
        yield assertTrue(
          admission.isInstanceOf[StaffService.Admission.Member],
          admission.asInstanceOf[StaffService.Admission.Member].member.level == StaffLevel.developer,
        )
      },
      test("a member is known by their sub from then on and /userinfo is not read for them again") {
        for
          (staff, _) <- service
          _ <- staff.invite("admin-1", phone, StaffLevel.viewer, None)
          _ <- admit(staff, "sub-1", userInfo("phone_number" -> Json.Str(phone)))
          (again, reads) <- admit(staff, "sub-1", userInfo())
        yield assertTrue(again.isInstanceOf[StaffService.Admission.Member], reads == 0)
      },
      test(
        "an invite for another identifier is not taken: a forged one, or a person nobody invited, is not a member and sees the identifier they signed in with, masked",
      ) {
        for
          (staff, repository) <- service
          _ <- staff.invite("admin-1", "+77019999999", StaffLevel.admin, None)
          (admission, _) <- admit(staff, "sub-1", userInfo("phone_number" -> Json.Str(phone), "email" -> Json.Str("someone@else.example")))
          members <- repository.members
          invites <- repository.invites
        yield assertTrue(
          admission == StaffService.Admission.NotAMember(Some("+7••••••••11")),
          members.isEmpty,
          invites.size == 1,
        )
      },
      test("an expired invite admits nobody") {
        for
          (staff, repository) <- service
          _ <- staff.invite("admin-1", phone, StaffLevel.admin, Some(1.hour))
          _ <- TestClock.adjust(2.hours)
          (admission, _) <- admit(staff, "sub-1", userInfo("phone_number" -> Json.Str(phone)))
          members <- repository.members
        yield assertTrue(admission.isInstanceOf[StaffService.Admission.NotAMember], members.isEmpty)
      },
      test("an identifier the operator says is not verified does not take the invite") {
        for
          (staff, _) <- service
          _ <- staff.invite("admin-1", "dev@partner.example", StaffLevel.admin, None)
          (admission, _) <- admit(staff, "sub-1", userInfo("email" -> Json.Str("dev@partner.example"), "email_verified" -> Json.Bool(false)))
        yield assertTrue(admission.isInstanceOf[StaffService.Admission.NotAMember])
      },
      test("the invite of a person who was removed is gone, and so is their access") {
        for
          (staff, _) <- service
          _ <- staff.invite("admin-1", phone, StaffLevel.admin, None)
          _ <- admit(staff, "sub-1", userInfo("phone_number" -> Json.Str(phone)))
          _ <- staff.invite("admin-1", "+77010000001", StaffLevel.admin, None)
          _ <- admit(staff, "sub-2", userInfo("phone_number" -> Json.Str("+77010000001")))
          _ <- staff.remove("sub-2", "sub-1")
          (after, _) <- admit(staff, "sub-1", userInfo("phone_number" -> Json.Str(phone)))
        yield assertTrue(after.isInstanceOf[StaffService.Admission.NotAMember])
      },
    ),
    suite("identifiers")(
      test("what is stored is neither the number nor the address: a keyed hash that another key does not reproduce") {
        val parsed = identifiers.parse(phone).toOption.get
        val other = Identifiers.Impl(Array.fill[Byte](32)(8)).parse(phone).toOption.get
        assertTrue(!parsed.hash.contains("7701123"), parsed.hash.length == 64, parsed.hash != other.hash, parsed.masked == "+7••••••••11")
      },
      test("a phone number is the same however it is written, and what is neither a phone number nor an email is refused") {
        assertTrue(
          identifiers.parse("+7 (701) 123-45-11") == identifiers.parse(phone),
          identifiers.parse("87011234511").isLeft,
          identifiers.parse("not an identifier").isLeft,
          identifiers.parse("a@b").isLeft,
        )
      },
    ),
    suite("the team")(
      test("an administrator invites, changes a level and removes; the list shows members and invites, an invite by its masked identifier only") {
        for
          (staff, _) <- service
          invited <- staff.invite("admin-1", "dev@partner.example", StaffLevel.developer, None)
          _ <- staff.setLevel("admin-1", invited.id, StaffLevel.viewer)
          listed <- staff.list
          _ <- staff.remove("admin-1", invited.id)
          after <- staff.list
        yield assertTrue(
          invited.masked == Some("d•••@partner.example"),
          listed.map(entry => (entry.member, entry.level, entry.masked)) == List((false, StaffLevel.viewer, Some("d•••@partner.example"))),
          after.isEmpty,
        )
      },
      test("an administrator cannot remove themself") {
        for
          (staff, _) <- service
          _ <- staff.invite("x", phone, StaffLevel.admin, None)
          _ <- admit(staff, "sub-1", userInfo("phone_number" -> Json.Str(phone)))
          _ <- staff.invite("x", "+77010000001", StaffLevel.admin, None)
          _ <- admit(staff, "sub-2", userInfo("phone_number" -> Json.Str("+77010000001")))
          error <- staff.remove("sub-1", "sub-1").flip
        yield assertTrue(error == StaffService.Error.Conflict("you cannot remove yourself from the team"))
      },
      test("the last administrator is neither removed nor made a developer") {
        for
          (staff, _) <- service
          _ <- staff.invite("x", phone, StaffLevel.admin, None)
          _ <- admit(staff, "sub-1", userInfo("phone_number" -> Json.Str(phone)))
          _ <- staff.invite("x", "+77010000001", StaffLevel.developer, None)
          _ <- admit(staff, "sub-2", userInfo("phone_number" -> Json.Str("+77010000001")))
          removed <- staff.remove("sub-2", "sub-1").flip
          demoted <- staff.setLevel("sub-2", "sub-1", StaffLevel.developer).flip
        yield assertTrue(removed.isInstanceOf[StaffService.Error.Conflict], demoted.isInstanceOf[StaffService.Error.Conflict])
      },
      test("a row that is not there is not found") {
        for
          (staff, _) <- service
          removed <- staff.remove("sub-1", "nobody").flip
          set <- staff.setLevel("sub-1", UUID.randomUUID().toString, StaffLevel.viewer).flip
        yield assertTrue(removed.isInstanceOf[StaffService.Error.NotFound], set.isInstanceOf[StaffService.Error.NotFound])
      },
      test("an invite for something that is not an identifier is refused") {
        for
          (staff, _) <- service
          error <- staff.invite("admin-1", "nope", StaffLevel.viewer, None).flip
        yield assertTrue(error.isInstanceOf[StaffService.Error.Invalid])
      },
      test("a first administrator who is not a phone number or an email stops the edge at its start") {
        for
          (staff, _) <- service
          error <- staff.applyAdmins(List("nobody")).flip
        yield assertTrue(error.getMessage.contains("partner.admins"))
      },
    ),
    suite("levels")(
      test("admin reaches developer and viewer, developer reaches viewer, viewer reaches only itself") {
        assertTrue(
          StaffLevel.admin.atLeast(StaffLevel.developer),
          StaffLevel.developer.atLeast(StaffLevel.viewer),
          !StaffLevel.viewer.atLeast(StaffLevel.developer),
          !StaffLevel.developer.atLeast(StaffLevel.admin),
        )
      },
    ),
  ) @@ TestAspect.silentLogging
