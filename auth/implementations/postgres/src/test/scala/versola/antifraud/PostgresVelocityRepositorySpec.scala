package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object PostgresVelocityRepositorySpec extends PostgresSpec:

  import VelocityKey.*

  private def bucket(keyType: VelocityKey, key: String, minute: Long, count: Int) =
    VelocityBucket(keyType = keyType, key = key, minute = minute, count = count)

  private def shared(instance: UUID, b: VelocityBucket) = SharedBucket(instance = instance, bucket = b)

  private val clean: ZIO[TransactorZIO, Throwable, PostgresVelocityRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_velocity".update.run())
    yield PostgresVelocityRepository(xa)

  def spec = suite("PostgresVelocityRepository")(
    test("what an instance saves, the others load, per instance, and its own is left out") {
      for
        repository <- clean
        a = UUID.randomUUID()
        b = UUID.randomUUID()
        c = UUID.randomUUID()
        _ <- repository.save(a, List(bucket(ip, "k", 100, 3), bucket(phone, "h", 100, 1)))
        _ <- repository.save(b, List(bucket(ip, "k", 100, 4), bucket(ip, "k", 101, 2)))
        seenByC <- repository.loadOthers(c, 0, Instant.EPOCH, 100)
        seenByA <- repository.loadOthers(a, 0, Instant.EPOCH, 100)
      yield assertTrue(
        seenByC.toSet == Set(
          shared(a, bucket(ip, "k", 100, 3)),
          shared(a, bucket(phone, "h", 100, 1)),
          shared(b, bucket(ip, "k", 100, 4)),
          shared(b, bucket(ip, "k", 101, 2)),
        ),
        seenByA.toSet == Set(shared(b, bucket(ip, "k", 100, 4)), shared(b, bucket(ip, "k", 101, 2))),
      )
    },
    test("saving a minute again replaces it, so a write that is repeated changes nothing") {
      for
        repository <- clean
        a = UUID.randomUUID()
        _ <- repository.save(a, List(bucket(ip, "k", 100, 3)))
        _ <- repository.save(a, List(bucket(ip, "k", 100, 5), bucket(ip, "k", 101, 1)))
        _ <- repository.save(a, List(bucket(ip, "k", 100, 5), bucket(ip, "k", 101, 1)))
        seen <- repository.loadOthers(UUID.randomUUID(), 0, Instant.EPOCH, 100)
      yield assertTrue(seen.map(_.bucket).toSet == Set(bucket(ip, "k", 100, 5), bucket(ip, "k", 101, 1)))
    },
    test("loads only the minutes asked for") {
      for
        repository <- clean
        _ <- repository.save(UUID.randomUUID(), List(bucket(ip, "old", 50, 99), bucket(ip, "new", 100, 1)))
        recent <- repository.loadOthers(UUID.randomUUID(), 100, Instant.EPOCH, 100)
      yield assertTrue(recent.map(_.bucket.key) == List("new"))
    },
    test("loads only what changed since a time, and a rewritten minute counts as changed") {
      for
        repository <- clean
        a = UUID.randomUUID()
        reader = UUID.randomUUID()
        _ <- repository.save(a, List(bucket(ip, "before", 100, 1), bucket(ip, "rewritten", 100, 1)))
        _ <- ZIO.sleep(1100.millis)
        cut <- Clock.instant
        _ <- ZIO.sleep(1100.millis)
        _ <- repository.save(a, List(bucket(ip, "after", 100, 1), bucket(ip, "rewritten", 100, 2)))
        changed <- repository.loadOthers(reader, 0, cut, 100)
      yield assertTrue(changed.map(_.bucket).toSet == Set(bucket(ip, "after", 100, 1), bucket(ip, "rewritten", 100, 2)))
    } @@ TestAspect.withLiveClock,
    test("the limit cuts keys, the busiest kept, and never the minutes of a key") {
      for
        repository <- clean
        a = UUID.randomUUID()
        b = UUID.randomUUID()
        // The hot key has many small minutes, the others one big minute each: ranked by bucket, the hot key would lose.
        _ <- repository.save(a, (1 to 30).map(m => bucket(ip, "hot", 100L + m, 2)).toList ++ (1 to 5).map(i => bucket(ip, s"big$i", 100, 40)).toList)
        _ <- repository.save(b, List(bucket(ip, "hot", 100, 3), bucket(ip, "small", 100, 1)))
        top <- repository.loadOthers(UUID.randomUUID(), 0, Instant.EPOCH, 3)
      yield assertTrue(
        top.map(_.bucket.key).toSet.size == 3,
        top.map(_.bucket.key).toSet.contains("hot"),
        top.count(_.bucket.key == "hot") == 31,
        !top.exists(_.bucket.key == "small"),
      )
    },
    test("prunes the minutes before the one given, and nothing else") {
      for
        repository <- clean
        a = UUID.randomUUID()
        _ <- repository.save(a, List(bucket(asn, "64500", 10, 1), bucket(asn, "64500", 20, 1), bucket(prefix, "t/49", 30, 1)))
        _ <- repository.prune(20)
        seen <- repository.loadOthers(UUID.randomUUID(), 0, Instant.EPOCH, 100)
      yield assertTrue(seen.map(_.bucket).toSet == Set(bucket(asn, "64500", 20, 1), bucket(prefix, "t/49", 30, 1)))
    },
    test("several prunes at once all succeed, whoever does the work") {
      for
        repository <- clean
        _ <- repository.save(UUID.randomUUID(), List(bucket(asn, "1", 10, 1), bucket(asn, "1", 30, 1)))
        results <- ZIO.foreachPar(1 to 8)(_ => repository.prune(20).exit)
        seen <- repository.loadOthers(UUID.randomUUID(), 0, Instant.EPOCH, 100)
      yield assertTrue(results.forall(_.isSuccess), seen.map(_.bucket.minute) == List(30L))
    },
    test("saves an empty batch, and many keys at once, from several instances at once") {
      for
        repository <- clean
        _ <- repository.save(UUID.randomUUID(), Nil)
        _ <- ZIO.foreachParDiscard(1 to 4)(i =>
          repository.save(UUID.randomUUID(), (1 to 500).map(k => bucket(ip, s"k$k", 100 + i, 1)).toList),
        )
        seen <- repository.loadOthers(UUID.randomUUID(), 0, Instant.EPOCH, 10000)
      yield assertTrue(seen.size == 2000)
    },
    test("a value in the database that is not a key kind is left out, not a failure") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(
          sql"INSERT INTO af_velocity (key_type, key, minute, instance_id, count) VALUES ('future', 'x', 100, ${UUID.randomUUID()}, 1)".update.run(),
        )
        seen <- repository.loadOthers(UUID.randomUUID(), 0, Instant.EPOCH, 100)
      yield assertTrue(seen.isEmpty)
    },
  ) @@ TestAspect.sequential
