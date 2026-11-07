package versola.e2e.support

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import versola.util.PrivateKeyUtil
import versola.util.audit.{ActorParams, ActorSignature}
import zio.*
import zio.http.Request

import java.io.File
import java.security.PrivateKey

/** What a spec that stands in for edge when it calls central directly needs to say who the caller is: central takes what is said of
  * the actor only from a call that a registered edge signed (`ActorSignature`), so the spec signs it with the key of the edge it runs
  * against, read from that edge's own configuration file (`edge/dev/env.conf`, as the staged stack was generated). */
object EdgeSigning:

  private case class Identity(edgeId: String, keyId: String, privateKey: PrivateKey)

  private def identity(config: E2EConfig): Task[Identity] =
    ZIO.attemptBlocking:
      val edge = ConfigFactory.parseFile(File(config.edgeEnvConf)).resolve(ConfigResolveOptions.defaults.setAllowUnresolved(true))
      val key = PrivateKeyUtil.parse(edge.getString("private-key"), "RSA").fold(throw _, key => key)
      Identity(edgeId = edge.getString("id"), keyId = edge.getString("key-id"), privateKey = key)

  /** `request` as edge forwards it: a call that states an actor carries the signature of the staged edge over the call as it is. */
  def forwarded(config: E2EConfig, request: Request): Task[Request] =
    if !ActorParams.stated.exists(request.url.queryParams.map.contains) then ZIO.succeed(request)
    else
      for
        edge <- identity(config)
        token <- ActorSignature.sign(
          edgeId = edge.edgeId,
          keyId = edge.keyId,
          privateKey = edge.privateKey,
          method = request.method.name,
          path = ActorSignature.pathOf(request.url.path.segments),
          query = request.url.queryParams,
        )
      yield request.updateURL(_.addQueryParam(ActorParams.Signature, token))
