package versola.util.delivery

import versola.util.http.Observability
import zio.http.{Client, ConnectionPoolConfig, DnsResolver}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Chunk, Scope, Trace, ZEnvironment, ZIO, ZLayer}

import java.net.{ConnectException, InetAddress, InetSocketAddress, Socket, SocketAddress, UnknownHostException}
import javax.net.SocketFactory

/** Where a message provider or a mail server saved through the admin API may be.
  *
  * Both are addresses an administrator types and `auth` then connects to from inside the deployment's network. Left open they
  * are a way to make `auth` call the cloud metadata service, a database or any internal admin port, and to hand the one-time
  * codes it sends to a host of the administrator's choosing. So an address is refused when it is
  *
  *  - loopback, link-local (which holds the cloud metadata addresses), unspecified or multicast: always, there is no setting;
  *  - private (RFC 1918, carrier-grade NAT, IPv6 unique-local): unless the operator sets `allow-private-delivery-targets`,
  *    for a provider that lives inside the cluster or the VPC.
  *
  * The check is made twice. On a write it can only see what is written (an address literal, `localhost`), so it is a courtesy
  * that fails the form early. The one that holds is made when a connection is opened, on the address that connection is going
  * to ([[GuardedDnsResolver]] for HTTP, [[GuardedSocket]] for SMTP): a name that answers a public address to the check and a
  * private one to the connection is refused at the connection.
  *
  * Settings read from `auth.conf` are the operator's own and are not held to this.
  */
final case class DeliveryTargetPolicy(allowPrivate: Boolean):

  /** The reason an address may not be connected to, naming the class and never the address. */
  def refusal(address: InetAddress): Option[String] =
    if address.isLoopbackAddress then Some("loopback")
    else if address.isLinkLocalAddress then Some("link-local")
    else if address.isAnyLocalAddress then Some("unspecified")
    else if address.isMulticastAddress then Some("multicast")
    else if !allowPrivate && DeliveryTargetPolicy.isPrivate(address) then Some("private")
    else None

  /** What can be told of a host name or an address literal without asking the network: `localhost` and the names that
    * cloud providers answer metadata on, and a literal in any form the JDK reads as an address (dotted, integer, hex, IPv6).
    * A name that is none of these is `None` here and is judged when it is connected to.
    */
  def refusalOfHost(host: String): Option[String] =
    val name = host.trim.stripPrefix("[").stripSuffix("]").toLowerCase.stripSuffix(".")
    if name == "localhost" || name.endsWith(".localhost") then Some("loopback")
    else if DeliveryTargetPolicy.MetadataNames.contains(name) then Some("link-local")
    else DeliveryTargetPolicy.literal(name).flatMap(refusal)

object DeliveryTargetPolicy:
  /** Names that resolve to the cloud metadata address on the clouds that publish one. */
  private val MetadataNames = Set("metadata", "metadata.google.internal")

  private val DottedOrInteger = "^[0-9.]+$".r

  /** The address a host written as a literal stands for, read by the JDK so that every form it accepts (`2130706433`,
    * `127.1`, `[::1]`) is seen as the address it is. `None` for a name: nothing here resolves one. */
  private def literal(name: String): Option[InetAddress] =
    val isLiteral = name.contains(':') || DottedOrInteger.matches(name)
    if isLiteral then scala.util.Try(InetAddress.getByName(name)).toOption else None

  private def isPrivate(address: InetAddress): Boolean =
    val bytes = address.getAddress
    address.isSiteLocalAddress ||
    // IPv6 unique local addresses, fc00::/7
    (bytes.length == 16 && (bytes(0) & 0xfe) == 0xfc) ||
    // Carrier-grade NAT, 100.64.0.0/10 (RFC 6598)
    (bytes.length == 4 && (bytes(0) & 0xff) == 100 && (bytes(1) & 0xc0) == 64)

/** The system resolver of the HTTP client with the policy applied to what it answers: a name any of whose addresses is
  * refused is not connected to at all, so the client cannot be sent to one address after a check was made on another. */
final class GuardedDnsResolver(policy: DeliveryTargetPolicy, underlying: DnsResolver) extends DnsResolver:
  override def resolve(host: String)(using trace: Trace): ZIO[Any, UnknownHostException, Chunk[InetAddress]] =
    underlying.resolve(host).flatMap: addresses =>
      addresses.flatMap(policy.refusal).headOption match
        case Some(kind) => ZIO.fail(UnknownHostException(s"delivery target refused: the host resolves to a $kind address"))
        case None => ZIO.succeed(addresses)

/** A socket that applies the policy at `connect`, to the address it is asked to connect to. Jakarta Mail resolves the host name
  * itself and then connects; hooking the connect is what leaves no gap between the check and the connection. */
final class GuardedSocket(policy: DeliveryTargetPolicy) extends Socket:
  override def connect(endpoint: SocketAddress, timeout: Int): Unit =
    endpoint match
      case inet: InetSocketAddress =>
        Option(inet.getAddress).flatMap(policy.refusal).foreach: kind =>
          throw ConnectException(s"delivery target refused: the host resolves to a $kind address")
      case _ => ()
    super.connect(endpoint, timeout)

  override def connect(endpoint: SocketAddress): Unit = connect(endpoint, 0)

/** The factory Jakarta Mail takes as `mail.smtp.socketFactory`; it asks for an unconnected socket and connects it. */
final class GuardedSocketFactory(policy: DeliveryTargetPolicy) extends SocketFactory:
  override def createSocket(): Socket = GuardedSocket(policy)
  override def createSocket(host: String, port: Int): Socket = connected(InetAddress.getByName(host), port)
  override def createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
    connected(InetAddress.getByName(host), port)
  override def createSocket(host: InetAddress, port: Int): Socket = connected(host, port)
  override def createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = connected(host, port)

  private def connected(address: InetAddress, port: Int): Socket =
    val socket = GuardedSocket(policy)
    socket.connect(InetSocketAddress(address, port))
    socket

object GuardedClient:
  /** A client of its own for the message provider saved through the admin API: the shared client's tracing and logging, a
    * resolver that applies `policy`, and a small pool, since it has one peer. The shared client is not used for these calls
    * because its resolver is the system's. */
  def make(policy: DeliveryTargetPolicy): ZIO[Scope & Tracing, Throwable, Client] =
    val dns: ZLayer[Any, Nothing, DnsResolver] =
      DnsResolver.default.map(env => ZEnvironment[DnsResolver](GuardedDnsResolver(policy, env.get[DnsResolver])))
    for
      tracing <- ZIO.service[Tracing]
      env <- Observability.pooledClient(ConnectionPoolConfig.Fixed(8), dns).build
    yield env.get[Client] @@ Observability.clientMiddleware(tracing)
