package versola.util

import java.security.Security
import javax.net.ssl.SSLContext

/** Which TLS 1.2 cipher suites this process negotiates: the AES-CBC ones are refused, as RFC 9325 §4.2 (BCP 195, which
  * FAPI 2.0 requires) asks. The mutual-TLS listeners of auth, central and edge offer Netty's default list, which holds
  * `TLS_ECDHE_RSA_WITH_AES_{128,256}_CBC_SHA`; zio-http has no cipher setting, and Netty sets its list explicitly, so
  * `jdk.tls.server.cipherSuites` changes nothing. What does reach them is the JDK property `jdk.tls.disabledAlgorithms`:
  * the two CBC bulk ciphers are added to what the JDK already disables.
  *
  * The property is process-wide, so it also applies to the connections this process opens: a peer that offers only CBC
  * in TLS 1.2 cannot be reached. `VERSOLA_TLS_CIPHER_POLICY=jdk` restores the JDK's own list. This removes the CBC
  * suites, it does not cut the list down to the four BCP 195 suites (JDK-stack clients still offer DHE and ChaCha20),
  * and a TLS terminator in front of a service (nginx, an ingress) has a cipher list of its own.
  *
  * The JDK reads the property once, at the first use of TLS, and silently ignores a later change. So this runs first
  * (see `VersolaApp`) and checks its own effect: when a CBC suite is still supported afterwards, the start is refused.
  */
object TlsCipherPolicy:

  enum Mode:
    case Bcp195, Jdk

  object Mode:
    def parse(value: Option[String]): Either[String, Mode] =
      value.map(_.trim.toLowerCase).filter(_.nonEmpty) match
        case None | Some("bcp195") => Right(Bcp195)
        case Some("jdk") => Right(Jdk)
        case Some(other) => Left(s"VERSOLA_TLS_CIPHER_POLICY=$other: expected bcp195 or jdk")

  /** What the process ended up with, for the log and the `tls_cbc_refused` gauge. */
  case class Outcome(refused: Boolean, description: String)

  private val Property = "jdk.tls.disabledAlgorithms"

  /** The bulk ciphers of the CBC suites RFC 9325 §4.2 does not recommend. The JDK matches these names against
    * every suite that uses the cipher, whatever its key exchange or MAC. */
  private val Refused = List("AES_128_CBC", "AES_256_CBC")

  /** `current` with the refused ciphers added, once each; what was there stays, in its order. */
  private[util] def withRefused(current: Option[String]): String =
    val entries = current.toList.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty)
    (entries ++ Refused.filterNot(entries.contains)).mkString(", ")

  /** Applies the policy named by the environment. Throws when the value is not one of the two, and in `bcp195`
    * mode when a CBC suite is still offered after the property is written. */
  def installFromEnv(): Outcome =
    val mode = Mode.parse(sys.env.get("VERSOLA_TLS_CIPHER_POLICY")).fold(
      message => throw new IllegalStateException(message),
      identity,
    )
    val outcome = install(mode, Option(Security.getProperty(Property)), Security.setProperty(Property, _), cbcSuitesOffered)
    // The logger is not up yet at this point of the start; CryptoProvider reports the same way.
    Console.err.println(outcome.description)
    outcome

  /** The policy apart from the JDK's security properties and TLS machinery, so it can be tried without them. */
  private[util] def install(
      mode: Mode,
      current: Option[String],
      write: String => Unit,
      offered: () => List[String],
  ): Outcome =
    mode match
      case Mode.Jdk => Outcome(refused = false, description = "tls cipher policy: JDK default")
      case Mode.Bcp195 =>
        write(withRefused(current))
        offered() match
          case Nil =>
            Outcome(
              refused = true,
              description = s"tls cipher policy: TLS 1.2 ${Refused.mkString(", ")} cipher suites refused (RFC 9325 4.2)",
            )
          case suites =>
            throw new IllegalStateException(
              s"VERSOLA_TLS_CIPHER_POLICY=bcp195, but the JDK still offers CBC cipher suites (${suites.take(3).mkString(", ")}): " +
                "TLS was used before the policy could be applied. VERSOLA_TLS_CIPHER_POLICY=jdk starts without the policy",
            )

  /** The CBC suites a TLS context built now supports. Building it is also what makes the JDK latch the property,
    * so after this a later change of it would be ignored. */
  private def cbcSuitesOffered(): List[String] =
    val context = SSLContext.getInstance("TLS")
    context.init(null, null, null)
    context.getSupportedSSLParameters.getCipherSuites.toList.filter(_.contains("_CBC_"))
