package versola.central.configuration.metadata

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.sync.SyncEvent
import versola.util.ReloadingCache
import zio.*
import zio.json.ast.Json
import zio.test.*

object ServerMetadataServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val metadataKey = "authorization_details_types_supported"

  private def metadata(fields: (String, Json)*): Json.Obj = Json.Obj(fields*)

  class Env(initial: Option[ServerMetadataRecord]):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ServerMetadataRepository]
    val service = ServerMetadataService.Impl(cache, repository)

  def spec = suite("ServerMetadataService")(
    test("updates supported authorization detail types in stored metadata") {
      val existing = metadata(
        "issuer" -> Json.Str("https://issuer.example"),
        metadataKey -> Json.Arr(Json.Str("payment")),
      )
      val env = new Env(Some(ServerMetadataRecord("default", existing)))

      for
        _ <- env.repository.get.succeedsWith(Some(ServerMetadataRecord("default", existing)))
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.service.updateAuthorizationDetailType("account", SyncEvent.Op.INSERT)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.upsert.calls.headOption.flatMap(_.get(metadataKey).flatMap(_.as[Set[String]].toOption)) ==
          Some(Set("account", "payment")),
        env.repository.upsert.calls.headOption.flatMap(_.get("issuer")) ==
          Some(Json.Str("https://issuer.example")),
        cached == Some(ServerMetadataRecord("default", existing)),
      )
    },
    test("removes the last supported authorization detail type") {
      val existing = metadata(
        "issuer" -> Json.Str("https://issuer.example"),
        metadataKey -> Json.Arr(Json.Str("payment")),
      )
      val env = new Env(Some(ServerMetadataRecord("default", existing)))

      for
        _ <- env.repository.get.succeedsWith(Some(ServerMetadataRecord("default", existing)))
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.service.updateAuthorizationDetailType("payment", SyncEvent.Op.DELETE)
      yield assertTrue(
        env.repository.upsert.calls == List(
          metadata(
            "issuer" -> Json.Str("https://issuer.example"),
            metadataKey -> Json.Arr(),
          )
        )
      )
    },
  )