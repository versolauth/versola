package versola.util

import java.io.File
import java.net.URLClassLoader
import java.security.{Provider, Security, Signature}
import scala.util.Try

/** Optional native JCA provider: Amazon Corretto Crypto Provider (ACCP, AWS-LC underneath).
  *
  * It replaces the JDK's RSA and ECDSA code, which is where most of the CPU a token endpoint or an edge
  * proxy spends goes: measured on one auth + one edge, 30% fewer cores per request and a ceiling about
  * 15-20% higher. ES256 only pays off with it -- without it ES256 signs 8x cheaper but verifies 8x
  * dearer, so the cost moves from auth to edge instead of going away.
  *
  * `VERSOLA_CRYPTO_PROVIDER` picks the mode:
  *  - unset or `jdk`: the JDK providers, nothing is loaded;
  *  - `accp`: use ACCP when it loads and works, otherwise say why and carry on with the JDK (development,
  *    where the native library for the machine may not exist);
  *  - `accp-required`: the same, but a provider that is missing, fails to load, fails its own health check
  *    or does not end up serving ECDSA and RSASSA-PSS refuses the start. What the images run: a service
  *    that quietly fell back would be five times dearer on the edge and would only show as a latency
  *    incident.
  *
  * The jar is not on the classpath: the images carry the one for their CPU architecture at
  * `VERSOLA_ACCP_JAR` (default `/app/accp/accp.jar`), picked at build time, because the jars of the two
  * architectures hold their native library under the same resource name and cannot sit side by side.
  */
object CryptoProvider:

  enum Mode:
    case Jdk, Accp, AccpRequired

  object Mode:
    def parse(value: Option[String]): Either[String, Mode] =
      value.map(_.trim.toLowerCase).filter(_.nonEmpty) match
        case None | Some("jdk") => Right(Jdk)
        case Some("accp") => Right(Accp)
        case Some("accp-required") => Right(AccpRequired)
        case Some(other) => Left(s"VERSOLA_CRYPTO_PROVIDER=$other: expected jdk, accp or accp-required")

  /** What the process ended up with, for the log and the `crypto_provider_native` gauge. */
  case class Outcome(native: Boolean, description: String)

  /** The algorithms that must be served by the native provider for it to be worth having. */
  private val Probes = List("SHA256withECDSA", "RSASSA-PSS", "SHA256withRSA")

  @volatile private var outcome: Outcome = Outcome(native = false, "crypto provider: JDK default")

  def current: Outcome = outcome

  /** Applies the mode named by the environment. Throws only in `accp-required` mode. */
  def installFromEnv(): Outcome =
    val mode = Mode.parse(Option(java.lang.System.getenv("VERSOLA_CRYPTO_PROVIDER"))).fold(
      message => throw new IllegalStateException(message),
      identity,
    )
    val jar = Option(java.lang.System.getenv("VERSOLA_ACCP_JAR")).filter(_.nonEmpty).getOrElse("/app/accp/accp.jar")
    val result = install(mode, () => loadProvider(jar), Security.insertProviderAt(_, 1), Security.removeProvider)
    java.lang.System.err.println(result.description)
    outcome = result
    result

  /** Mode logic apart from where the provider comes from, so it can be tried without the native library. */
  private[util] def install(
      mode: Mode,
      load: () => Either[String, Provider],
      insert: Provider => Unit,
      remove: String => Unit,
  ): Outcome =
    mode match
      case Mode.Jdk => Outcome(native = false, "crypto provider: JDK default")
      case Mode.Accp | Mode.AccpRequired =>
        val attempt = load().flatMap(provider => activate(provider, insert, remove))
        attempt match
          case Right(provider) =>
            Outcome(native = true, s"crypto provider: ${provider.getName} ${provider.getVersionStr} serving ${Probes.mkString(", ")}")
          case Left(reason) if mode == Mode.AccpRequired =>
            throw new IllegalStateException(s"VERSOLA_CRYPTO_PROVIDER=accp-required, but the native provider is not usable: $reason")
          case Left(reason) =>
            Outcome(native = false, s"crypto provider: ACCP requested but not usable ($reason), using the JDK providers")

  /** Inserts the provider at the front, then checks that it really answers for the algorithms we sign and
    * verify with. Takes it out again when it does not, so a half-working provider never stays in the chain.
    */
  private def activate(provider: Provider, insert: Provider => Unit, remove: String => Unit): Either[String, Provider] =
    insert(provider)
    val wrong = Probes.flatMap: algorithm =>
      Try(Signature.getInstance(algorithm).getProvider.getName).fold(
        error => Some(s"$algorithm: ${error.getMessage}"),
        name => Option.when(name != provider.getName)(s"$algorithm served by $name"),
      )
    if wrong.isEmpty then Right(provider)
    else
      remove(provider.getName)
      Left(wrong.mkString("; "))

  private def loadProvider(jar: String): Either[String, Provider] =
    val className = "com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider"
    Try {
      val loader =
        if File(jar).isFile then URLClassLoader(Array(File(jar).toURI.toURL), getClass.getClassLoader)
        else getClass.getClassLoader
      val clazz = Class.forName(className, true, loader)
      val provider = clazz.getField("INSTANCE").get(null).asInstanceOf[Provider]
      Option(clazz.getMethod("getLoadingError").invoke(provider).asInstanceOf[Throwable]) match
        case Some(error) => Left(s"native library did not load: $error")
        case None =>
          // Its own self-test: throws when the library loaded but a primitive misbehaves.
          Try(clazz.getMethod("assertHealthy").invoke(provider)).fold(
            error => Left(s"health check failed: ${Option(error.getCause).getOrElse(error)}"),
            _ => Right(provider),
          )
    }.fold(error => Left(s"${error.getClass.getSimpleName}: ${error.getMessage} (jar $jar)"), identity)
