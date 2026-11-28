package versola.oauth.handoff

import com.nimbusds.jose.jwk.{JWK, JWKSet, RSAKey}
import versola.util.{CacheSource, CoreConfig, JWT, ReloadingCache}
import zio.json.EncoderOps
import zio.{Clock, Duration, Ref, Scope, Task, UIO, ZIO, ZLayer, durationInt}

import java.security.interfaces.RSAPublicKey
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The cells as this auth knows them, which is what it needs of them to hand a sign-in over (versolauth/versola#1050):
  * the public keys a cell signs its seals with and takes them under.
  *
  * Read from central like the rest of the configuration, kept in a [[ReloadingCache]] on the same interval, and served
  * from the stored snapshot while central is down. An `auth` that is no cell never hands anything over and never asks
  * central for the list: it starts, and works, without it.
  *
  * A cell whose record holds no usable key is known, and has no keys: a seal for it is not made and a seal from it is not
  * opened.
  */
trait CellRegistry:
  /** The keys a seal from `cell` is verified with; none for a cell this auth does not know, or one with no usable key. */
  def keysOf(cell: String): UIO[Option[JWT.PublicKeys]]

  /** The key a seal for `cell` is encrypted to: the first of the keys central lists for it. */
  def encryptionKeyOf(cell: String): UIO[Option[RSAPublicKey]]

  def all: UIO[List[CellInfo]]

  /** Reads the cells from central now, instead of at the next refresh interval. For the non-prod
    * `/service/configuration/sync`, which exists so that a test need not wait one out. */
  def refresh: UIO[Unit]

  /** The same when a cell that should be known is not: a cell registered a moment ago is unknown to this auth until
    * its next refresh, and a sign-in for one of its users would be refused for that long. At most once in
    * [[CellRegistry.MissRefreshEvery]], whoever asks: the name of a cell in a seal is the sender's to choose, and must
    * not be a way to make this auth call central at will. */
  def refreshAfterMiss: UIO[Unit]

object CellRegistry:
  val MissRefreshEvery: Duration = 5.seconds

  private case class Entry(info: CellInfo, keys: Option[JWT.PublicKeys], encryption: Option[RSAPublicKey])

  /** Only a public RSA key with a `kid` is a key of a cell: anything else in the list is left out rather than failing the
    * sync, which would blind this auth to every other cell. A key that is left out is never a key a seal is made for, or
    * accepted from. */
  private def entryOf(info: CellInfo): Entry =
    def parse(json: zio.json.ast.Json.Obj): Option[RSAKey] =
      Try(JWK.parse(json.toJson)).toOption.collect:
        case key: RSAKey if !key.isPrivate && Option(key.getKeyID).exists(_.nonEmpty) => key
    val usable = info.publicKeys.flatMap(parse(_).toList)
    Entry(
      info = info,
      keys = Option.when(usable.nonEmpty)(JWT.PublicKeys(JWKSet(usable.map(key => key: JWK).asJava))),
      // The first of the list, and not the first that parses: which key to encrypt to is central's word.
      encryption = info.publicKeys.headOption.flatMap(parse).flatMap(key => Try(key.toRSAPublicKey).toOption),
    )

  val live: ZLayer[Scope & CoreConfig & CellSyncClient, Throwable, CellRegistry] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[CoreConfig]
        source <- ZIO.service[CellSyncClient]
        registry <- make(isCell = config.cellId.isDefined, source = source, config = config)
      yield registry

  /** `isCell` false gives a registry that has no cell, without ever calling `source`. */
  def make(isCell: Boolean, source: CellSyncClient, config: CoreConfig): ZIO[Scope, Throwable, CellRegistry] =
    if !isCell then ZIO.succeed(Empty)
    else
      val entries = new CacheSource[Map[String, Entry]]:
        override def getAll: Task[Map[String, Entry]] =
          source.getAll.map(_.map(info => info.id -> entryOf(info)).toMap)
      for
        cache <- ReloadingCache.make[Map[String, Entry]](config.configurationCacheRefreshInterval, fromSnapshot = true)
          .provideSomeLayer[Scope](ZLayer.succeed[CacheSource[Map[String, Entry]]](entries))
        lastMiss <- Ref.make(Instant.EPOCH)
        // A reload that fails keeps what there is: the cells this auth knows stay known.
        reload = entries.getAll.flatMap(cache.set).catchAllCause(cause => ZIO.logWarningCause("could not read the cells from central", cause))
      yield Impl(cache, reload, lastMiss)

  /** The registry of an auth that is no cell: it knows none and asks central for none. */
  val none: CellRegistry = Empty

  private object Empty extends CellRegistry:
    override def keysOf(cell: String): UIO[Option[JWT.PublicKeys]] = ZIO.none
    override def encryptionKeyOf(cell: String): UIO[Option[RSAPublicKey]] = ZIO.none
    override def all: UIO[List[CellInfo]] = ZIO.succeed(Nil)
    override def refresh: UIO[Unit] = ZIO.unit
    override def refreshAfterMiss: UIO[Unit] = ZIO.unit

  private class Impl(cache: ReloadingCache[Map[String, Entry]], reload: UIO[Unit], lastMiss: Ref[Instant]) extends CellRegistry:
    override def refresh: UIO[Unit] = reload

    override def refreshAfterMiss: UIO[Unit] =
      Clock.instant.flatMap: now =>
        lastMiss
          .modify(last => if Duration.fromInterval(last, now).compareTo(MissRefreshEvery) >= 0 then (true, now) else (false, last))
          .flatMap(due => reload.when(due).unit)

    override def keysOf(cell: String): UIO[Option[JWT.PublicKeys]] = cache.get.map(_.get(cell).flatMap(_.keys))

    override def encryptionKeyOf(cell: String): UIO[Option[RSAPublicKey]] = cache.get.map(_.get(cell).flatMap(_.encryption))

    override def all: UIO[List[CellInfo]] = cache.get.map(_.values.map(_.info).toList.sortBy(_.id))
