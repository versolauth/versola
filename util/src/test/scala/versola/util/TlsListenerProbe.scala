package versola.util

import zio.*
import zio.http.*

/** A zio-http listener as a process of its own for [[TlsCipherPolicySpec]]. It is built the way the services build
  * theirs (`Server.Config` with `SSLConfig.fromFile`, Netty's default cipher list), and it prints `listening <port>`
  * once it accepts connections. Arguments: `plain` or `policy`, then the certificate and the key (PEM files). With
  * `policy` the real `TlsCipherPolicy.installFromEnv()` runs first, as the first step of a service's bootstrap does.
  *
  * It lives in a process of its own so that the client probing it can be a different JVM, one that has not applied the
  * policy: a client under the policy cannot offer a CBC suite, and its refusal would prove nothing about the listener.
  * Client authentication is left off: it takes no part in the choice of a cipher suite. */
object TlsListenerProbe extends ZIOAppDefault:

  override def run: ZIO[ZIOAppArgs & Scope, Throwable, Unit] =
    for
      args <- getArgs
      _ <- ZIO.when(args.headOption.contains("policy"))(ZIO.attempt(TlsCipherPolicy.installFromEnv()))
      ssl = SSLConfig.fromFile(
        behaviour = SSLConfig.HttpBehaviour.Fail,
        certPath = args(1),
        keyPath = args(2),
        clientAuth = None,
        trustCertCollectionPath = None,
        includeClientCert = false,
      )
      _ <- (for
        port <- Server.install(Routes(Method.GET / Root -> handler(Response.ok)))
        _ <- Console.printLine(s"listening $port")
        _ <- ZIO.never
      yield ()).provide(Server.live, ZLayer.succeed(Server.Config.default.port(0).ssl(ssl)))
    yield ()
