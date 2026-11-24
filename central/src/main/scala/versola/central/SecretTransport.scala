package versola.central

import versola.central.configuration.edges.EdgeService
import versola.util.http.Unauthorized
import versola.util.{Base64Url, SecurityService}
import zio.{RIO, Task, ZIO}

import java.security.interfaces.RSAPublicKey

/** How a secret in a sync answer travels (#1050): encrypted to the one that asked, with RSA-OAEP-wrapped AES-256-GCM
  * ([[SecurityService.encryptRsaHybrid]], so that a value longer than one RSA block fits) and written base64url. What the caller
  * can read it with is the private half of the key its token was verified with: an `auth`'s `central.private-key`, an edge's own.
  * A caller that holds another key, or none, reads nothing.
  */
object SecretTransport:

  /** Encrypts to `key`. */
  def to(key: RSAPublicKey): RIO[SecurityService, Array[Byte] => Task[String]] =
    ZIO.serviceWith[SecurityService]: security =>
      (secret: Array[Byte]) => security.encryptRsaHybrid(secret, key).map(Base64Url.encode)

  /** Encrypts to the caller's key: the key an `auth` caller proved itself with, the registered one of an edge. An edge that is no
    * longer registered is refused, as it is wherever it is looked up. */
  def to(caller: Caller): RIO[SecurityService & EdgeService, Array[Byte] => Task[String]] =
    caller match
      case Caller.Edge(id) =>
        for
          edge <- ZIO.serviceWithZIO[EdgeService](_.find(id)).someOrFail(Unauthorized)
          key <- ZIO.attempt(edge.activeRsaPublicKey)
          encrypt <- to(key)
        yield encrypt
      case Caller.Cell(_, key) => to(key)
      case Caller.ConfiguredAuth(key) => to(key)
