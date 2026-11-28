package versola.user.move

import versola.util.Base64
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** The rows of one user that travel with them to another cell (versolauth/versola#1051): every column of `users`,
  * `user_passwords`, `passkeys`, `user_roles`, `user_consents` and of the `refresh_tokens` that have not expired, as the cell
  * that holds the user read them in one repeatable-read transaction. It is JSON, bytes as base64url, and it is what
  * [[versola.oauth.handoff.CellEnvelope]] seals under the purpose `user-move`.
  *
  * The import reproduces the rows as they are: the ids, `last_version`, timestamps, hashes and salts, the passkeys' counters,
  * a refresh token's family, `cnf`, scope, expiry and `public_session_id`. The `session_id` stays as it is although the
  * session is not carried: a token may outlive its session.
  *
  * @param version the shape of this document; one that is not [[UserMoveDocument.Version]] is refused whole
  * @param moveId the move central made it for: an arrival is taken only for the move its envelope names
  */
final case class UserMoveDocument(
    version: Int,
    userId: UUID,
    moveId: UUID,
    user: UserMoveDocument.User,
    passwords: List[UserMoveDocument.Password],
    passkeys: List[UserMoveDocument.Passkey],
    roles: List[UserMoveDocument.Role],
    consents: List[UserMoveDocument.Consent],
    refreshTokens: List[UserMoveDocument.RefreshToken],
) derives JsonCodec

object UserMoveDocument:
  val Version = 1

  /** Bytes are base64url, as everywhere an id or a hash is written for a person or a peer to read. */
  given JsonCodec[Chunk[Byte]] = JsonCodec[String].transformOrFail(
    text => Try(Chunk.fromArray(Base64.urlDecode(text))).toEither.left.map(_ => "not base64url"),
    bytes => Base64.urlEncode(bytes.toArray),
  )

  /** `users`. */
  final case class User(
      id: UUID,
      email: Option[String],
      phone: Option[String],
      login: Option[String],
      claims: Json.Obj,
      uiLocales: Option[List[String]],
      lastVersion: Option[UUID],
  ) derives JsonCodec

  /** `user_passwords`: `id` orders the history of a user's passwords. */
  final case class Password(
      id: Long,
      password: Chunk[Byte],
      salt: Chunk[Byte],
      createdAt: Instant,
      expiresAt: Option[Instant],
  ) derives JsonCodec

  /** `passkeys`. */
  final case class Passkey(
      id: Chunk[Byte],
      publicKey: Chunk[Byte],
      signatureCounter: Long,
      deviceType: String,
      backedUp: Boolean,
      backupEligible: Boolean,
      transports: Option[List[String]],
      attestationObject: Option[Chunk[Byte]],
      clientDataJson: Option[Chunk[Byte]],
      aaguid: Option[Chunk[Byte]],
      name: Option[String],
      lastUsedAt: Option[Instant],
      createdAt: Instant,
      updatedAt: Instant,
  ) derives JsonCodec

  /** `user_roles`. */
  final case class Role(tenantId: String, roleId: String) derives JsonCodec

  /** `user_consents`. */
  final case class Consent(
      clientId: String,
      scope: List[String],
      grantedAt: Instant,
      expiresAt: Option[Instant],
  ) derives JsonCodec

  /** `refresh_tokens`: `id` is the MAC of the token under `REFRESH_TOKENS_SECRET`, which every cell of a partition shares. A
    * generation rotated away is carried too (`rotatedAt`, `idempotencyKey`): a replay of it must still resolve to its family.
    */
  final case class RefreshToken(
      id: Chunk[Byte],
      familyId: String,
      rotatedAt: Option[Instant],
      idempotencyKey: Option[Chunk[Byte]],
      sessionId: Chunk[Byte],
      publicSessionId: String,
      clientId: String,
      audience: List[String],
      scope: List[String],
      issuedAt: Instant,
      expiresAt: Instant,
      requestedClaims: Option[Json],
      uiLocales: Option[List[String]],
      nonce: Option[String],
      acr: Option[String],
      amr: Json,
      authTime: Instant,
      cnf: Option[Json],
      authorizationDetails: Option[List[Json]],
  ) derives JsonCodec

  /** The document with its rows in an order that depends on what they are and not on how a storage returned them. */
  def canonical(document: UserMoveDocument): UserMoveDocument =
    def key(bytes: Chunk[Byte]) = Base64.urlEncode(bytes.toArray)
    document.copy(
      passwords = document.passwords.sortBy(_.id),
      passkeys = document.passkeys.sortBy(passkey => key(passkey.id)),
      roles = document.roles.sortBy(role => (role.tenantId, role.roleId)),
      consents = document.consents.sortBy(_.clientId),
      refreshTokens = document.refreshTokens.sortBy(token => key(token.id)),
    )

  /** The digest the export answers with and the completion checks (base64url SHA-256): of the document in canonical order (rows
    * sorted, the fields of every JSON object sorted by name, no white space), so that two exports of rows that did not change give
    * the same one, and a change of any column of any travelling row gives another. A refresh token that expires between two
    * exports leaves the second document and so changes the digest: harmless (the target is brought up to date) and rare. */
  def digest(document: UserMoveDocument): String =
    def sorted(json: Json): Json = json match
      case Json.Obj(fields) => Json.Obj(fields.sortBy(_._1).map((name, value) => name -> sorted(value)))
      case Json.Arr(elements) => Json.Arr(elements.map(sorted))
      case other => other
    // Encoding a document to a JSON tree does not fail; if it ever did, a digest of nothing would say "unchanged" for everything.
    val tree = canonical(document).toJsonAST.fold(error => throw IllegalStateException(s"a move document is not encodable: $error"), identity)
    val text = sorted(tree).toJson
    Base64.urlEncode(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))

  /** The document as bytes, and back; a document that is not valid, or of a version this build does not know, is not one. */
  def encode(document: UserMoveDocument): Array[Byte] = document.toJson.getBytes(StandardCharsets.UTF_8)

  def decode(bytes: Array[Byte]): Either[DocumentError, UserMoveDocument] =
    String(bytes, StandardCharsets.UTF_8).fromJson[UserMoveDocument].left.map(_ => DocumentError.Invalid).flatMap: document =>
      if document.version != Version then Left(DocumentError.UnsupportedVersion(document.version))
      else if document.user.id != document.userId then Left(DocumentError.Invalid)
      else Right(document)

  /** Why a document is not taken: a closed set. */
  enum DocumentError(val code: String):
    case Invalid extends DocumentError("invalid_document")
    case UnsupportedVersion(version: Int) extends DocumentError("unsupported_version")

    /** The rows name a tenant, a client or a scope this cell's synced configuration does not know; `what` says which kind. */
    case NotInConfiguration(what: String) extends DocumentError("invalid_document")
