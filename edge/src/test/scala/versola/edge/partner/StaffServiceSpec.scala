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
  private def phoneInfo(number: String) = userInfo("phone_number" -> Json.Str(number), "phone_number_verified" -> Json.Bool(true))

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
          (admission, _) <- admit(staff, "sub-1", phoneInfo(phone))
          members <- repository.members
          invites <- Clock.instant.flatMap(repository.invites)
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
          (admission, _) <- admit(staff, "sub-1", userInfo("email" -> Json.Str("dev@partner.example"), "email_verified" -> Json.Bool(true)))
        yield assertTrue(
          admission.isInstanceOf[StaffService.Admission.Member],
          admission.asInstanceOf[StaffService.Admission.Member].member.level == StaffLevel.developer,
        )
      },
      test("a member is known by their sub from then on and /userinfo is not read for them again") {
        for
          (staff, _) <- service
          _ <- staff.invite("admin-1", phone, StaffLevel.viewer, None)
          _ <- admit(staff, "sub-1", phoneInfo(phone))
          (again, reads) <- admit(staff, "sub-1", userInfo())
        yield assertTrue(again.isInstanceOf[StaffService.Admission.Member], reads == 0)
      },
      test(
        "an invite for another identifier is not taken: a forged one, or a person nobody invited, is not a member and sees the identifier they signed in with, masked",
      ) {
        for
          (staff, repository) <- service
          _ <- staff.invite("admin-1", "+77019999999", StaffLevel.admin, None)
          (admission, _) <- admit(
            staff,
            "sub-1",
            userInfo(
              "phone_number" -> Json.Str(phone),
              "phone_number_verified" -> Json.Bool(true),
              "email" -> Json.Str("someone@else.example"),
              "email_verified" -> Json.Bool(true),
            ),
          )
          members <- repository.members
          invites <- Clock.instant.flatMap(repository.invites)
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
          (admission, _) <- admit(staff, "sub-1", phoneInfo(phone))
          members <- repository.members
        yield assertTrue(admission.isInstanceOf[StaffService.Admission.NotAMember], members.isEmpty)
      },
      test(
        "an identifier whose verified claim is absent does not take the invite: auth leaves it out for an account whose contact was set without a code",
      ) {
        for
          (staff, repository) <- service
          _ <- staff.invite("admin-1", "dev@partner.example", StaffLevel.admin, None)
          (absent, _) <- admit(staff, "sub-1", userInfo("email" -> Json.Str("dev@partner.example")))
          (phoneAbsent, _) <- admit(staff, "sub-2", userInfo("phone_number" -> Json.Str(phone)))
          members <- repository.members
        yield assertTrue(
          absent.isInstanceOf[StaffService.Admission.NotAMember],
          phoneAbsent.isInstanceOf[StaffService.Admission.NotAMember],
          members.isEmpty,
        )
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
          _ <- admit(staff, "sub-1", phoneInfo(phone))
          _ <- staff.invite("admin-1", "+77010000001", StaffLevel.admin, None)
          _ <- admit(staff, "sub-2", phoneInfo("+77010000001"))
          _ <- staff.remove("sub-2", "sub-1")
          (after, _) <- admit(staff, "sub-1", phoneInfo(phone))
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
          _ <- admit(staff, "sub-1", phoneInfo(phone))
          _ <- staff.invite("x", "+77010000001", StaffLevel.admin, None)
          _ <- admit(staff, "sub-2", phoneInfo("+77010000001"))
          error <- staff.remove("sub-1", "sub-1").flip
        yield assertTrue(error == StaffService.Error.Conflict("you cannot remove yourself from the team"))
      },
      test("the last administrator is neither removed nor made a developer") {
        for
          (staff, _) <- service
          _ <- staff.invite("x", phone, StaffLevel.admin, None)
          _ <- admit(staff, "sub-1", phoneInfo(phone))
          _ <- staff.invite("x", "+77010000001", StaffLevel.developer, None)
          _ <- admit(staff, "sub-2", phoneInfo("+77010000001"))
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
      test("a person removed from the team stays removed though the configuration still names them: the invite is not made again at the next start") {
        for
          (staff, repository) <- service
          _ <- staff.applyAdmins(List(phone))
          _ <- staff.invite("x", "+77010000001", StaffLevel.admin, None)
          _ <- admit(staff, "sub-1", phoneInfo(phone))
          _ <- admit(staff, "sub-2", phoneInfo("+77010000001"))
          _ <- staff.remove("sub-2", "sub-1")
          _ <- staff.applyAdmins(List(phone))
          (after, _) <- admit(staff, "sub-1", phoneInfo(phone))
          invites <- Clock.instant.flatMap(repository.invites)
        yield assertTrue(after.isInstanceOf[StaffService.Admission.NotAMember], invites.isEmpty)
      },
      test("a configured administrator who has not signed in yet has the invite renewed at every start") {
        for
          (staff, repository) <- service
          _ <- staff.applyAdmins(List(phone))
          _ <- TestClock.adjust(6.days)
          _ <- staff.applyAdmins(List(phone))
          _ <- TestClock.adjust(6.days)
          (admission, _) <- admit(staff, "sub-1", phoneInfo(phone))
        yield assertTrue(admission.isInstanceOf[StaffService.Admission.Member])
      },
      test("a /userinfo about another sub is not about this person: nothing is taken from it") {
        for
          (staff, _) <- service
          _ <- staff.invite("admin-1", phone, StaffLevel.admin, None)
          (admission, _) <- admit(staff, "sub-1", phoneInfo(phone).add("sub", Json.Str("someone-else")))
        yield assertTrue(admission.isInstanceOf[StaffService.Admission.NotAMember])
      },
      test("a name the person chose is bounded, stripped of control characters, and shown beside the identifier they were invited by") {
        for
          (staff, repository) <- service
          _ <- staff.invite("admin-1", phone, StaffLevel.viewer, None)
          _ <- admit(staff, "sub-1", phoneInfo(phone).add("name", Json.Str("Admin\u0000 " + "x" * 200)))
          member <- repository.findMember("sub-1")
        yield assertTrue(member.exists(m => m.display.length <= 64 + 3 + 12 && !m.display.contains('\u0000') && m.display.contains("+7")))
      },
      test("a person who is not on the team is not looked up at the operator on every request, and a new invite is seen at once") {
        for
          (staff, _) <- service
          (first, firstReads) <- admit(staff, "sub-1", phoneInfo(phone))
          (second, secondReads) <- admit(staff, "sub-1", phoneInfo(phone))
          _ <- staff.invite("admin-1", phone, StaffLevel.viewer, None)
          (third, thirdReads) <- admit(staff, "sub-1", phoneInfo(phone))
          _ <- TestClock.adjust(1.minute)
        yield assertTrue(
          first.isInstanceOf[StaffService.Admission.NotAMember],
          firstReads == 1,
          secondReads == 0,
          third.isInstanceOf[StaffService.Admission.Member],
          thirdReads == 1,
        )
      },
      test("an expired invite is not listed") {
        for
          (staff, _) <- service
          _ <- staff.invite("admin-1", phone, StaffLevel.viewer, Some(1.hour))
          before <- staff.list
          _ <- TestClock.adjust(2.hours)
          after <- staff.list
        yield assertTrue(before.size == 1, after.isEmpty)
      },
      test("team changes are recorded: who invited, changed and removed whom, and an admission") {
        for
          (staff, _) <- service
          _ <- staff.invite("admin-1", phone, StaffLevel.viewer, None)
          _ <- admit(staff, "sub-1", phoneInfo(phone))
          _ <- staff.setLevel("admin-1", "sub-1", StaffLevel.developer).either
          _ <- staff.remove("admin-1", "sub-1")
          events <- staff.events(None, None, 10)
        yield assertTrue(events.map(_.action).toSet == Set("invite", "admit", "level", "remove"), events.forall(_.target != phone))
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
