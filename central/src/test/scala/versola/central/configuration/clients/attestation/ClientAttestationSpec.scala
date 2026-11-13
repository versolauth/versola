package versola.central.configuration.clients.attestation

import versola.central.configuration.clients.attestation.AttestationFixtures.*
import zio.json.*
import zio.test.*

/** What a policy may say before it is stored: the shapes of the identities edge will later compare an attestation to, and
  * that a mode which asks for attestation has something to ask for. */
object ClientAttestationSpec extends ZIOSpecDefault:

  private def refused(attestation: ClientAttestation) = ClientAttestation.validated(attestation).left.toOption.getOrElse("")

  def spec = suite("ClientAttestation")(
    test("a well-formed policy is stored as it is") {
      assertTrue(ClientAttestation.validated(enforcing) == Right(enforcing))
    },
    test("a policy that is off may name no build, so an operator can create it first") {
      assertTrue(ClientAttestation.validated(ClientAttestation.off).isRight)
    },
    test("report and enforce without a build to accept are refused, since they would flag or turn away every app") {
      assertTrue(
        refused(ClientAttestation.off.copy(mode = AttestationMode.report)).contains("at least one"),
        refused(ClientAttestation.off.copy(mode = AttestationMode.enforce, ios = Some(ios.copy(appIds = Set.empty)))).contains("at least one"),
      )
    },
    test("an iOS app id is a Team ID and a bundle id") {
      assertTrue(
        refused(enforcing.copy(ios = Some(ios.copy(appIds = Set("com.example.app"))))).contains("<Team ID>.<bundle id>"),
        refused(enforcing.copy(ios = Some(ios.copy(appIds = Set("abcde12345.com.example"))))).contains("ios.appIds"),
      )
    },
    test("an Android package has at least two segments, and every app needs a certificate digest") {
      assertTrue(
        refused(enforcing.copy(android = List(android.copy(packageName = "example")))).contains("not a package name"),
        refused(enforcing.copy(android = List(android.copy(certificateDigests = Set.empty)))).contains("SHA-256"),
      )
    },
    test("a package listed twice is refused, since which digests apply would be a guess") {
      assertTrue(refused(enforcing.copy(android = List(android, android))).contains("more than once"))
    },
    test("digests are put in one form, however the operator copied them") {
      val colons = digest.toUpperCase.grouped(2).mkString(":")
      val stored = ClientAttestation.validated(enforcing.copy(android = List(android.copy(certificateDigests = Set(colons, digest)))))
      assertTrue(stored.map(_.android.map(_.certificateDigests)) == Right(List(Set(digest))))
    },
    test("a digest that is not 64 hex digits is refused") {
      assertTrue(refused(enforcing.copy(android = List(android.copy(certificateDigests = Set("abcd"))))).contains("not a SHA-256 digest"))
    },
    test("what one request can store is bounded, since the policy goes into every edge's sync") {
      val manyApps = List.tabulate(ClientAttestation.MaxApps + 1)(i => android.copy(packageName = s"com.example.app$i"))
      val manyIds = Set.tabulate(ClientAttestation.MaxApps + 1)(i => s"ABCDE12345.com.example.app$i")
      val manyDigests = Set.tabulate(ClientAttestation.MaxDigestsPerApp + 1)(i => f"$i%064x")
      assertTrue(
        refused(enforcing.copy(android = manyApps)).contains("at most"),
        refused(enforcing.copy(ios = Some(ios.copy(appIds = manyIds)))).contains("at most"),
        refused(enforcing.copy(android = List(android.copy(certificateDigests = manyDigests)))).contains("more than"),
        refused(enforcing.copy(android = List(android.copy(packageName = "com." + "a" * 300)))).contains("not a package name"),
      )
    },
    test("the wire form names the mode and leaves out the platform that is not set") {
      val json = ClientAttestation.off.toJson
      assertTrue(json == """{"mode":"off","android":[]}""", json.fromJson[ClientAttestation] == Right(ClientAttestation.off))
    },
    test("a field the policy does not know is refused rather than dropped") {
      val misspelt = """{"mode":"enforce","IOS":{"appIds":["ABCDE12345.com.example.app"],"environment":"production"},"android":[]}"""
      val nested = """{"mode":"off","android":[{"packageName":"com.example.app","certificateDigests":[],"extra":1}]}"""
      assertTrue(misspelt.fromJson[ClientAttestation].isLeft, nested.fromJson[ClientAttestation].isLeft)
    },
  )
