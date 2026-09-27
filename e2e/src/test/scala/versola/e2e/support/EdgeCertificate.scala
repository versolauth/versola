package versola.e2e.support

import com.nimbusds.jose.jwk.RSAKey
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.nio.file.{Files, Path}
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.util.{Base64, UUID}
import scala.sys.process.Process

/** The client half of RFC 8705 §2.2 self-signed mutual TLS: one key pair, the `jwks` a client
  * registers its public half as, and the certificate -- private key included -- central hands
  * to the edge fronting it (`edgeClientCertificate` on `OAuthClient.registerClient`), exactly
  * as it would a real edge's self-issued one.
  *
  * Held for a whole test, like [[AssertionSigner]] and [[DpopProver]], for the same reason: a
  * fresh pair per call would authenticate nothing.
  *
  * The certificate wrapping the key is built by shelling out to `openssl` rather than with a
  * JVM library: unlike the assertion signers above, minting an X.509 certificate needs one,
  * and none of e2e's existing dependencies do it. `openssl` is not a new dependency -- every
  * environment this suite runs in already needs it to generate the nginx certificate edge's
  * own internal TLS trust is pinned to (see `scripts/gen-env.scala`).
  *
  * Signed by that same run's CA (`edge/dev/internal-tls/ca.{crt,key}`) rather than
  * self-signed, though RFC 8705 §2.2 itself has no use for a CA at all -- auth matches this
  * certificate's public key against `jwks`, never its issuer. The CA exists on the nginx side
  * only (see that file's own comment: it is the one issuer nginx advertises in the
  * handshake's `CertificateRequest`), but a JDK-provider TLS client -- edge, absent
  * netty-tcnative -- offers a certificate only if its issuer is one the server advertised.
  * Self-signing here would make edge hold a certificate nginx's handshake would silently
  * decline to ask for, and the test would pass for a client that never proved anything.
  */
final class EdgeCertificate private (
    privateKeyPem: String,
    val certificatePem: String,
    publicJwk: RSAKey,
):

  /** The `jwks` document to register the client with (RFC 8705 §2.2: the key is the whole
    * credential, so this is what its certificate is matched against). */
  val jwks: Json.Obj =
    Json.Obj("keys" -> Json.Arr(publicJwk.toJSONString.fromJson[Json.Obj].toOption.get))

  /** The certificate and its private key as the single PEM `edgeClientCertificate` expects
    * (`PrivateClientCertificate` on the server side): the credential an edge fronting this
    * client presents at the TLS handshake. */
  val edgeClientCertificate: String = s"$certificatePem\n$privateKeyPem\n"

object EdgeCertificate:

  def make(commonName: String = s"e2e-edge-mtls-${UUID.randomUUID().toString.take(8)}"): Task[EdgeCertificate] =
    ZIO.attempt:
      val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
      generator.initialize(2048)
      val pair = generator.generateKeyPair().nn
      val privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey]
      val publicKey = pair.getPublic.asInstanceOf[RSAPublicKey]
      val privateKeyPem = pem("PRIVATE KEY", privateKey.getEncoded.nn)

      // Not relative to this process's own working directory: `Test / fork` in build.sbt
      // gives the e2e module's forked JVM a cwd of `e2e/` itself, not the repo root every
      // other relative path here (and gen-env.scala's own output) assumes. Found by walking
      // up from wherever that happens to be to the one directory that has `build.sbt`.
      val internalTls = repoRoot.resolve("edge/dev/internal-tls").nn
      val caCert = internalTls.resolve("ca.crt").nn
      val caKey = internalTls.resolve("ca.key").nn
      if !Files.exists(caCert) || !Files.exists(caKey) then
        throw RuntimeException(
          s"No CA at $internalTls -- run `echo local | scala-cli run scripts/gen-env.scala` first " +
            "(it generates the nginx TLS terminator's certificate and the CA that signs it).",
        )

      val directory = Files.createTempDirectory("e2e-edge-mtls-cert").nn
      try
        val keyFile = directory.resolve("key.pem").nn
        val csrFile = directory.resolve("csr.pem").nn
        val certFile = directory.resolve("cert.pem").nn
        Files.writeString(keyFile, privateKeyPem)
        def run(args: String*): Unit =
          val exit = Process(Seq("openssl") ++ args).!
          if exit != 0 then
            throw RuntimeException(s"openssl failed (exit $exit): ${args.mkString(" ")}")
        run("req", "-new", "-key", keyFile.toString, "-out", csrFile.toString, "-subj", s"/CN=$commonName")
        // Signed by the same CA nginx's own certificate is (see the class doc): its issuer is
        // what a JDK-provider TLS client is offered against the handshake's advertised list.
        run(
          "x509", "-req", "-in", csrFile.toString,
          "-CA", caCert.toString, "-CAkey", caKey.toString, "-CAcreateserial",
          "-out", certFile.toString, "-days", "2",
        )
        val certificatePem = Files.readString(certFile).nn

        EdgeCertificate(
          privateKeyPem,
          certificatePem,
          RSAKey.Builder(publicKey).keyID(UUID.randomUUID().toString).build(),
        )
      finally
        deleteRecursively(directory)

  private def pem(label: String, der: Array[Byte]): String =
    val body = Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(der)
    s"-----BEGIN $label-----\n$body\n-----END $label-----\n"

  private def deleteRecursively(directory: Path): Unit =
    import scala.jdk.CollectionConverters.*
    Files.walk(directory).nn.iterator.nn.asScala.toList.reverse.foreach(Files.deleteIfExists)

  private def repoRoot: Path =
    Iterator.iterate(Path.of("").nn.toAbsolutePath.nn)(_.getParent.nn)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw RuntimeException("Walked up to the filesystem root without finding build.sbt"))
