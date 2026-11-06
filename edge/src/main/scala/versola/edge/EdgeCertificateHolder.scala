package versola.edge

import versola.util.PrivateClientCertificate
import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/** The certificate this edge presents to central: where the files are now, and, where central issues it, the means of
  * replacing it.
  *
  * [[paths]] is read on every connection to central (zio-http takes a client certificate as file paths), so a renewal
  * has only to install the new pair: the next connection presents it. A pair is written to a directory of its own and
  * never over the previous one, so a connection never reads a certificate from one pair and a key from another.
  */
trait EdgeCertificateHolder:
  /** The certificate chain (leaf first) and the PKCS#8 key to present now, or `None` before the first certificate. */
  def paths: Option[(String, String)]

  /** The certificate and key held, parsed. */
  def material: UIO[Option[PrivateClientCertificate.Material]]

  /** Keeps `chain` with `privateKeyPem` and makes them the pair that is presented. Fails, changing nothing, on a pair
    * that is not one certificate chain with its key. Not supported where the certificate is the operator's. */
  def install(chain: String, privateKeyPem: String): Task[Unit]

object EdgeCertificateHolder:
  val live: ZLayer[EdgeConfig, Throwable, EdgeCertificateHolder] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[EdgeConfig]: config =>
        config.central.identity match
          case Some(identity) => Directory.open(Path.of(identity.directory))
          case None =>
            val files =
              for
                mtls <- config.central.mutualTls
                certificate <- mtls.certificate
                privateKey <- mtls.privateKey
              yield (certificate, privateKey)
            ZIO.succeed(Static(files))

  /** The files whatever deployed the edge provides (cert-manager, `mtls-init`): read at start-up as before, replaced
    * by a restart. */
  private class Static(files: Option[(String, String)]) extends EdgeCertificateHolder:
    override def paths: Option[(String, String)] = files

    override def material: UIO[Option[PrivateClientCertificate.Material]] =
      ZIO.attemptBlocking:
        files.flatMap: (certificate, privateKey) =>
          val chain = Files.readString(Path.of(certificate)).trim
          val key = Files.readString(Path.of(privateKey)).trim
          PrivateClientCertificate(chain + "\n" + key).material.toOption
      .orElseSucceed(None)

    override def install(chain: String, privateKeyPem: String): Task[Unit] =
      ZIO.fail(IllegalStateException("this edge's certificate is the one it was deployed with; central does not issue it"))

  private case class Generation(number: Long, certificate: Path, privateKey: Path)

  private val GenerationPrefix = "gen-"
  private val OwnerOnlyDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
  private val OwnerOnlyFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

  /** Pairs kept in a directory only the edge can read, one subdirectory per pair. The newest that holds a readable
    * certificate and key is the one presented after a restart. */
  private class Directory(root: Path, state: AtomicReference[Option[Generation]]) extends EdgeCertificateHolder:
    // An `AtomicReference` and not a `Ref`: `paths` is read where a `Client` is configured, which is not an effect.
    override def paths: Option[(String, String)] =
      state.get.map(generation => (generation.certificate.toString, generation.privateKey.toString))

    override def material: UIO[Option[PrivateClientCertificate.Material]] =
      ZIO.attemptBlocking(state.get.flatMap(read)).orElseSucceed(None)

    override def install(chain: String, privateKeyPem: String): Task[Unit] =
      for
        _ <- ZIO.fromEither(PrivateClientCertificate(chain.trim + "\n" + privateKeyPem.trim).material)
          .mapError(reason => IllegalArgumentException(s"not a certificate chain with its key: $reason"))
        _ <- ZIO.attemptBlocking:
          val number = state.get.fold(1L)(_.number + 1)
          val directory = Files.createDirectory(root.resolve(f"$GenerationPrefix$number%010d"), OwnerOnlyDirectory)
          // The key first: a certificate is never present without its key.
          val key = write(directory.resolve("edge.key"), privateKeyPem.trim + "\n")
          val certificate = write(directory.resolve("edge.crt"), chain.trim + "\n")
          state.set(Some(Generation(number, certificate, key)))
          // The previous pair stays for a connection that read its paths a moment ago; older ones go.
          generations(root).filter(_.number < number - 1).foreach(old => deleteGeneration(old))
      yield ()

    private def write(path: Path, content: String): Path =
      val temporary = Files.createTempFile(path.getParent, ".tmp-", "", OwnerOnlyFile)
      Files.writeString(temporary, content, StandardCharsets.UTF_8)
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)

  private object Directory:
    def open(root: Path): Task[EdgeCertificateHolder] =
      ZIO.attemptBlocking:
        Files.createDirectories(root, OwnerOnlyDirectory)
        val newest = generations(root).sortBy(-_.number).find(generation => read(generation).isDefined)
        Directory(root, AtomicReference(newest))

  private def generations(root: Path): List[Generation] =
    scala.util.Using.resource(Files.list(root)): stream =>
      stream.iterator.asScala.toList.flatMap: path =>
        val name = path.getFileName.toString
        Option.when(name.startsWith(GenerationPrefix) && Files.isDirectory(path)):
          name.stripPrefix(GenerationPrefix).toLongOption.map(Generation(_, path.resolve("edge.crt"), path.resolve("edge.key")))
        .flatten

  private def read(generation: Generation): Option[PrivateClientCertificate.Material] =
    scala.util.Try:
      val chain = Files.readString(generation.certificate).trim
      val key = Files.readString(generation.privateKey).trim
      PrivateClientCertificate(chain + "\n" + key).material.toOption
    .toOption.flatten

  private def deleteGeneration(generation: Generation): Unit =
    scala.util.Try:
      Files.deleteIfExists(generation.certificate)
      Files.deleteIfExists(generation.privateKey)
      Files.deleteIfExists(generation.certificate.getParent)
