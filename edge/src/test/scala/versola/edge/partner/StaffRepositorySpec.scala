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
          invites <- env.repository.invites
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
          invites <- env.repository.invites
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
          invites <- env.repository.invites
        yield assertTrue(admitted, found.map(_.level) == Some(StaffLevel.admin), invites.isEmpty)
      },
      test("an invite that is gone admits nobody: the second of two sign-ins at once finds it spent") {
        val made = invite("hash-1")
        for
          _ <- env.repository.invite(made)
          first <- env.repository.admit(made.id, member("sub-1"))
          second <- env.repository.admit(made.id, member("sub-2"))
          members <- env.repository.members
        yield assertTrue(first, !second, members.map(_.subject) == List("sub-1"))
      },
      test("a member who is there already is left as they are when the invite of the same person is spent") {
        val made = invite("hash-1", StaffLevel.admin)
        for
          _ <- env.repository.invite(made)
          _ <- env.repository.admit(made.id, member("sub-1", StaffLevel.viewer))
          _ <- env.repository.invite(made.copy(id = UUID.randomUUID()))
          again <- env.repository.invites
          _ <- env.repository.admit(again.head.id, member("sub-1", StaffLevel.admin))
          found <- env.repository.findMember("sub-1")
        yield assertTrue(found.map(_.level) == Some(StaffLevel.viewer))
      },
      test("a level is set on a member and on an invite, and a row that is not there says so") {
        val made = invite("hash-1", StaffLevel.viewer)
        for
          _ <- env.repository.invite(made)
          invited <- env.repository.setInviteLevel(made.id, StaffLevel.developer)
          _ <- env.repository.invite(invite("hash-2")).flatMap(_ => env.repository.invites).flatMap(rows =>
            env.repository.admit(rows.find(_.identifierHash == "hash-2").get.id, member("sub-1", StaffLevel.viewer)),
          )
          set <- env.repository.setMemberLevel("sub-1", StaffLevel.admin)
          missingMember <- env.repository.setMemberLevel("nobody", StaffLevel.admin)
          missingInvite <- env.repository.setInviteLevel(UUID.randomUUID(), StaffLevel.admin)
          invites <- env.repository.invites
          found <- env.repository.findMember("sub-1")
        yield assertTrue(
          invited,
          set,
          !missingMember,
          !missingInvite,
          invites.head.level == StaffLevel.developer,
          found.map(_.level) == Some(StaffLevel.admin),
        )
      },
      test("a member and an invite are removed, and removing what is not there is false") {
        val made = invite("hash-1")
        for
          _ <- env.repository.invite(made)
          removedInvite <- env.repository.removeInvite(made.id)
          removedAgain <- env.repository.removeInvite(made.id)
          _ <- env.repository.invite(invite("hash-2")).flatMap(_ => env.repository.invites).flatMap(rows =>
            env.repository.admit(rows.head.id, member("sub-1")),
          )
          removedMember <- env.repository.removeMember("sub-1")
          removedMemberAgain <- env.repository.removeMember("sub-1")
        yield assertTrue(removedInvite, !removedAgain, removedMember, !removedMemberAgain)
      },
    )

object StaffRepositorySpec:
  case class Env(repository: StaffRepository)
