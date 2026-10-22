package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.util.UUID

object PostgresVelocityRepositorySpec extends PostgresSpec:

  import VelocityKey.*

  private def bucket(keyType: VelocityKey, key: String, minute: Long, count: Int) = VelocityBucket(keyType, key, minute, count)

  private val clean: ZIO[TransactorZIO, Throwable, PostgresVelocityRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_velocity".update.run())
    yield PostgresVelocityRepository(xa)

  def spec = suite("PostgresVelocityRepository")(
    test("what an instance saves, the others load, summed per key and minute, and its own is left out") {
      for
        repository <- clean
        a = UUID.randomUUID()
        b = UUID.randomUUID()
        c = UUID.randomUUID()
        _ <- repository.save(a, List(bucket(ip, "1.1.1.1", 100, 3), bucket(phone, "h", 100, 1)))
        _ <- repository.save(b, List(bucket(ip, "1.1.1.1", 100, 4), bucket(ip, "1.1.1.1", 101, 2)))
        seenByC <- repository.loadOthers(c, 0, 100)
        seenByA <- repository.loadOthers(a, 0, 100)
      yield assertTrue(
        seenByC.toSet == Set(bucket(ip, "1.1.1.1", 100, 7), bucket(ip, "1.1.1.1", 101, 2), bucket(phone, "h", 100, 1)),
        seenByA.toSet == Set(bucket(ip, "1.1.1.1", 100, 4), bucket(ip, "1.1.1.1", 101, 2)),
      )
    },
    test("saving a minute again replaces it, so a write that is repeated changes nothing") {
      for
        repository <- clean
        a = UUID.randomUUID()
        _ <- repository.save(a, List(bucket(ip, "k", 100, 3)))
        _ <- repository.save(a, List(bucket(ip, "k", 100, 5), bucket(ip, "k", 101, 1)))
        _ <- repository.save(a, List(bucket(ip, "k", 100, 5), bucket(ip, "k", 101, 1)))
        seen <- repository.loadOthers(UUID.randomUUID(), 0, 100)
      yield assertTrue(seen.toSet == Set(bucket(ip, "k", 100, 5), bucket(ip, "k", 101, 1)))
    },
    test("loads only the minutes asked for, and at most the limit, the busiest first") {
      for
        repository <- clean
        a = UUID.randomUUID()
        _ <- repository.save(a, List(bucket(ip, "old", 50, 99), bucket(ip, "small", 100, 1), bucket(ip, "big", 100, 9), bucket(ip, "mid", 101, 4)))
        recent <- repository.loadOthers(UUID.randomUUID(), 100, 100)
        top <- repository.loadOthers(UUID.randomUUID(), 100, 2)
      yield assertTrue(recent.map(_.key).toSet == Set("small", "big", "mid"), top.map(_.key) == List("big", "mid"))
    },
    test("prunes the minutes before the one given, and nothing else") {
      for
        repository <- clean
        a = UUID.randomUUID()
        _ <- repository.save(a, List(bucket(asn, "64500", 10, 1), bucket(asn, "64500", 20, 1), bucket(prefix, "t/49", 30, 1)))
        _ <- repository.prune(20)
        seen <- repository.loadOthers(UUID.randomUUID(), 0, 100)
      yield assertTrue(seen.toSet == Set(bucket(asn, "64500", 20, 1), bucket(prefix, "t/49", 30, 1)))
    },
    test("saves an empty batch, and many keys at once, from several instances at once") {
      for
        repository <- clean
        _ <- repository.save(UUID.randomUUID(), Nil)
        _ <- ZIO.foreachParDiscard(1 to 4)(i =>
          repository.save(UUID.randomUUID(), (1 to 500).map(k => bucket(ip, s"k$k", 100 + i, 1)).toList),
        )
        seen <- repository.loadOthers(UUID.randomUUID(), 0, 10000)
      yield assertTrue(seen.size == 2000)
    },
    test("a value in the database that is not a key kind is left out, not a failure") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(
          sql"INSERT INTO af_velocity (key_type, key, minute, instance_id, count) VALUES ('future', 'x', 100, ${UUID.randomUUID()}, 1)".update.run(),
        )
        seen <- repository.loadOthers(UUID.randomUUID(), 0, 100)
      yield assertTrue(seen.isEmpty)
    },
  ) @@ TestAspect.sequential
