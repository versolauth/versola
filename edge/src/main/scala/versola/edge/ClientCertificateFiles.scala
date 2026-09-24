package versola.edge

import versola.util.{Base64, PrivateClientCertificate}
import zio.http.ClientSSLCertConfig
import zio.{Ref, Scope, Task, UIO, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Where a client certificate this edge presents is put so that the TLS stack can read it.
  *
  * Not an implementation detail that could be avoided: zio-http takes a client certificate as
  * a pair of filesystem paths and offers no way to hand it the material directly, so a
  * certificate that arrives over sync has to reach the disk before it can be presented. The
  * files are what leaves this process, so they are written where only this process can read
  * them, and removed with the scope that created them.
  */
trait ClientCertificateFiles:
  /** The certificate as zio-http names one, written out on first use.
    *
    * Keyed by the material rather than by the client, because the same certificate presented
    * for a second client is the same handshake — and because a client whose certificate
    * central has rotated must not go on presenting the one already on disk.
    */
  def present(certificate: PrivateClientCertificate.Material): Task[ClientSSLCertConfig]

object ClientCertificateFiles:
  val live: ZLayer[Scope, Throwable, ClientCertificateFiles] =
    ZLayer.fromZIO(
      for
        directory <- ZIO.acquireRelease(createDirectory)(remove)
        written <- Ref.Synchronized.make(Map.empty[String, ClientSSLCertConfig])
      yield Impl(directory, written),
    )

  /** `700`, and created with those permissions rather than given them afterwards: a directory
    * that is world-readable for even an instant is one a private key could be read out of. */
  private def createDirectory: Task[Path] =
    ZIO.attemptBlocking(
      Files.createTempDirectory(
        "versola-client-certificates",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
      ),
    )

  private def remove(directory: Path): UIO[Unit] =
    ZIO.attemptBlocking(
      Files.walk(directory).iterator.asScala.toList.reverse.foreach(Files.deleteIfExists),
    ).ignoreLogged

  class Impl(
      directory: Path,
      written: Ref.Synchronized[Map[String, ClientSSLCertConfig]],
  ) extends ClientCertificateFiles:

    override def present(certificate: PrivateClientCertificate.Material): Task[ClientSSLCertConfig] =
      val name = fingerprint(certificate)
      written.modifyZIO: cached =>
        cached.get(name) match
          case Some(config) =>
            ZIO.succeed(config -> cached)
          case None =>
            for
              certificatePath <- write(s"$name.crt", certificate.certificateChain)
              keyPath <- write(s"$name.key", certificate.privateKeyPem)
              config = ClientSSLCertConfig.FromClientCertFile(certificatePath.toString, keyPath.toString)
            yield config -> cached.updated(name, config)

    /** Names the file after what is in it, so a rotated certificate lands beside the old one
      * rather than racing a reader of the path it would otherwise reuse. */
    private def fingerprint(certificate: PrivateClientCertificate.Material): String =
      Base64.urlEncode(
        MessageDigest.getInstance("SHA-256").digest(
          (certificate.certificateChain + certificate.privateKeyPem).getBytes(StandardCharsets.UTF_8),
        ),
      )

    private def write(name: String, content: String): Task[Path] =
      ZIO.attemptBlocking(
        Files.write(
          Files.createFile(
            directory.resolve(name),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
          ),
          content.getBytes(StandardCharsets.UTF_8),
        ),
      )
