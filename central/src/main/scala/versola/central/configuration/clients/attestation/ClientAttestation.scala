package versola.central.configuration.clients.attestation

import zio.json.{JsonCodec, jsonNoExtraFields}
import zio.schema.*

/** What edge does with the attestation an app sends on `/native/start` (#423). */
enum AttestationMode derives JsonCodec, Schema, CanEqual:
  /** Nothing is asked and nothing is checked: what every client was before attestation, and what a client with no
    * policy is. */
  case off

  /** Edge verifies what the app sends and counts the outcome, but answers as if it had not: an operator sees how many
    * installs `enforce` would turn away before it does. */
  case report

  /** `/native/start` without a verifiable attestation of one of the builds below is refused. */
  case enforce

/** Which Apple environment an iOS app's App Attest keys come from. The two are told apart by the attestation itself, and
  * a key from the development one proves nothing about an App Store build. */
enum IosEnvironment derives JsonCodec, Schema, CanEqual:
  case production, development

/** The iOS builds a client accepts, by App ID (`<Team ID>.<bundle id>`), the identity App Attest binds a key to. */
@jsonNoExtraFields
case class IosAttestation(
    appIds: Set[String],
    environment: IosEnvironment,
) derives JsonCodec, Schema

/** An Android build a client accepts: the package, and the SHA-256 of the certificate the build is signed with. Several
  * digests so a signing-key rotation, or a Play-managed signing key beside the upload key, can overlap. */
@jsonNoExtraFields
case class AndroidApp(
    packageName: String,
    certificateDigests: Set[String],
) derives JsonCodec, Schema

/** A native client's attestation policy. The identities are kept while the mode is `off`, so an operator can prepare them
  * before turning it on.
  *
  * A field the policy does not know is refused rather than dropped: a misspelt `ios` would otherwise store a policy without the
  * builds the operator meant to set. */
@jsonNoExtraFields
case class ClientAttestation(
    mode: AttestationMode,
    ios: Option[IosAttestation],
    android: List[AndroidApp],
) derives JsonCodec, Schema:

  /** Whether any build is named: a mode other than `off` over no builds would refuse (or flag) every request. */
  def namesBuilds: Boolean = ios.exists(_.appIds.nonEmpty) || android.nonEmpty

object ClientAttestation:

  /** What a client with no policy has. */
  val off: ClientAttestation = ClientAttestation(mode = AttestationMode.off, ios = None, android = Nil)

  /** `<Team ID>.<bundle id>`: Apple's Team ID is ten characters from `A-Z0-9`, and a bundle id is reverse-DNS. */
  private val AppId = """[A-Z0-9]{10}\.[A-Za-z0-9][A-Za-z0-9.\-]{0,200}""".r

  /** A Java-style package name with at least two segments, which is what Play and the Keystore report. */
  private val PackageName = """[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""".r

  private val Sha256Hex = """[0-9a-f]{64}""".r

  /** The policy is copied into every edge's client sync, so what one request can store is bounded. The numbers are far past what a
    * real app needs (a handful of bundle ids, a signing key and its rotation). */
  val MaxApps = 20
  val MaxDigestsPerApp = 10
  val MaxLength = 255

  /** The policy as it is stored, or why it cannot be. Digests are put in one form (lower-case hex, no separators) because
    * `keytool` prints them with colons and `apksigner` without, and an operator pastes whichever they have. */
  def validated(attestation: ClientAttestation): Either[String, ClientAttestation] =
    for
      _ <- Either.cond(attestation.android.sizeIs <= MaxApps, (), s"android: at most $MaxApps apps")
      ios <- attestation.ios.fold(Right(None))(validatedIos(_).map(Some(_)))
      android <- validatedAndroid(attestation.android)
      validated = attestation.copy(ios = ios, android = android)
      _ <- Either.cond(
        validated.mode == AttestationMode.off || validated.namesBuilds,
        (),
        s"mode '${validated.mode}' needs at least one iOS app id or Android app to accept",
      )
    yield validated

  private def validatedIos(ios: IosAttestation): Either[String, IosAttestation] =
    if ios.appIds.sizeIs > MaxApps then Left(s"ios.appIds: at most $MaxApps app ids")
    else
      ios.appIds.toList.sorted.collectFirst { case appId if !AppId.matches(appId) => appId } match
        case Some(appId) => Left(s"ios.appIds: '$appId' is not <Team ID>.<bundle id>, e.g. ABCDE12345.com.example.app")
        case None => Right(ios)

  private def validatedAndroid(apps: List[AndroidApp]): Either[String, List[AndroidApp]] =
    val packages = apps.map(_.packageName)
    if packages.distinct.size != packages.size then Left("android: a package is listed more than once")
    else
      apps.foldRight[Either[String, List[AndroidApp]]](Right(Nil)): (app, rest) =>
        for
          tail <- rest
          head <- validatedApp(app)
        yield head :: tail

  private def validatedApp(app: AndroidApp): Either[String, AndroidApp] =
    if app.packageName.length > MaxLength || !PackageName.matches(app.packageName) then Left(s"android: '${app.packageName}' is not a package name, e.g. com.example.app")
    else if app.certificateDigests.isEmpty then Left(s"android: '${app.packageName}' needs the SHA-256 of its signing certificate")
    else if app.certificateDigests.sizeIs > MaxDigestsPerApp then Left(s"android: '${app.packageName}' has more than $MaxDigestsPerApp digests")
    else
      val normalized = app.certificateDigests.map(supplied => supplied -> supplied.filterNot(c => c == ':' || c.isWhitespace).toLowerCase)
      normalized.collectFirst { case (supplied, digest) if !Sha256Hex.matches(digest) => supplied } match
        case Some(bad) => Left(s"android: '${app.packageName}': '$bad' is not a SHA-256 digest (64 hex digits)")
        case None => Right(app.copy(certificateDigests = normalized.map(_._2)))
