package versola.edge.partner

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** What every storage of the partner's team must do (`partner_staff`, `partner_staff_invites`). A storage module runs it; the one
  * there is today is `PostgresStaffRepositorySpec`. */
trait StaffRepositorySpec extends DatabaseSpecBase[StaffRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val at = Instant.parse("2026-11-12T10:00:00Z")

  private def invite(hash: String, level: StaffLevel = StaffLevel.developer, kind: IdentifierKind = IdentifierKind.phone) =
    StaffInvite(UUID.randomUUID(), kind, hash, "+7••11", level, "config", at, at.plusSeconds(3600))

  private def member(subject: String, level: StaffLevel = StaffLevel.viewer) = StaffMember(subject, level, s"Member $subject", "config", at)

  override def testCases(env: StaffRepositorySpec.Env) =
    List(
      test("a team with nobody on it is empty") {
        for
          members <- env.repository.members
          invites <- env.repository.invites(at)
          found <- env.repository.findMember("nobody")
        yield assertTrue(members.isEmpty, invites.isEmpty, found.isEmpty)
      },
      test("an invite is found by the kind and the hash of its identifier, and by nothing else") {
        val made = invite("hash-1")
        for
          _ <- env.repository.invite(made)
          found <- env.repository.findInvite(IdentifierKind.phone, "hash-1")
          otherKind <- env.repository.findInvite(IdentifierKind.email, "hash-1")
          otherHash <- env.repository.findInvite(IdentifierKind.phone, "hash-2")
        yield assertTrue(found == Some(made), otherKind.isEmpty, otherHash.isEmpty)
      },
      test("inviting the same identifier again renews the invite and keeps its id") {
        val first = invite("hash-1", StaffLevel.viewer)
        val again = invite("hash-1", StaffLevel.admin).copy(invitedBy = "someone", expiresAt = at.plusSeconds(7200))
        for
          _ <- env.repository.invite(first)
          _ <- env.repository.invite(again)
          invites <- env.repository.invites(at)
        yield assertTrue(
          invites.size == 1,
          invites.head.id == first.id,
          invites.head.level == StaffLevel.admin,
          invites.head.invitedBy == "someone",
          invites.head.expiresAt == at.plusSeconds(7200),
        )
      },
      test("admitting turns the invite into the member in one step, and the invite is spent") {
        val made = invite("hash-1", StaffLevel.admin)
        for
          _ <- env.repository.invite(made)
          admitted <- env.repository.admit(made.id, member("sub-1", StaffLevel.admin))
          found <- env.repository.findMember("sub-1")
          invites <- env.repository.invites(at)
        yield assertTrue(admitted.isDefined, found.map(_.level) == Some(StaffLevel.admin), invites.isEmpty)
      },
      test("an invite lowered or re-issued while the person signs in admits them at its level now, not at the one that was read") {
        val made = invite("hash-1", StaffLevel.admin)
        for
          _ <- env.repository.invite(made)
          read <- env.repository.findInvite(IdentifierKind.phone, "hash-1").someOrFail(RuntimeException("no invite"))
          // an administrator lowers it after the sign-in has read it
          _ <- env.repository.setInviteLevel(made.id, StaffLevel.viewer)
          admitted <- env.repository.admit(read.id, member("sub-1", read.level))
          found <- env.repository.findMember("sub-1")
        yield assertTrue(
          read.level == StaffLevel.admin,
          admitted.map(_.level) == Some(StaffLevel.viewer),
          found.map(_.level) == Some(StaffLevel.viewer),
        )
      },
      test("an invite that is gone admits nobody: the second of two sign-ins at once finds it spent") {
        val made = invite("hash-1")
        for
          _ <- env.repository.invite(made)
          first <- env.repository.admit(made.id, member("sub-1"))
          second <- env.repository.admit(made.id, member("sub-2"))
          members <- env.repository.members
        yield assertTrue(first.isDefined, second.isEmpty, members.map(_.subject) == List("sub-1"))
      },
      test("a member who is there already is left as they are when the invite of the same person is spent") {
        val made = invite("hash-1", StaffLevel.viewer)
        for
          _ <- env.repository.invite(made)
          _ <- env.repository.admit(made.id, member("sub-1", StaffLevel.viewer))
          // a second invite, for a higher level, of the person who is on the team already
          _ <- env.repository.invite(made.copy(id = UUID.randomUUID(), level = StaffLevel.admin))
          again <- env.repository.invites(at)
          admitted <- env.repository.admit(again.head.id, member("sub-1", StaffLevel.admin))
          found <- env.repository.findMember("sub-1")
        yield assertTrue(
          found.map(_.level) == Some(StaffLevel.viewer),
          // what the request runs as is what is stored, not the level of the invite that was spent
          admitted == found,
          admitted.map(_.level) == Some(StaffLevel.viewer),
        )
      },
      test("a level is set on a member and on an invite, and a row that is not there says so") {
        val made = invite("hash-1", StaffLevel.viewer)
        for
          _ <- env.repository.invite(made)
          invited <- env.repository.setInviteLevel(made.id, StaffLevel.developer)
          _ <- env.repository.invite(invite("hash-2")).flatMap(_ => env.repository.invites(at)).flatMap(rows =>
            env.repository.admit(rows.find(_.identifierHash == "hash-2").get.id, member("sub-1", StaffLevel.viewer)),
          )
          set <- env.repository.setMemberLevel("sub-1", StaffLevel.admin)
          missingMember <- env.repository.setMemberLevel("nobody", StaffLevel.admin)
          missingInvite <- env.repository.setInviteLevel(UUID.randomUUID(), StaffLevel.admin)
          invites <- env.repository.invites(at)
          found <- env.repository.findMember("sub-1")
        yield assertTrue(
          invited,
          set == Change.Done,
          missingMember == Change.Missing,
          !missingInvite,
          invites.head.level == StaffLevel.developer,
          found.map(_.level) == Some(StaffLevel.admin),
        )
      },
      test("the last administrator is neither removed nor made a developer, and with another one either can be") {
        for
          a <- env.repository.invite(invite("hash-a", StaffLevel.admin)) *> env.repository.invites(at).flatMap(rows =>
            env.repository.admit(rows.head.id, member("sub-a", StaffLevel.admin)),
          )
          lastRemoved <- env.repository.removeMember("sub-a")
          lastDemoted <- env.repository.setMemberLevel("sub-a", StaffLevel.developer)
          _ <- env.repository.invite(invite("hash-b", StaffLevel.admin)) *> env.repository.invites(at).flatMap(rows =>
            env.repository.admit(rows.head.id, member("sub-b", StaffLevel.admin)),
          )
          demoted <- env.repository.setMemberLevel("sub-a", StaffLevel.developer)
          removed <- env.repository.removeMember("sub-a")
          lastAgain <- env.repository.removeMember("sub-b")
        yield assertTrue(
          lastRemoved == Change.LastAdmin,
          lastDemoted == Change.LastAdmin,
          demoted == Change.Done,
          removed == Change.Done,
          lastAgain == Change.LastAdmin,
        )
      },
      test("two administrators demoting each other at once leave one: the check and the change are one step") {
        for
          _ <- ZIO.foreachDiscard(List("a", "b"))(name =>
            env.repository.invite(invite(s"hash-$name", StaffLevel.admin)) *> env.repository.invites(at).flatMap(rows =>
              env.repository.admit(rows.find(_.identifierHash == s"hash-$name").get.id, member(s"sub-$name", StaffLevel.admin)),
            ),
          )
          results <-
            ZIO.collectAllPar(List.fill(8)(List("a", "b")).flatten.map(name => env.repository.setMemberLevel(s"sub-$name", StaffLevel.viewer)))
          members <- env.repository.members
        yield assertTrue(members.count(_.level == StaffLevel.admin) == 1, results.count(_ == Change.LastAdmin) >= 1)
      },
      test("a configured administrator's invite is made until they have signed in, and never again: a removed person stays removed") {
        for
          first <- env.repository.claimConfigAdmin(IdentifierKind.phone, "hash-c", at)
          again <- env.repository.claimConfigAdmin(IdentifierKind.phone, "hash-c", at)
          _ <- env.repository.invite(invite("hash-c", StaffLevel.admin)) *> env.repository.invites(at).flatMap(rows =>
            env.repository.admit(rows.head.id, member("sub-c", StaffLevel.admin)),
          )
          afterSignIn <- env.repository.claimConfigAdmin(IdentifierKind.phone, "hash-c", at)
          other <- env.repository.claimConfigAdmin(IdentifierKind.email, "hash-c", at)
        yield assertTrue(first, again, !afterSignIn, other)
      },
      test("an expired invite is not listed and is purged") {
        for
          _ <- env.repository.invite(invite("hash-1"))
          live <- env.repository.invites(at)
          late <- env.repository.invites(at.plusSeconds(7200))
          purged <- env.repository.purgeExpired(at.plusSeconds(7200))
          found <- env.repository.findInvite(IdentifierKind.phone, "hash-1")
        yield assertTrue(live.size == 1, late.isEmpty, purged == 1, found.isEmpty)
      },
      test("team events are kept and read newest first within a time range") {
        def event(offset: Long, action: String) = TeamEvent(UUID.randomUUID(), at.plusSeconds(offset), "sub-1", action, "+7••11", None)
        for
          _ <- ZIO.foreachDiscard(List(event(0, "invite"), event(10, "admit"), event(20, "remove")))(env.repository.record)
          all <- env.repository.events(None, None, 10)
          middle <- env.repository.events(Some(at.plusSeconds(5)), Some(at.plusSeconds(20)), 10)
        yield assertTrue(all.map(_.action) == List("remove", "admit", "invite"), middle.map(_.action) == List("admit"))
      },
      test("a member and an invite are removed, and removing what is not there is false") {
        val made = invite("hash-1")
        for
          _ <- env.repository.invite(made)
          removedInvite <- env.repository.removeInvite(made.id)
          removedAgain <- env.repository.removeInvite(made.id)
          _ <- env.repository.invite(invite("hash-2")).flatMap(_ => env.repository.invites(at)).flatMap(rows =>
            env.repository.admit(rows.head.id, member("sub-1")),
          )
          removedMember <- env.repository.removeMember("sub-1")
          removedMemberAgain <- env.repository.removeMember("sub-1")
        yield assertTrue(removedInvite, !removedAgain, removedMember == Change.Done, removedMemberAgain == Change.Missing)
      },
      test("every level and every kind of identifier is stored and read back as it was") {
        for
          _ <- ZIO.foreachDiscard(StaffLevel.values.toList)(level => env.repository.invite(invite(s"level-$level", level)))
          _ <- ZIO.foreachDiscard(IdentifierKind.values.toList)(kind => env.repository.invite(invite(s"kind-$kind", kind = kind)))
          invites <- env.repository.invites(at)
        yield assertTrue(
          StaffLevel.values.toSet.subsetOf(invites.map(_.level).toSet),
          IdentifierKind.values.toSet.subsetOf(invites.map(_.kind).toSet),
        )
      },
      test("a stored level or kind that is not one of the enum's fails to read, loudly, and does not become a default") {
        for
          _ <- env.storeRaw("sub-x", "owner", "fax", at)
          member <- env.repository.findMember("sub-x").exit
          invites <- env.repository.invites(at).exit
        yield assertTrue(member.isFailure, invites.isFailure)
      },
    )

object StaffRepositorySpec:
  /** `storeRaw` stores a member whose level is `rawLevel` and an invite whose kind is `rawKind`, as text, whatever the enums say: what a storage
    * must refuse to read back, loudly, when it is not one of them. The runner implements it for its storage. */
  case class Env(
      repository: StaffRepository,
      storeRaw: (subject: String, rawLevel: String, rawKind: String, at: Instant) => Task[Unit],
  )
