package versola.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.util.UUID

/** What every [[VelocityRepository]] promises: the counters an instance saves are what the others load, per instance and
  * without its own; a saved minute replaces the earlier value; a load is bounded by minutes and by keys, the busiest kept;
  * pruning is by minute and in batches; forgetting is by key, by range of numbers, or everything, for one instance or all.
  * A counter of a kind this version does not know, which the repository cannot write, is stored through
  * [[VelocityRepositorySpec.Fixtures]]. A storage adds what it alone promises (a statement timeout) in `implementationCases`. */
trait VelocityRepositorySpec extends DatabaseSpecBase[VelocityRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import VelocityKey.*
  import VelocityRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    List(
      test("what an instance saves, the others load, per instance, and its own is left out") {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        for
          _ <- repository.save(a, List(bucket(ip, "k", 100, 3), bucket(phone, "h", 100, 1)))
          _ <- repository.save(b, List(bucket(ip, "k", 100, 4), bucket(ip, "k", 101, 2)))
          seenByC <- repository.loadOthers(c, 0, Long.MaxValue, 100)
          seenByA <- repository.loadOthers(a, 0, Long.MaxValue, 100)
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
        val a = UUID.randomUUID()
        for
          _ <- repository.save(a, List(bucket(ip, "k", 100, 3)))
          _ <- repository.save(a, List(bucket(ip, "k", 100, 5), bucket(ip, "k", 101, 1)))
          _ <- repository.save(a, List(bucket(ip, "k", 100, 5), bucket(ip, "k", 101, 1)))
          seen <- repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 100)
        yield assertTrue(seen.map(_.bucket).toSet == Set(bucket(ip, "k", 100, 5), bucket(ip, "k", 101, 1)))
      },
      test("loads only the minutes asked for") {
        for
          _ <- repository.save(UUID.randomUUID(), List(bucket(ip, "old", 50, 99), bucket(ip, "new", 100, 1)))
          recent <- repository.loadOthers(UUID.randomUUID(), 100, Long.MaxValue, 100)
        yield assertTrue(recent.map(_.bucket.key) == List("new"))
      },
      test("loads a range of minutes, the end not included") {
        val reader = UUID.randomUUID()
        for
          _ <- repository.save(
            UUID.randomUUID(),
            List(bucket(ip, "a", 99, 1), bucket(ip, "b", 100, 1), bucket(ip, "c", 109, 1), bucket(ip, "d", 110, 1)),
          )
          range <- repository.loadOthers(reader, 100, 110, 100)
        yield assertTrue(range.map(_.bucket.key).toSet == Set("b", "c"))
      },
      test("the limit cuts keys, the busiest kept, and never the minutes of a key") {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        for
          // The hot key has many small minutes, the others one big minute each: ranked by bucket, the hot key would lose.
          _ <- repository.save(
            a,
            (1 to 30).map(m => bucket(ip, "hot", 100L + m, 2)).toList ++ (1 to 5).map(i => bucket(ip, s"big$i", 100, 40)).toList,
          )
          _ <- repository.save(b, List(bucket(ip, "hot", 100, 3), bucket(ip, "small", 100, 1)))
          top <- repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 3)
        yield assertTrue(
          top.map(_.bucket.key).toSet.size == 3,
          top.map(_.bucket.key).toSet.contains("hot"),
          top.count(_.bucket.key == "hot") == 31,
          !top.exists(_.bucket.key == "small"),
        )
      },
      test("prunes the minutes before the one given, and nothing else") {
        val a = UUID.randomUUID()
        for
          _ <- repository.save(a, List(bucket(asn, "64500", 10, 1), bucket(asn, "64500", 20, 1), bucket(prefix, "t/49", 30, 1)))
          deleted <- repository.prune(20, 100)
          seen <- repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 100)
        yield assertTrue(deleted == 1, seen.map(_.bucket).toSet == Set(bucket(asn, "64500", 20, 1), bucket(prefix, "t/49", 30, 1)))
      },
      test("prunes at most a batch at a time, and the caller repeats until it gets less") {
        for
          _ <- repository.save(UUID.randomUUID(), (1 to 25).map(m => bucket(ip, "k", m.toLong, 1)).toList)
          first <- repository.prune(1000, 10)
          second <- repository.prune(1000, 10)
          third <- repository.prune(1000, 10)
          again <- repository.prune(1000, 10)
          left <- repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 100)
        yield assertTrue((first, second, third, again) == (10, 10, 5, 0), left.isEmpty)
      },
      test("several prunes at once all succeed, whoever does the work") {
        for
          _ <- repository.save(UUID.randomUUID(), List(bucket(asn, "1", 10, 1), bucket(asn, "1", 30, 1)))
          results <- ZIO.foreachPar(1 to 8)(_ => repository.prune(20, 100).exit)
          seen <- repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 100)
        yield assertTrue(results.forall(_.isSuccess), seen.map(_.bucket.minute) == List(30L))
      },
      test("saves a batch bigger than a chunk in one go") {
        for
          _ <- repository.save(UUID.randomUUID(), (1 to 5000).map(k => bucket(phone, s"k$k", 100, k)).toList)
          seen <- repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 10000)
        yield assertTrue(seen.size == 5000, seen.map(_.bucket.count.toLong).sum == 5000L * 5001 / 2)
      },
      test("saves an empty batch, and many keys at once, from several instances at once") {
        for
          _ <- repository.save(UUID.randomUUID(), Nil)
          _ <- ZIO.foreachParDiscard(1 to 4)(i =>
            repository.save(UUID.randomUUID(), (1 to 500).map(k => bucket(ip, s"k$k", 100 + i, 1)).toList),
          )
          seen <- repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 10000)
        yield assertTrue(seen.size == 2000)
      },
      test("forgets everything, one key, or the ranges that start with some digits of every tenant") {
        val a = UUID.randomUUID()
        val reader = UUID.randomUUID()
        val rows = List(
          bucket(phone, "h1", 100, 1),
          bucket(phone, "h2", 100, 1),
          bucket(ip, "h1", 100, 1),
          bucket(prefix, "t1/491512", 100, 1),
          bucket(prefix, "t2/491599", 100, 1),
          bucket(prefix, "t1/337000", 100, 1),
        )
        for
          _ <- repository.save(a, rows)
          oneKey <- repository.forget(VelocityTarget.Key(phone, "h1"), None)
          afterKey <- repository.loadOthers(reader, 0, Long.MaxValue, 100)
          ranges <- repository.forget(VelocityTarget.Range("4915"), None)
          afterRange <- repository.loadOthers(reader, 0, Long.MaxValue, 100)
          // `%` or `_` cannot be in a range's digits, but a range is not matched in the middle of the digits either.
          none <- repository.forget(VelocityTarget.Range("9"), None)
          all <- repository.forget(VelocityTarget.All, None)
          afterAll <- repository.loadOthers(reader, 0, Long.MaxValue, 100)
        yield assertTrue(
          oneKey == 1,
          afterKey.map(_.bucket).toSet == (rows.toSet - bucket(phone, "h1", 100, 1)),
          ranges == 2,
          afterRange.map(_.bucket).toSet == Set(bucket(phone, "h2", 100, 1), bucket(ip, "h1", 100, 1), bucket(prefix, "t1/337000", 100, 1)),
          none == 0,
          all == 3,
          afterAll.isEmpty,
        )
      },
      test("forgetting for one instance leaves the rows of the others") {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        for
          _ <- repository.save(a, List(bucket(ip, "k", 100, 1)))
          _ <- repository.save(b, List(bucket(ip, "k", 100, 2)))
          gone <- repository.forget(VelocityTarget.All, Some(a))
          left <- repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 100)
        yield assertTrue(gone == 1, left == List(shared(b, bucket(ip, "k", 100, 2))))
      },
      test("a value in the table that is not a key kind is left out, not a failure") {
        for
          _ <- env.fixtures.bucketOfKind(keyType = "future", key = "x", minute = 100, instance = UUID.randomUUID(), count = 1)
          seen <- repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 100)
        yield assertTrue(seen.isEmpty)
      },
    )

object VelocityRepositorySpec:
  case class Env(repository: VelocityRepository, fixtures: Fixtures)

  /** What a case stores that the repository under test cannot write: the storage's runner supplies it. */
  trait Fixtures:
    /** A counter as a later version would store it, `keyType` by the name it is stored under and not one this version has to know. */
    def bucketOfKind(keyType: String, key: String, minute: Long, instance: UUID, count: Int): Task[Unit]

  def bucket(keyType: VelocityKey, key: String, minute: Long, count: Int): VelocityBucket =
    VelocityBucket(keyType = keyType, key = key, minute = minute, count = count)

  def shared(instance: UUID, bucket: VelocityBucket): SharedBucket = SharedBucket(instance = instance, bucket = bucket)
