package versola.loadgen.store

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.loadgen.model.*
import versola.util.{DatabaseSpecBase, Secret}
import zio.*
import zio.test.*

import java.util.UUID

final case class VirtualUserEnv(repository: VirtualUserRepository)

/** `vu_users` against a real Postgres: the keyset pagination every driver walks its slice with,
  * the state transitions the coordinator's registration controller drives, and the `phone`
  * constraint the seeder detects collisions by.
  */
object PostgresVirtualUserRepositorySpec extends LoadgenPostgresSpec, DatabaseSpecBase[VirtualUserEnv]:

  private def user(id: Long, shard: Int, state: VirtualUserState = VirtualUserState.Planned): VirtualUser =
    VirtualUser(
      id = id,
      sutUserId = None,
      phone = f"+7700000$id%04d",
      password = Some(s"secret-$id"),
      activityClass = ActivityClass.Regular,
      platform = Platform.Mobile,
      credential = CredentialKind.OtpPassword,
      role = UserRole.RetailUser,
      passkeyKey = None,
      passkeyCredId = None,
      state = state,
      shard = shard,
      lastSeenAt = None,
    )

  /** `Secret` is an `Array[Byte]` newtype, so a case-class comparison of two of them is a
    * reference comparison. Compared as bytes here, and excluded from the whole-row comparison.
    */
  private def bytes(secret: Option[Secret]): Option[Seq[Byte]] = secret.map(_.toSeq)

  override lazy val environment =
    ZLayer:
      ZIO.serviceWith[TransactorZIO](xa => VirtualUserEnv(PostgresVirtualUserRepository(xa)))

  override def beforeEach(env: VirtualUserEnv) =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(sql"TRUNCATE TABLE vu_users".update.run()).unit

  override def testCases(env: VirtualUserEnv) = List(
    test("insertAll then find round-trips every persisted column, in the order V0001 declares them") {
      val planned = user(1, shard = 0)
      for
        _     <- env.repository.insertAll(Chunk(planned))
        found <- env.repository.find(1)
      yield assertTrue(found.contains(planned))
    },
    test("a registered user round-trips its SUT identity and its passkey material") {
      val enrolled = user(1, shard = 0, state = VirtualUserState.Registered).copy(
        sutUserId = Some(UUID.fromString("00000000-0000-0000-0000-0000000000ff")),
        credential = CredentialKind.Passkey,
        platform = Platform.Web,
        activityClass = ActivityClass.Heavy,
        role = UserRole.RetailBasic,
        passkeyKey = Some(Secret.fromString("pkcs8-private-key")),
        passkeyCredId = Some("credential-1"),
      )
      for
        _     <- env.repository.insertAll(Chunk(enrolled))
        found <- env.repository.find(1)
      yield assertTrue(
        found.map(_.copy(passkeyKey = None)).contains(enrolled.copy(passkeyKey = None)),
        found.map(u => bytes(u.passkeyKey)).contains(bytes(enrolled.passkeyKey)),
      )
    },
    test("insertAll on an empty chunk is a no-op, not an empty round trip") {
      env.repository.insertAll(Chunk.empty).as(assertCompletes)
    },
    test("the phone constraint rejects a second user claiming the same number") {
      val taken = user(1, shard = 0)
      for
        _    <- env.repository.insertAll(Chunk(taken))
        exit <- env.repository.insertAll(Chunk(user(2, shard = 0).copy(phone = taken.phone))).exit
      yield assertTrue(exit.isFailure)
    },
    test("loadShardSlice pages through one driver's slice by keyset, without repeating a row") {
      val population = Chunk(user(1, 0), user(2, 1), user(3, 0), user(4, 1), user(5, 0))
      for
        _      <- env.repository.insertAll(population)
        first  <- env.repository.loadShardSlice(shard = 0, afterId = None, limit = 2)
        second <- env.repository.loadShardSlice(shard = 0, afterId = first.lastOption.map(_.id), limit = 2)
        third  <- env.repository.loadShardSlice(shard = 0, afterId = second.lastOption.map(_.id), limit = 2)
      yield assertTrue(
        first.map(_.id) == Vector(1L, 3L),
        second.map(_.id) == Vector(5L),
        third.isEmpty,
      )
    },
    test("loadShardSlice starting from no id begins at the first user, not the second") {
      for
        _     <- env.repository.insertAll(Chunk(user(1, 0), user(2, 0)))
        slice <- env.repository.loadShardSlice(shard = 0, afterId = None, limit = 10)
      yield assertTrue(slice.map(_.id) == Vector(1L, 2L))
    },
    test("markRegistered records the SUT identity and leaves the state registered in one statement") {
      val sutUserId = UUID.fromString("00000000-0000-0000-0000-00000000000a")
      for
        _     <- env.repository.insertAll(Chunk(user(1, shard = 0)))
        _     <- env.repository.markRegistered(1, sutUserId)
        found <- env.repository.find(1)
      yield assertTrue(
        found.flatMap(_.sutUserId).contains(sutUserId),
        found.map(_.state).contains(VirtualUserState.Registered),
      )
    },
    test("markBroken moves a user out of the population the scheduler picks from") {
      for
        _     <- env.repository.insertAll(Chunk(user(1, shard = 0, state = VirtualUserState.Registered)))
        _     <- env.repository.markBroken(1)
        found <- env.repository.find(1)
      yield assertTrue(found.map(_.state).contains(VirtualUserState.Broken))
    },
    test("recordPasskey persists the key and the credential id the SUT now knows about") {
      val key = Secret.fromString("pkcs8-private-key")
      for
        _     <- env.repository.insertAll(Chunk(user(1, shard = 0)))
        _     <- env.repository.recordPasskey(1, key, "credential-1")
        found <- env.repository.find(1)
      yield assertTrue(
        found.map(u => bytes(u.passkeyKey)).contains(Some(key.toSeq)),
        found.flatMap(_.passkeyCredId).contains("credential-1"),
      )
    },
    test("touchAll moves last_seen_at for the batch, and of nothing else") {
      val seenAt = java.time.Instant.parse("2026-01-01T12:00:00Z")
      for
        _         <- env.repository.insertAll(Chunk(user(1, 0), user(2, 0)))
        _         <- env.repository.touchAll(Chunk(UserTouch(1, seenAt)))
        touched   <- env.repository.find(1)
        untouched <- env.repository.find(2)
      yield assertTrue(
        touched.flatMap(_.lastSeenAt).contains(seenAt),
        untouched.flatMap(_.lastSeenAt).isEmpty,
      )
    },
    test("touchAll on an empty batch is a no-op, not an empty round trip") {
      env.repository.touchAll(Chunk.empty).as(assertCompletes)
    },
    test("countByState counts every state that has rows, and omits the ones that do not") {
      val population = Chunk(
        user(1, 0),
        user(2, 0),
        user(3, 0, state = VirtualUserState.Registered),
      )
      for
        _      <- env.repository.insertAll(population)
        counts <- env.repository.countByState
      yield assertTrue(
        counts == Map(VirtualUserState.Planned -> 2L, VirtualUserState.Registered -> 1L),
        !counts.contains(VirtualUserState.Broken),
      )
    },
  )
