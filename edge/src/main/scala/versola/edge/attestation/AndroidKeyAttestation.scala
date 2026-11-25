package versola.edge.attestation

import org.bouncycastle.asn1.{ASN1Boolean, ASN1Enumerated, ASN1Integer, ASN1OctetString, ASN1Sequence, ASN1Set}
import versola.edge.attestation.AttestationCrypto.*
import versola.edge.attestation.AttestationFailure.Kind

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Android's Key Attestation, the server half: a certificate chain the Keystore made for the DPoP key itself, ending at a root of
  * Google's, whose first certificate carries a `KeyDescription` that says where the key lives, what state the device booted in and
  * which app asked for it.
  *
  * The key that is attested is the key that signs the DPoP proofs, so binding is direct: the certificate's public key has the
  * `jkt` of the proof the request carries. Nothing but the holder of that key can produce such a proof, which is why the chain
  * itself is not secret and needs no freshness of its own beyond the challenge that names the client it was made for.
  *
  * Written against the schema in Android's "Verify hardware-backed key pairs with Key Attestation". Pure: the caller supplies the
  * roots and the revocation list.
  */
private[attestation] object AndroidKeyAttestation:

  private val KeyDescriptionExtension = "1.3.6.1.4.1.11129.2.1.17"

  /** The extension of a remotely provisioned (RKP) chain's intermediate, which says that the certificate below it is the key attestation. */
  private val ProvisioningInfoExtension = "1.3.6.1.4.1.11129.2.1.30"

  /** The subject serial number of the root that factory-provisioned attestation keys chain to. Google: keep trusting chains to it
    * "regardless of certificate validity period", since devices launched before 2021 carry expired attestation certificates. */
  private val FactoryRootSerialNumber = "f92009e853b6b045"

  /** `KeyDescription` members by position: the security levels are `Software` (0), `TrustedEnvironment` (1) and `StrongBox` (2). */
  private val AttestationSecurityLevel = 1
  private val KeyMintSecurityLevel = 3
  private val ChallengeIndex = 4
  private val SoftwareEnforcedIndex = 6
  private val HardwareEnforcedIndex = 7

  /** `AuthorizationList` tags. */
  private val OriginTag = 702
  private val RootOfTrustTag = 704
  private val ApplicationIdTag = 709

  private val VerifiedBoot = 0

  /** `Origin` `GENERATED`: the key was made inside the secure hardware, so its private half has never been anywhere else. An
    * imported key (`IMPORTED`, 2) or a securely imported one (`SECURELY_IMPORTED`, 4) is held in hardware but was known to whoever
    * imported it. */
  private val Generated = 0

  /** What an accepted attestation says about the key, for the caller to count. */
  final case class Verified(strongBox: Boolean, app: AndroidApp)

  /** @param challenge what the app handed the Keystore as the attestation challenge: bound to the client, see
    *   [[AttestationService.androidChallenge]]. */
  def verify(
      chain: List[Array[Byte]],
      challenge: Array[Byte],
      apps: List[AndroidApp],
      jkt: String,
      roots: Set[X509Certificate],
      revoked: BigInteger => Boolean,
      now: Instant,
  ): Either[AttestationFailure, Verified] =
    for
      certificates <- certificates(chain)
      _ <- extensionsInPlace(certificates)
      _ <- validate(certificates, roots, now, ignoreValidity = factoryProvisioned(certificates, roots))
      _ <- Either.cond(
        !certificates.exists(certificate => revoked(certificate.getSerialNumber)),
        (),
        AttestationFailure(Kind.Revoked, "a certificate of the chain is revoked"),
      )
      leaf = certificates.head

      key <- thumbprint(leaf.getPublicKey).toRight(AttestationFailure(Kind.Unsupported, "only a P-256 key can be attested"))
      _ <- Either.cond(
        equal(key.getBytes(StandardCharsets.UTF_8), jkt.getBytes(StandardCharsets.UTF_8)),
        (),
        AttestationFailure(Kind.Mismatch, "the attested key is not the DPoP key of the request"),
      )

      description <- extensionValue(leaf, KeyDescriptionExtension).flatMap(sequence).toRight(malformed("the leaf certificate has no key description"))
      _ <- Either.cond(description.size > HardwareEnforcedIndex, (), malformed("the key description is too short"))
      _ <- Either.cond(
        enumerated(description, AttestationSecurityLevel).exists(_ >= 1) && enumerated(description, KeyMintSecurityLevel).exists(_ >= 1),
        (),
        AttestationFailure(Kind.DeviceNotAccepted, "the key is not held in hardware"),
      )
      attested <- octets(description, ChallengeIndex).toRight(malformed("the key description has no challenge"))
      _ <- Either.cond(equal(attested, challenge), (), AttestationFailure(Kind.Mismatch, "the attestation was made for another client"))

      software <- list(description, SoftwareEnforcedIndex)
      hardware <- list(description, HardwareEnforcedIndex)
      _ <- Either.cond(
        origin(hardware).contains(Generated),
        (),
        AttestationFailure(Kind.DeviceNotAccepted, "the key was not generated in the secure hardware"),
      )
      // The root of trust is the secure hardware's own statement; the software-enforced list is not where it is, and is not read.
      _ <- bootState(hardware) match
        case Some((locked, state)) if locked && state == VerifiedBoot => Right(())
        case Some(_) => Left(AttestationFailure(Kind.DeviceNotAccepted, "the device is unlocked or did not boot verified"))
        case None => Left(AttestationFailure(Kind.DeviceNotAccepted, "the attestation carries no root of trust"))
      application <- applicationId(software).orElse(applicationId(hardware)).toRight(malformed("the attestation names no app"))
      app <- accepted(application, apps)
    yield Verified(strongBox = enumerated(description, AttestationSecurityLevel).contains(2), app = app)

  /** Google: "only the first occurrence of the extension in the chain can be trusted. Any further instances have not been issued by the
    * secure hardware and might have been issued by an attacker extending the chain". The first one, counted from the root, is the leaf
    * when the chain has one only, and a chain with a second is refused rather than guessed at: an app that holds an attested key able
    * to sign could issue a leaf for a software key, with a description of its choosing, under it. A provisioning-info extension (RKP)
    * says that the certificate right below it is the one that carries the key description, which is the leaf's place.
    *
    * A chain made with an attest key the app holds (the description one certificate up) is refused by this, on purpose: it is the shape
    * of the forgery, and the SDK does not use attest keys.
    */
  private def extensionsInPlace(certificates: List[X509Certificate]): Either[AttestationFailure, Unit] =
    val descriptions = certificates.indices.filter(index => certificates(index).getExtensionValue(KeyDescriptionExtension) != null)
    val provisioning = certificates.indices.filter(index => certificates(index).getExtensionValue(ProvisioningInfoExtension) != null)
    if descriptions.isEmpty then Left(malformed("the leaf certificate has no key description"))
    else if descriptions != Seq(0) then Left(malformed("the key description is not in the leaf certificate alone"))
    else if provisioning.exists(_ != 1) then Left(malformed("the provisioning information is not right above the leaf certificate"))
    else Right(())

  /** A chain made at the factory (no provisioning information) to Google's older root: its certificates may have expired. */
  private def factoryProvisioned(certificates: List[X509Certificate], roots: Set[X509Certificate]): Boolean =
    !certificates.exists(_.getExtensionValue(ProvisioningInfoExtension) != null) &&
      anchorOf(certificates, roots).exists: root =>
        org.bouncycastle.asn1.x500.X500Name.getInstance(root.getSubjectX500Principal.getEncoded)
          .getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.SERIALNUMBER)
          .exists(rdn => rdn.getFirst.getValue.toString == FactoryRootSerialNumber)

  private def list(description: ASN1Sequence, index: Int): Either[AttestationFailure, ASN1Sequence] =
    Try(ASN1Sequence.getInstance(description.getObjectAt(index))).toEither.left.map(_ => malformed("the key description has no authorization list"))

  private def enumerated(description: ASN1Sequence, index: Int): Option[Int] =
    Try(ASN1Enumerated.getInstance(description.getObjectAt(index)).intValueExact()).toOption

  private def octets(description: ASN1Sequence, index: Int): Option[Array[Byte]] =
    Try(ASN1OctetString.getInstance(description.getObjectAt(index)).getOctets).toOption

  private def origin(authorizations: ASN1Sequence): Option[Int] =
    tagged(authorizations, OriginTag).flatMap(value => Try(ASN1Integer.getInstance(value).intValueExact()).toOption)

  /** Whether the bootloader is locked, and the state the device booted in. */
  private def bootState(authorizations: ASN1Sequence): Option[(Boolean, Int)] =
    tagged(authorizations, RootOfTrustTag).flatMap: root =>
      Try:
        val fields = ASN1Sequence.getInstance(root)
        (ASN1Boolean.getInstance(fields.getObjectAt(1)).isTrue, ASN1Enumerated.getInstance(fields.getObjectAt(2)).intValueExact())
      .toOption

  /** The packages the app is installed as, with the SHA-256 digests of the certificates that signed it. */
  private final case class Application(packages: Set[String], digests: Set[String])

  private def applicationId(authorizations: ASN1Sequence): Option[Application] =
    tagged(authorizations, ApplicationIdTag).flatMap: wrapped =>
      Try:
        val id = ASN1Sequence.getInstance(ASN1OctetString.getInstance(wrapped).getOctets)
        val packages = ASN1Set.getInstance(id.getObjectAt(0)).iterator.asScala
          .map(info => String(ASN1OctetString.getInstance(ASN1Sequence.getInstance(info).getObjectAt(0)).getOctets, StandardCharsets.UTF_8)).toSet
        val digests = ASN1Set.getInstance(id.getObjectAt(1)).iterator.asScala
          .map(digest => hex(ASN1OctetString.getInstance(digest).getOctets)).toSet
        Application(packages, digests)
      .toOption

  /** The app is one the client accepts when it is installed as a package the client names, and every certificate it is signed by is
    * one of that package's. Several are possible, when a signing key has been rotated. */
  private def accepted(application: Application, apps: List[AndroidApp]): Either[AttestationFailure, AndroidApp] =
    val matching = apps.filter(app => application.packages.contains(app.packageName))
    matching.find(app => application.digests.nonEmpty && application.digests.subsetOf(app.certificateDigests))
      .toRight(AttestationFailure(Kind.AppNotAccepted, "the attestation is for an app this client does not accept"))

  private def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString
