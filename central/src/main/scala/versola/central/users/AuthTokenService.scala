package versola.central.users

import versola.central.CentralConfig
import versola.util.JWT
import zio.json.ast.Json
import zio.{Clock, Ref, Task, URLayer, ZIO, ZLayer, durationInt}

import java.time.Instant

/** The token central presents to an `auth` (#1050): RS256 under the service key (`service-key`), `kid` the key's id, `iss` and
  * `sub` `central`, `aud` the one `auth` it is made for. An `auth` accepts a token whose audience is its own, so a token made
  * for one cell is refused by every other: what a cell learns of it is worth nothing at another. One token is kept for each
  * audience and made again before it runs out.
  */
trait AuthTokenService:
  /** The token for the `auth` whose audience this is: the cell id of a registered cell, the configured `auth`'s `auth.cell-id`,
    * or `auth` for a configured `auth` with no cell id ([[AuthTokenService.audienceOfConfigured]]). */
  def getToken(audience: String): Task[String]

  /** A new token for the audience, which replaces the one kept: for one the `auth` has refused. */
  def refreshToken(audience: String): Task[String]

object AuthTokenService:
  private val TokenTtl = 10.minutes
  private val ReloadAfter = 9.minutes

  /** The audience of the `auth` central is configured with: its cell id, else `auth`. */
  def audienceOfConfigured(config: CentralConfig): String = config.auth.cellId.fold(ConfiguredAudience)(identity[String])

  /** What an `auth` with no `cell-id` takes as its own audience. */
  val ConfiguredAudience = "auth"

  val live: URLayer[CentralConfig, AuthTokenService] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[CentralConfig]
        cache <- Ref.make(Map.empty[String, Kept])
      yield Impl(cache, config)

  /** @param issuedAt when it was made, which is when it is made again from, not when it runs out */
  private case class Kept(token: String, issuedAt: Instant)

  private def generateToken(config: CentralConfig, audience: String): Task[String] =
    JWT.serialize(
      claims = JWT.Claims(
        issuer = "central",
        subject = "central",
        audience = List(audience),
        custom = Json.Obj(),
      ),
      ttl = TokenTtl,
      signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, config.serviceKey.keyId, config.serviceKey.privateKey),
    )

  private class Impl(cache: Ref[Map[String, Kept]], config: CentralConfig) extends AuthTokenService:
    override def getToken(audience: String): Task[String] =
      for
        now <- Clock.instant
        kept <- cache.get.map(_.get(audience))
        token <- kept match
          case Some(Kept(token, issuedAt)) if issuedAt.plusSeconds(ReloadAfter.toSeconds).isAfter(now) => ZIO.succeed(token)
          case _ => refreshToken(audience)
      yield token

    override def refreshToken(audience: String): Task[String] =
      for
        token <- generateToken(config, audience)
        now <- Clock.instant
        _ <- cache.update(_.updated(audience, Kept(token, now)))
      yield token
