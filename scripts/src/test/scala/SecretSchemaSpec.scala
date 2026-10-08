import zio.json.*
import zio.test.*

import scala.util.Try

/** SecretSchema, the declarative list of secrets in gen-env.scala.
  *
  * The expected key sets below are written out by hand on purpose: they are the second copy that
  * makes a change to the schema (or to the Seqs gen-env.scala writes) show up here. What the
  * generator really writes is compared against the schema by .github/scripts/check-secret-schema.sh.
  */
object SecretSchemaSpec extends ZIOSpecDefault:

  private val authKeys = Set(
    "ACCESS_TOKENS_SECRET", "CLIENT_SECRETS_SECRET", "REFRESH_TOKENS_SECRET", "AUTH_CODES_SECRET",
    "SESSIONS_SECRET", "PASSWORDS_SECRET", "CONVERSATION_COOKIE_SECRET", "SESSION_COOKIE_SECRET",
    "USER_AGENT_COOKIE_SECRET", "PAR_REQUESTS_SECRET", "DPOP_NONCES_SECRET", "JWT_PRIVATE_KEY",
    "CENTRAL_SECRET_KEY",
  )
  private val centralKeys = Set(
    "CENTRAL_SECRET_KEY", "CLIENT_SECRETS_SECRET", "ACCOUNT_RESOURCE_SECRET", "CENTRAL_RESOURCE_SECRET",
    "UTILITY_CLIENT_PUBLIC_JWK", "JWKS_JSON", "EDGE_PUBLIC_JWK",
  )
  private val edgeKeys = Set(
    "EDGE_PRIVATE_KEY", "EDGE_KEY_ID", "EDGE_TOKEN_ENC_KEY", "EDGE_SESSIONS_SECRET", "EDGE_INTERNAL_SECRET",
    "EDGE_DPOP_NONCE_SALT", "EDGE_NATIVE_BLOB_KEY",
  )

  /** target -> service -> the keys that service's *.generated-secrets.env holds there. */
  private val expected: List[(SecretTarget, String, Set[String])] =
    List(
      (SecretTarget.DockerLocal, "auth", authKeys),
      (SecretTarget.DockerLocal, "central", centralKeys),
      (SecretTarget.DockerLocal, "edge", edgeKeys),
    ) ++ List(SecretTarget.Vps, SecretTarget.K8s).flatMap { target =>
      List(
        (target, "auth", authKeys + "POSTGRES_PASSWORD" + "ADMIN_BOOTSTRAP_PASSWORD"),
        (target, "central", centralKeys + "POSTGRES_PASSWORD"),
        (target, "edge", edgeKeys + "POSTGRES_PASSWORD"),
      )
    }

  private final case class Entry(
      name: String,
      services: List[String],
      `type`: String,
      size: Option[Int],
      group: Option[String],
      onMissing: String,
      file: Option[String],
  ) derives JsonDecoder

  private final case class Doc(schemaVersion: Int, target: String, secrets: List[Entry]) derives JsonDecoder

  private def parsed(target: SecretTarget): Doc =
    SecretSchema.toJson(target).fromJson[Doc].fold(error => throw RuntimeException(error), identity)

  /** The message `thunk` fails with, or None when it doesn't fail. */
  private def failureOf(thunk: => Unit): Option[String] =
    Try(thunk).failed.toOption.map(_.getMessage)

  private def specNamed(name: String): SecretSpec = SecretSchema.specs.find(_.name == name).get

  /** The entries called `name` that exist on `target`, in schema order. */
  private def onTarget(target: SecretTarget, name: String): List[SecretSpec] =
    SecretSchema.forTarget(target).filter(_.name == name)

  private def membersOf(group: String): Set[String] =
    SecretSchema.specs.filter(_.group.contains(group)).map(_.name).toSet

  /** A sound schema entry to break one rule of at a time. */
  private val sound = SecretSpec("A_SECRET", List("auth"), SecretType.Base64Url, Some(32), None, OnMissing.Generate, Set(SecretTarget.Vps))

  private val keyTests: List[Spec[Any, Nothing]] =
    expected.map { (target, service, keys) =>
      test(s"${target.json} / $service") {
        assertTrue(SecretSchema.keysFor(target, service) == keys)
      }
    }

  private val jsonTests: List[Spec[Any, Nothing]] =
    List(SecretTarget.DockerLocal, SecretTarget.Vps, SecretTarget.K8s).map { target =>
      test(s"${target.json}: parses and describes exactly the schema for the target") {
        val doc = parsed(target)
        val described = doc.secrets.map { entry =>
          (entry.name, entry.services, entry.`type`, entry.size, entry.group, entry.onMissing, entry.file)
        }
        val declared = SecretSchema.forTarget(target).map { s =>
          (s.name, s.services, s.tpe.json, s.size, s.group, s.onMissing.json, s.file)
        }
        assertTrue(
          doc.schemaVersion == SecretSchema.SchemaVersion,
          doc.target == target.json,
          described == declared,
        )
      }
    }

  def spec: Spec[Any, Nothing] = suite("SecretSchema")(
    suite("keys per target and service")(keyTests*),
    suite("verifyKeys")(
      test("accepts exactly the schema's keys") {
        assertTrue(failureOf(SecretSchema.verifyKeys(SecretTarget.Vps, "edge", (edgeKeys + "POSTGRES_PASSWORD").toList)).isEmpty)
      },
      test("names a key that is in the schema but not written") {
        val written = (edgeKeys + "POSTGRES_PASSWORD" - "EDGE_KEY_ID").toList
        val message = failureOf(SecretSchema.verifyKeys(SecretTarget.Vps, "edge", written))
        assertTrue(message.exists(_.contains("not written: [EDGE_KEY_ID]")))
      },
      test("names a key that is written but not in the schema") {
        val written = (edgeKeys + "POSTGRES_PASSWORD" + "BRAND_NEW_SECRET").toList
        val message = failureOf(SecretSchema.verifyKeys(SecretTarget.Vps, "edge", written))
        assertTrue(message.exists(_.contains("not in the schema: [BRAND_NEW_SECRET]")))
      },
      test("rejects a key written twice") {
        val written = (edgeKeys + "POSTGRES_PASSWORD").toList :+ "EDGE_KEY_ID"
        val message = failureOf(SecretSchema.verifyKeys(SecretTarget.Vps, "edge", written))
        assertTrue(message.exists(_.contains("written twice: [EDGE_KEY_ID]")))
      },
      test("a key only vps and k8s write is a mismatch on docker-local") {
        val message = failureOf(SecretSchema.verifyKeys(SecretTarget.DockerLocal, "edge", (edgeKeys + "POSTGRES_PASSWORD").toList))
        assertTrue(message.exists(_.contains("not in the schema: [POSTGRES_PASSWORD]")))
      },
    ),
    suite("verifySharedPostgresPassword")(
      test("accepts the same password for the three services") {
        assertTrue(failureOf(SecretSchema.verifySharedPostgresPassword("same", "same", "same")).isEmpty)
      },
      test("rejects a service with a different password, naming the flags and not the values") {
        val message = failureOf(SecretSchema.verifySharedPostgresPassword("one-password", "one-password", "another-password"))
        assertTrue(
          message.exists(_.contains("--edge-postgres-password")),
          message.exists(!_.contains("one-password")),
          message.exists(!_.contains("another-password")),
        )
      },
    ),
    suite("the schema itself")(
      test("has no problems") {
        assertTrue(SecretSchema.problems(SecretSchema.specs) == Nil)
      },
      test("every service a key goes to is one gen-env.scala writes for, or `utils`") {
        val holders = SecretSchema.Services.toSet + "utils"
        assertTrue(SecretSchema.specs.forall(_.services.forall(holders.contains)))
      },
      test("groups are the three cross-service sets") {
        assertTrue(
          membersOf("jwt") == Set("JWT_PRIVATE_KEY", "JWKS_JSON"),
          membersOf("edge-key") == Set("EDGE_PRIVATE_KEY", "EDGE_KEY_ID", "EDGE_PUBLIC_JWK"),
          membersOf("utils") == Set("UTILITY_CLIENT_PUBLIC_JWK", "UTILS_PRIVATE_KEY_JWK"),
          SecretSchema.specs.flatMap(_.group).toSet == Set("jwt", "edge-key", "utils"),
        )
      },
      test("values shared between services are listed once, with every service") {
        assertTrue(
          specNamed("CENTRAL_SECRET_KEY").services == List("auth", "central"),
          specNamed("CLIENT_SECRETS_SECRET").services == List("auth", "central"),
          // vps: one Postgres host, one generated password for all three.
          onTarget(SecretTarget.Vps, "POSTGRES_PASSWORD").map(_.services) == List(List("auth", "central", "edge")),
        )
      },
      test("k8s: each service has a Postgres password of its own, typed by the operator") {
        val entries = onTarget(SecretTarget.K8s, "POSTGRES_PASSWORD")
        assertTrue(
          entries.map(_.services) == List(List("auth"), List("central"), List("edge")),
          entries.forall(_.tpe == SecretType.Opaque),
          entries.forall(_.size.isEmpty),
          entries.forall(_.onMissing == OnMissing.External),
        )
      },
      test("the admin bootstrap password has no shape: --admin-password can set it on vps and k8s") {
        val k8s = onTarget(SecretTarget.K8s, "ADMIN_BOOTSTRAP_PASSWORD")
        val vps = onTarget(SecretTarget.Vps, "ADMIN_BOOTSTRAP_PASSWORD")
        assertTrue(
          k8s.map(e => (e.services, e.tpe, e.size, e.onMissing)) == List((List("auth"), SecretType.Opaque, None, OnMissing.External)),
          vps.map(e => (e.services, e.tpe, e.size, e.onMissing)) == List((List("auth"), SecretType.Opaque, None, OnMissing.Generate)),
        )
      },
      test("a secret an operator can set is opaque: the vps Postgres password too") {
        val vps = onTarget(SecretTarget.Vps, "POSTGRES_PASSWORD")
        assertTrue(vps.map(e => (e.tpe, e.size)) == List((SecretType.Opaque, None)))
      },
      test("secrets that stored data or an external system depends on are not generated on an upgrade; k8s values are the operator's") {
        val notGenerated = SecretSchema.specs.filter(_.onMissing != OnMissing.Generate)
        val firstInstallOnly = notGenerated.filter(_.onMissing == OnMissing.GenerateOnFirstInstallOnly).map(_.name).toSet
        assertTrue(
          firstInstallOnly == Set(
            "POSTGRES_PASSWORD", "PASSWORDS_SECRET", "CLIENT_SECRETS_SECRET", "CENTRAL_RESOURCE_SECRET",
            "JWT_PRIVATE_KEY", "JWKS_JSON", "EDGE_PRIVATE_KEY", "EDGE_KEY_ID", "EDGE_PUBLIC_JWK",
          ),
          // the ones whose loss only signs users out or invalidates in-flight tokens stay generated
          Set("REFRESH_TOKENS_SECRET", "AUTH_CODES_SECRET", "SESSIONS_SECRET", "PAR_REQUESTS_SECRET", "ACCOUNT_RESOURCE_SECRET")
            .forall(specNamed(_).onMissing == OnMissing.Generate),
          notGenerated.filter(_.onMissing == OnMissing.GenerateOnFirstInstallOnly).filter(_.name == "POSTGRES_PASSWORD")
            .map(_.targets) == List(Set(SecretTarget.Vps)),
          notGenerated.filter(_.onMissing == OnMissing.External).forall(_.targets == Set(SecretTarget.K8s)),
          notGenerated.filter(_.onMissing == OnMissing.External).map(_.name).toSet ==
            Set("POSTGRES_PASSWORD", "ADMIN_BOOTSTRAP_PASSWORD"),
        )
      },
      test("the utils private key is held by `utils` and written to its own file") {
        assertTrue(
          specNamed("UTILS_PRIVATE_KEY_JWK").services == List("utils"),
          specNamed("UTILS_PRIVATE_KEY_JWK").file.contains("utils.private-key.jwk"),
          SecretSchema.specs.filter(_.file.nonEmpty).map(_.name) == List("UTILS_PRIVATE_KEY_JWK"),
        )
      },
      test("local has no schema") {
        assertTrue(
          SecretSchema.parseTarget("local").isEmpty,
          SecretSchema.parseTarget("docker-local").contains(SecretTarget.DockerLocal),
          SecretSchema.parseTarget("vps").contains(SecretTarget.Vps),
          SecretSchema.parseTarget("k8s").contains(SecretTarget.K8s),
        )
      },
    ),
    suite("problems")(
      test("a duplicate name") {
        assertTrue(SecretSchema.problems(List(sound, sound)).exists(_.contains("duplicate name A_SECRET")))
      },
      test("the same name for different services is fine; for the same service on the same target it is not") {
        val forAuth    = sound.copy(targets = Set(SecretTarget.K8s))
        val forCentral = forAuth.copy(services = List("central"))
        val overlap    = forAuth.copy(services = List("auth", "central"))
        assertTrue(
          SecretSchema.problems(List(forAuth, forCentral)) == Nil,
          SecretSchema.problems(List(forAuth, overlap)).exists(_.contains("duplicate name A_SECRET")),
          // the same name and service on different targets is two different entries, not a clash
          SecretSchema.problems(List(sound, forAuth)) == Nil,
        )
      },
      test("an unknown service") {
        assertTrue(SecretSchema.problems(List(sound.copy(services = List("billing")))).exists(_.contains("unknown service billing")))
      },
      test("no services, no targets") {
        val found = SecretSchema.problems(List(sound.copy(services = Nil, targets = Set.empty)))
        assertTrue(found.exists(_.contains("no services")), found.exists(_.contains("no targets")))
      },
      test("a base64url or RSA entry without a size, and a size where none belongs") {
        assertTrue(
          SecretSchema.problems(List(sound.copy(size = None))).exists(_.contains("needs a size")),
          SecretSchema.problems(List(sound.copy(size = Some(0)))).exists(_.contains("size must be positive")),
          SecretSchema.problems(List(sound.copy(tpe = SecretType.PublicJwk))).exists(_.contains("takes no size")),
        )
      },
      test("a file that doesn't match who holds the value") {
        assertTrue(
          SecretSchema.problems(List(sound.copy(file = Some("x.jwk")))).exists(_.contains("names a file")),
          SecretSchema.problems(List(sound.copy(services = List("utils")))).exists(_.contains("names no file")),
        )
      },
      test("a group of one") {
        assertTrue(SecretSchema.problems(List(sound.copy(group = Some("lonely")))).exists(_.contains("group lonely: fewer than two")))
      },
      test("a group whose members differ in onMissing") {
        val first  = sound.copy(name = "A_ONE", group = Some("pair"))
        val second = sound.copy(name = "A_TWO", group = Some("pair"), onMissing = OnMissing.GenerateOnFirstInstallOnly)
        assertTrue(SecretSchema.problems(List(first, second)).exists(_.contains("group pair: members have different onMissing")))
      },
      test("a group whose members are on different targets") {
        val first  = sound.copy(name = "A_ONE", group = Some("pair"), targets = Set(SecretTarget.Vps))
        val second = sound.copy(name = "A_TWO", group = Some("pair"), targets = Set(SecretTarget.K8s))
        assertTrue(SecretSchema.problems(List(first, second)).exists(_.contains("group pair: members are not on the same targets")))
      },
    ),
    suite("secrets.schema.json")(jsonTests*),
    suite("secrets.schema.json contents")(
      test("docker-local has no Postgres or admin-bootstrap password, vps and k8s do") {
        val names = (t: SecretTarget) => parsed(t).secrets.map(_.name).toSet
        assertTrue(
          !names(SecretTarget.DockerLocal).contains("POSTGRES_PASSWORD"),
          !names(SecretTarget.DockerLocal).contains("ADMIN_BOOTSTRAP_PASSWORD"),
          names(SecretTarget.Vps).contains("POSTGRES_PASSWORD"),
          names(SecretTarget.K8s).contains("ADMIN_BOOTSTRAP_PASSWORD"),
        )
      },
      test("k8s: the Postgres password is three entries, one per service, so a consumer keys by (name, service)") {
        val entries = parsed(SecretTarget.K8s).secrets.filter(_.name == "POSTGRES_PASSWORD")
        assertTrue(
          entries.map(_.services) == List(List("auth"), List("central"), List("edge")),
          entries.map(_.`type`).distinct == List("opaque"),
          entries.map(_.onMissing).distinct == List("external"),
          entries.forall(_.size.isEmpty),
        )
      },
      test("what the CLI needs to decide is in the entries") {
        val entries = parsed(SecretTarget.Vps).secrets.map(entry => entry.name -> entry).toMap
        assertTrue(
          entries("POSTGRES_PASSWORD").onMissing == "generate-on-first-install-only",
          entries("POSTGRES_PASSWORD").services == List("auth", "central", "edge"),
          entries("ACCESS_TOKENS_SECRET").`type` == "base64url",
          entries("ACCESS_TOKENS_SECRET").size.contains(32),
          entries("JWT_PRIVATE_KEY").group.contains("jwt"),
          entries("UTILS_PRIVATE_KEY_JWK").file.contains("utils.private-key.jwk"),
        )
      },
    ),
  )
