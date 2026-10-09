package versola.util

import zio.*
import zio.http.{Body, Request, Response}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The last configuration central served, kept so that a service can start while central is
  * unreachable (#566).
  *
  * What is kept is the raw body of each sync response, not the value decoded from it. The
  * secrets in a body are still encrypted to the service that received it, so nothing is stored
  * in the clear, and a body read back goes through exactly the decoding a live one does.
  *
  * Each record is authenticated with an HMAC under a key derived from key material the service
  * already holds. Whoever has that material can already read everything the snapshot holds from
  * central directly, so a central signature would add a key to distribute and no protection. A
  * record that fails the check is never served.
  */
trait ConfigSnapshot:

  /** Runs `fetch` and records a successful body under the request's path (its segments, so a
    * central URL with and without a trailing slash records the same key). While a cache is
    * being started from the snapshot (see [[ReloadingCache.make]]), answers from the record
    * instead and does not run `fetch` at all.
    */
  def through(request: Request)(fetch: ZIO[Scope, Throwable, Response]): ZIO[Scope, Throwable, Response]

object ConfigSnapshot:

  case class Record(key: String, body: Array[Byte], savedAt: Instant, mac: Array[Byte])

  trait Repository:
    def find(key: String): Task[Option[Record]]
    def save(record: Record): Task[Unit]

  final case class Missing(key: String)
      extends RuntimeException(s"No configuration snapshot of '$key'")

  final case class Rejected(key: String)
      extends RuntimeException(s"The configuration snapshot of '$key' failed verification")

  /** A request that failed after the snapshot has already been served in this process: the
    * source is known to be down, so a cache starting now goes to the snapshot without waiting
    * for it again.
    */
  final case class SourceDown(key: String, cause: Throwable)
      extends RuntimeException(s"'$key' is unreachable and the configuration snapshot is being served", cause)

  /** An unchanged body is written again once this old, so that the age of a record stays a
    * bound on how long ago central last confirmed it, not on when it last changed.
    */
  private val ConfirmInterval: Duration = 10.minutes

  private val Version: Array[Byte] = Array(1)

  /** Set while a cache is started from the snapshot. Collects the oldest save time of the
    * records read, which is the age the cache is served at.
    */
  private val replaying: FiberRef[Option[Ref[Option[Instant]]]] =
    Unsafe.unsafe(FiberRef.unsafe.make(Option.empty[Ref[Option[Instant]]])(using _))

  /** Runs `load` with every request answered from the snapshot. The save time is `None` when no
    * request went through a snapshot, i.e. the value was loaded live.
    */
  private[util] def replay[A](load: Task[A]): Task[(A, Option[Instant])] =
    for
      oldest <- Ref.make(Option.empty[Instant])
      value <- replaying.locally(Some(oldest))(load)
      savedAt <- oldest.get
    yield (value, savedAt)

  val disabled: ConfigSnapshot = new ConfigSnapshot:
    override def through(request: Request)(fetch: ZIO[Scope, Throwable, Response]): ZIO[Scope, Throwable, Response] =
      fetch

  def make(repository: Repository, keyMaterial: Array[Byte]): UIO[ConfigSnapshot] =
    for
      saved <- Ref.make(Map.empty[String, Saved])
      serving <- Ref.make(false)
    yield Impl(repository, deriveKey(keyMaterial), saved, serving)

  private def deriveKey(keyMaterial: Array[Byte]): SecretKeySpec =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(keyMaterial, "HmacSHA256"))
    SecretKeySpec(mac.doFinal("versola-config-snapshot-v1".getBytes(StandardCharsets.UTF_8)), "HmacSHA256")

  private case class Saved(digest: Array[Byte], savedAt: Instant)

  private class Impl(
      repository: Repository,
      key: SecretKeySpec,
      saved: Ref[Map[String, Saved]],
      serving: Ref[Boolean],
  ) extends ConfigSnapshot:

    override def through(request: Request)(fetch: ZIO[Scope, Throwable, Response]): ZIO[Scope, Throwable, Response] =
      val recordKey = request.url.path.segments.mkString("/", "/", "")
      replaying.get.flatMap:
        case Some(oldest) =>
          load(recordKey).flatMap: record =>
            oldest.update(current => Some(current.fold(record.savedAt)(c => if c.isBefore(record.savedAt) then c else record.savedAt))) *>
              serving.set(true).as(Response.ok.copy(body = Body.fromArray(record.body)))
        case None =>
          fetch
            .catchAll(error => serving.get.flatMap(down => ZIO.fail(if down then SourceDown(recordKey, error) else error)))
            .flatMap: response =>
              if !response.status.isSuccess then ZIO.succeed(response)
              else
                response.body.asArray.flatMap: body =>
                  serving.set(false) *> save(recordKey, body).forkDaemon.as(response.copy(body = Body.fromArray(body)))

    private def load(recordKey: String): Task[Record] =
      repository.find(recordKey)
        .someOrFail(Missing(recordKey))
        .filterOrFail(record => MessageDigest.isEqual(record.mac, mac(record.key, record.savedAt, record.body)))(
          Rejected(recordKey),
        )

    /** Never fails the sync it is part of: a snapshot that cannot be written only matters on a
      * later cold start without central. It also does not delay it: callers set what they fetched
      * into a cache, and a database write between the fetch and that `set` lets a slower, older
      * fetch of a concurrent sync land after a newer one -- a client registered a moment ago is
      * then missing until the next refresh.
      */
    private def save(recordKey: String, body: Array[Byte]): UIO[Unit] =
      (for
        now <- Clock.instant.map(_.truncatedTo(ChronoUnit.MILLIS))
        digest = MessageDigest.getInstance("SHA-256").digest(body)
        previous <- saved.get.map(_.get(recordKey))
        confirmed = previous.exists(p => MessageDigest.isEqual(p.digest, digest) && now.isBefore(p.savedAt.plus(ConfirmInterval)))
        _ <- ZIO.unless(confirmed):
          repository.save(Record(recordKey, body, now, mac(recordKey, now, body))) *>
            saved.update(_.updated(recordKey, Saved(digest, now)))
      yield ()).catchAllCause(cause => ZIO.logWarningCause(s"Couldn't save the configuration snapshot of '$recordKey'", cause))

    /** Covers the key and the save time as well as the body, so that a record can be neither
      * moved to another key nor made to look younger than it is.
      */
    private def mac(recordKey: String, savedAt: Instant, body: Array[Byte]): Array[Byte] =
      val keyBytes = recordKey.getBytes(StandardCharsets.UTF_8)
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(key)
      mac.update(Version)
      mac.update(ByteBuffer.allocate(4).putInt(keyBytes.length).array())
      mac.update(keyBytes)
      mac.update(ByteBuffer.allocate(8).putLong(savedAt.toEpochMilli).array())
      mac.doFinal(body)
