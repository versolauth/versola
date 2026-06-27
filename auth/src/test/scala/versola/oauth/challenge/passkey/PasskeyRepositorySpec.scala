package versola.oauth.challenge.passkey

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.user.model.UserId
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

trait PasskeyRepositorySpec extends DatabaseSpecBase[PasskeyRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val credId1 = CredentialId(Array.fill(32)(1.toByte))
  val credId2 = CredentialId(Array.fill(32)(2.toByte))

  val baseInstant = Instant.parse("2024-01-01T00:00:00Z")

  def record(
      id: CredentialId,
      userId: UserId,
      name: Option[String] = Some("My Key"),
      createdAt: Instant = baseInstant,
  ) = PasskeyRecord(
    id = id,
    userId = userId,
    publicKey = Array.fill(16)(7.toByte),
    signatureCounter = 0L,
    deviceType = CredentialDeviceType.MultiDevice,
    backedUp = true,
    backupEligible = true,
    transports = List(AuthenticatorTransport.Internal, AuthenticatorTransport.Hybrid),
    attestationObject = None,
    clientDataJson = None,
    aaguid = None,
    name = name,
    lastUsedAt = None,
    createdAt = createdAt,
    updatedAt = createdAt,
  )

  def testCases(env: PasskeyRepositorySpec.Env): List[Spec[PasskeyRepositorySpec.Env & Scope, Any]] =
    List(
      test("insert then findByCredentialIdAndUser returns the record") {
        val rec = record(credId1, userId1)
        for
          _ <- env.repository.insert(rec)
          found <- env.repository.findByCredentialIdAndUser(credId1, userId1)
        yield assertTrue(
          found.isDefined,
          found.exists(_.name == rec.name),
          found.exists(_.deviceType == rec.deviceType),
          found.exists(_.transports == rec.transports),
          found.exists(_.backedUp == rec.backedUp),
          found.exists(_.backupEligible == rec.backupEligible),
          found.exists(r => java.util.Arrays.equals(r.publicKey, rec.publicKey)),
        )
      },
      test("findByCredentialIdAndUser returns None for a different user") {
        for
          _ <- env.repository.insert(record(credId1, userId1))
          found <- env.repository.findByCredentialIdAndUser(credId1, userId2)
        yield assertTrue(found.isEmpty)
      },
      test("findByCredentialId returns all records matching the credential id") {
        for
          _ <- env.repository.insert(record(credId1, userId1))
          found <- env.repository.findByCredentialId(credId1)
        yield assertTrue(found.size == 1, found.head.userId == userId1)
      },
      test("listByUser returns the user's records ordered by created_at") {
        for
          _ <- env.repository.insert(record(credId2, userId1, name = Some("second"), createdAt = baseInstant.plusSeconds(60)))
          _ <- env.repository.insert(record(credId1, userId1, name = Some("first"), createdAt = baseInstant))
          _ <- env.repository.insert(record(CredentialId(Array.fill(32)(3.toByte)), userId2, name = Some("other")))
          found <- env.repository.listByUser(userId1)
        yield assertTrue(found.map(_.name) == Vector(Some("first"), Some("second")))
      },
      test("updateUsage updates signature counter and last used timestamp") {
        val usedAt = baseInstant.plusSeconds(120)
        for
          _ <- env.repository.insert(record(credId1, userId1))
          result <- env.repository.updateUsage(credId1, signatureCounter = 5L, lastUsedAt = usedAt)
          found <- env.repository.findByCredentialIdAndUser(credId1, userId1)
        yield assertTrue(
          result,
          found.exists(_.signatureCounter == 5L),
          found.exists(_.lastUsedAt.contains(usedAt)),
        )
      },
      test("updateUsage returns false when new counter is not greater than stored") {
        for
          _ <- env.repository.insert(record(credId1, userId1))
          _ <- env.repository.updateUsage(credId1, signatureCounter = 5L, lastUsedAt = baseInstant)
          result <- env.repository.updateUsage(credId1, signatureCounter = 5L, lastUsedAt = baseInstant)
        yield assertTrue(!result)
      },
      test("updateUsage allows only one concurrent update with the same counter") {
        for
          _ <- env.repository.insert(record(credId1, userId1))
          results <- ZIO.collectAllPar(List(
            env.repository.updateUsage(credId1, signatureCounter = 6L, lastUsedAt = baseInstant),
            env.repository.updateUsage(credId1, signatureCounter = 6L, lastUsedAt = baseInstant),
          ))
        yield assertTrue(results.count(_ == true) == 1)
      },
      test("updateUsage returns true when new counter is zero (counter not supported)") {
        for
          _ <- env.repository.insert(record(credId1, userId1))
          result <- env.repository.updateUsage(credId1, signatureCounter = 0L, lastUsedAt = baseInstant)
        yield assertTrue(result)
      },
      test("updateUsage returns false when new counter is zero but stored counter is non-zero") {
        for
          _ <- env.repository.insert(record(credId1, userId1))
          _ <- env.repository.updateUsage(credId1, signatureCounter = 5L, lastUsedAt = baseInstant)
          result <- env.repository.updateUsage(credId1, signatureCounter = 0L, lastUsedAt = baseInstant)
        yield assertTrue(!result)
      },
      test("rename updates the name when the user matches") {
        for
          _ <- env.repository.insert(record(credId1, userId1, name = Some("orig")))
          _ <- env.repository.rename(credId1, userId1, Some("renamed"))
          found <- env.repository.findByCredentialIdAndUser(credId1, userId1)
        yield assertTrue(found.exists(_.name.contains("renamed")))
      },
      test("rename does not change the name when the user does not match") {
        for
          _ <- env.repository.insert(record(credId1, userId1, name = Some("orig")))
          _ <- env.repository.rename(credId1, userId2, Some("hacked"))
          found <- env.repository.findByCredentialIdAndUser(credId1, userId1)
        yield assertTrue(found.exists(_.name.contains("orig")))
      },
      test("deleteByUser removes the record when the user matches") {
        for
          _ <- env.repository.insert(record(credId1, userId1))
          _ <- env.repository.deleteByUser(credId1, userId1)
          found <- env.repository.findByCredentialIdAndUser(credId1, userId1)
        yield assertTrue(found.isEmpty)
      },
      test("deleteByUser does not remove the record when the user does not match") {
        for
          _ <- env.repository.insert(record(credId1, userId1))
          _ <- env.repository.deleteByUser(credId1, userId2)
          found <- env.repository.findByCredentialIdAndUser(credId1, userId1)
        yield assertTrue(found.isDefined)
      },
      test("delete removes the record by credential id") {
        for
          _ <- env.repository.insert(record(credId1, userId1))
          _ <- env.repository.delete(credId1)
          found <- env.repository.findByCredentialId(credId1)
        yield assertTrue(found.isEmpty)
      },
    )

object PasskeyRepositorySpec:
  case class Env(repository: PasskeyRepository)
