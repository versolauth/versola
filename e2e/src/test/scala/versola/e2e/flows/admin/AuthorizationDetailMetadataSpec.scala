package versola.e2e.flows.admin

import versola.e2e.support.*
import zio.*
import zio.test.*

import java.util.UUID

/** Verifies that authorization-detail type writes are reflected in server metadata. */
object AuthorizationDetailMetadataSpec extends E2ESpec:

  def spec = suite("Authorization detail metadata")(
    test("adding and removing an authorization detail type updates server metadata") {
      val typeName = s"e2e_${UUID.randomUUID().toString.replace('-', '_')}"
      val schema = """{"type":"object"}"""

      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.registerAuthorizationDetailType(typeName, schema).success
        _ <- auth.awaitAuthorizationDetailTypeInMetadata(typeName, expected = true)
        _ <- auth.deleteAuthorizationDetailType(typeName)
        _ <- auth.awaitAuthorizationDetailTypeInMetadata(typeName, expected = false)
      yield assertCompletes
    },
  ).@@(TestAspect.withLiveClock)