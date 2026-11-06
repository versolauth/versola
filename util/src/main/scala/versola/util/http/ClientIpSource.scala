package versola.util.http

import zio.Config
import zio.config.magnolia.DeriveConfig

/** Where a service reads a caller's address from (`client-ip` in the edge's config file, `HTTP_AUTH_LIMIT_CLIENT_IP` in auth).
  *
  * `socket`: the peer of the connection. Right when callers reach the service directly. Behind a reverse proxy or load balancer it
  * is the proxy's address, the same for everyone, so an address limit would count the whole world as one caller.
  *
  * `forwarded`: the header named by `client-ip-header` (`X-Real-IP` by default), which nginx and ingress-nginx set from the
  * client chain they have already resolved. **Never trusted unless the service is reachable only through the proxy that sets it**
  * (compose network, chart network policy): any other caller could write the header and choose its own bucket.
  */
enum ClientIpSource(val name: String):
  case Socket extends ClientIpSource("socket")
  case Forwarded extends ClientIpSource("forwarded")

object ClientIpSource:
  given DeriveConfig[ClientIpSource] = DeriveConfig[String].mapOrFail: value =>
    values.find(_.name == value)
      .toRight(Config.Error.InvalidData(message = s"client-ip must be one of ${values.map(_.name).mkString(", ")}, not '$value'"))
