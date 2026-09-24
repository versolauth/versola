package versola.edge

import versola.util.{PrivateClientCertificate, TestCertificates}
import zio.*
import zio.http.ClientSSLCertConfig
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

/** The one place a client's private key reaches the disk. What matters is that the files are
  * readable by the TLS stack and by nothing else, and that a rotated certificate is not served
  * from the copy already written.
  */
object ClientCertificateFilesSpec extends ZIOSpecDefault:

  private val first = material(TestCertificates.generate())
  private val second = material(TestCertificates.generate(subject = "CN=other,O=Versola,C=KZ"))

  private def material(generated: TestCertificates.Generated): PrivateClientCertificate.Material =
    PrivateClientCertificate(generated.bundle).material.toOption.get

  private def files: ZIO[Scope, Throwable, ClientCertificateFiles] =
    ClientCertificateFiles.live.build.map(_.get[ClientCertificateFiles])

  private def paths(config: ClientSSLCertConfig): (Path, Path) = config match
    case ClientSSLCertConfig.FromClientCertFile(certificate, key) => (Path.of(certificate), Path.of(key))
    case other => throw RuntimeException(s"expected a certificate on disk, got $other")

  def spec = suite("ClientCertificateFiles")(
    test("writes the two halves the TLS stack reads, each holding what it was given") {
      ZIO.scoped:
        for
          store <- files
          config <- store.present(first)
          (certificatePath, keyPath) = paths(config)
          certificate <- ZIO.attemptBlocking(String(Files.readAllBytes(certificatePath), StandardCharsets.UTF_8))
          key <- ZIO.attemptBlocking(String(Files.readAllBytes(keyPath), StandardCharsets.UTF_8))
        yield assertTrue(
          certificate == first.certificateChain,
          key == first.privateKeyPem,
        )
    },
    test("leaves the key readable by this process and by nobody else") {
      // The whole reason a private key is allowed on disk at all: the file is the credential,
      // and a mode that lets another account read it hands the credential away.
      ZIO.scoped:
        for
          store <- files
          config <- store.present(first)
          (certificatePath, keyPath) = paths(config)
          keyMode <- ZIO.attemptBlocking(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyPath)))
          directoryMode <- ZIO.attemptBlocking(
            PosixFilePermissions.toString(Files.getPosixFilePermissions(keyPath.getParent)),
          )
          certificateMode <- ZIO.attemptBlocking(
            PosixFilePermissions.toString(Files.getPosixFilePermissions(certificatePath)),
          )
        yield assertTrue(
          keyMode == "rw-------",
          certificateMode == "rw-------",
          directoryMode == "rwx------",
        )
    },
    test("writes one certificate once, however many calls present it") {
      ZIO.scoped:
        for
          store <- files
          first1 <- store.present(first)
          first2 <- store.present(first)
          written <- ZIO.attemptBlocking(Files.list(paths(first1)._1.getParent).iterator.asScala.size)
        yield assertTrue(first1 == first2, written == 2)
    },
    test("writes a rotated certificate beside the old one rather than over it") {
      // Overwriting the path already in use would hand the TLS stack a key that does not match
      // the certificate beside it for as long as the two writes take.
      ZIO.scoped:
        for
          store <- files
          before <- store.present(first)
          after <- store.present(second)
        yield assertTrue(
          paths(before)._1 != paths(after)._1,
          paths(before)._2 != paths(after)._2,
        )
    },
    test("writes a pair Netty accepts, which is the only reader that matters") {
      // Asserting the file contents proves what was written, not that it can be presented:
      // Netty reads the PEM itself, from these paths, and fails on a container it cannot
      // parse -- this is the call zio-http makes out of a `FromClientCertFile`.
      ZIO.scoped:
        for
          store <- files
          config <- store.present(first)
          (certificatePath, keyPath) = paths(config)
          context <- ZIO.attemptBlocking(
            io.netty.handler.ssl.SslContextBuilder
              .forClient()
              .keyManager(certificatePath.toFile, keyPath.toFile)
              .build(),
          )
        yield assertTrue(context.isClient)
    },
    test("takes the files away with the scope that made them") {
      for
        directory <- ZIO.scoped(files.flatMap(_.present(first)).map(paths(_)._1.getParent))
        remains <- ZIO.attemptBlocking(Files.exists(directory))
      yield assertTrue(!remains)
    },
  )
