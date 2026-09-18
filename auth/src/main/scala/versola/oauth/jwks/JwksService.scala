package versola.oauth.jwks

import com.nimbusds.jose.jwk.RSAKey as JwkRsaKey
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.TenantId
import versola.util.{CacheSource, CoreConfig, JWT, ReloadingCache}
import zio.{Scope, Task, UIO, ZIO, ZLayer}

import java.security.PrivateKey
import java.security.interfaces.RSAKey as JavaRsaKey
import scala.jdk.CollectionConverters.*

/** Provides the JWKS synced from central, plus the key this instance signs a given tenant's
  * tokens with.
  *
  * Verification uses the full key set ([[getPublicKeys]]). Signing uses the key the tenant
  * selected in central, whose private half central supplies alongside the JWKS -- kid,
  * algorithm and private key therefore all come from one record and cannot drift apart.
  *
  * Where a tenant has selected nothing, or central has no private half for what it selected,
  * signing falls back to the pre-selection behaviour: the one entry in the synced set whose
  * public modulus matches this instance's configured `jwt.private-key`. That is all a
  * deployment seeded from `bootstrap.jwks` can do -- central was never given those private
  * halves -- and it must not use whichever entry central reports as
  * [[JWT.PublicKeys.active]], which is just the first element of an unordered list and can
  * drift ahead of this instance's own key across a rotation (see #104).
  */
trait JwksService:
  def getPublicKeys: UIO[JWT.PublicKeys]

  /** The complete signature to sign this tenant's tokens with: kid, algorithm and private key
    * resolved together, so a caller cannot pair one key's kid with another's algorithm.
    *
    * Fails when neither the tenant's selected key nor the legacy fallback is usable -- which
    * can happen transiently after a rotation, before this instance has synced the new key.
    */
  def signingKey(tenantId: TenantId): Task[JWT.Signature.Asymmetric]

  /** Re-reads the key set from central immediately, instead of waiting for the next scheduled
    * refresh.
    *
    * A key generated in central is selectable there the moment it exists, but not signable
    * here until it has been synced -- so a tenant switched onto a brand new key keeps issuing
    * under the old one for up to one refresh interval. This is what the configuration sync
    * calls to close that window.
    */
  def refresh: Task[Unit]

object JwksService:
  case class Snapshot(
      publicKeys: JWT.PublicKeys,
      privateKeys: Map[String, PrivateKey],
      fallback: Option[JWT.Signature.Asymmetric],
  ):
    /** The signature for `kid`, when central published both halves of it and said what
      * algorithm it is for. A kid that fails any of those is not signable, and the caller
      * falls back rather than guessing.
      */
    def signatureFor(kid: String): Option[JWT.Signature.Asymmetric] =
      for
        privateKey <- privateKeys.get(kid)
        jwk <- Option(publicKeys.keys.getKeyByKeyId(kid))
        algorithm <- JWT.PublicKey(jwk).algorithm
      yield JWT.Signature.Asymmetric(algorithm, kid, privateKey)

  /** The JWKS entry whose modulus matches `privateKey`'s, i.e. the one this instance can sign
    * with using its configured key. During a rotation window central's JWKS holds both the
    * outgoing and incoming key; this always resolves to the one whose private half this
    * instance actually has, never to whichever one happens to be listed first.
    *
    * An entry without a usable `alg` is skipped rather than defaulted: the algorithm a key is
    * published under is the operator's statement about it, and guessing one would mean signing
    * under a header the JWKS contradicts.
    */
  private[jwks] def resolveSigningKey(
      privateKey: PrivateKey,
      publicKeys: JWT.PublicKeys,
  ): Option[JWT.Signature.Asymmetric] =
    privateKey match
      case rsaPrivateKey: JavaRsaKey =>
        publicKeys.keys.getKeys.asScala.collectFirst {
          case key: JwkRsaKey if key.toRSAPublicKey.getModulus == rsaPrivateKey.getModulus =>
            JWT.PublicKey(key)
        }.flatMap { publicKey =>
          publicKey.algorithm.map { algorithm =>
            JWT.Signature.Asymmetric(algorithm, publicKey.id, privateKey)
          }
        }
      case _ => None

  /** Wraps the raw sync with the derived fallback signature, so the match against this
    * instance's configured private key is computed once per cache refresh instead of once per
    * JWT signed.
    */
  private class SigningAwareSource(client: JwksSyncClient, privateKey: PrivateKey) extends CacheSource[Snapshot]:
    override def getAll: Task[Snapshot] =
      client.getAll.map { keys =>
        Snapshot(keys.publicKeys, keys.privateKeys, resolveSigningKey(privateKey, keys.publicKeys))
      }

  def live: ZLayer[JwksSyncClient & OAuthConfigurationService & Scope & CoreConfig, Throwable, JwksService] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[CoreConfig]
        client <- ZIO.service[JwksSyncClient]
        configurationService <- ZIO.service[OAuthConfigurationService]
        // The same source backs the scheduled refresh and the on-demand one, so a forced
        // sync cannot compute the fallback differently from the timer that follows it.
        source = new SigningAwareSource(client, config.jwt.privateKey): CacheSource[Snapshot]
        cache <- ReloadingCache.make[Snapshot](config.configurationCacheRefreshInterval)
          .provideSome[Scope](ZLayer.succeed(source))
      yield Impl(cache, configurationService, source)

  case class Impl(
      cache: ReloadingCache[Snapshot],
      configurationService: OAuthConfigurationService,
      source: CacheSource[Snapshot],
  ) extends JwksService:
    override def getPublicKeys: UIO[JWT.PublicKeys] = cache.get.map(_.publicKeys)

    override def signingKey(tenantId: TenantId): Task[JWT.Signature.Asymmetric] =
      for
        selected <- configurationService.getSigningKeyId(tenantId)
        snapshot <- cache.get
        // A selected kid that is not signable here (not yet synced, or its private half was
        // never published) falls back rather than failing: the tenant keeps issuing tokens
        // under the previous key until the new one arrives.
        signature <- ZIO.fromOption(selected.flatMap(snapshot.signatureFor).orElse(snapshot.fallback))
          .orElseFail(
            RuntimeException(
              s"No signing key for tenant '$tenantId': neither its selected key nor a JWKS entry " +
                "matching this instance's configured private key is available",
            ),
          )
      yield signature

    override def refresh: Task[Unit] =
      source.getAll.flatMap(cache.set)
