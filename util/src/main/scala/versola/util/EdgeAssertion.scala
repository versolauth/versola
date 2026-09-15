package versola.util

import zio.json.ast.Json
import zio.json.{JsonCodec, jsonField}
import zio.{Duration, IO, Task, ZIO, durationInt}

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, PrivateKey}
import java.time.Instant

/** What an edge sends auth to say it has already enforced RFC 9449 §7 on a request, so auth's
  * own resource endpoints do not demand a second proof the caller cannot produce.
  *
  * Edge is a resource server for the same bound tokens auth issues, and it verifies the client's
  * proof at its own boundary (`DpopVerifier`). The calls it then makes to auth on its own behalf
  * -- `/userinfo`, to build the CEL authorization context -- carry the user's access token but no
  * proof, and cannot: the proof is signed with the client's private key, which edge never holds,
  * and the client's own proof is bound by `htu` to edge's URL and spent on arrival. Without
  * something else to go on, auth can only read those calls as the `Bearer` downgrade of a bound
  * token that §7.2 requires it to refuse.
  *
  * The assertion is that something else: a short-lived JWT signed with the edge's own registered
  * key, the same key and `edge_id` header central already authenticates an edge by (see
  * `central.authorizeInternal`). It is *not* a way to turn DPoP off -- it says only that this
  * edge checked this token's proof, so [[verify]] binds it to the token in hand via `ath` and
  * auth honours it for no other refusal.
  */
object EdgeAssertion:

  /** Sent alongside, not instead of, the user's `Authorization` token. */
  val HeaderName = "Versola-Edge-Assertion"

  /** The JWT header parameter naming the edge, matching central's `InternalAuthHeader`. */
  val EdgeIdHeader = "edge_id"

  /** Who the assertion is for. Checked explicitly on the way in, because the edge key that
    * signs this also signs the sync token it sends central every few minutes
    * (`CentralSyncTokenService`) -- same key, same `edge_id`, same JWT type. Without an
    * audience check, one of those tokens replayed at auth would read as a valid assertion,
    * and anything that has seen one (central itself included) could use it to present a bound
    * token under `Bearer`. The `ath` claim below is absent from those tokens and required
    * here, so both checks have to pass for the two to stay distinct.
    */
  val Audience = "auth"

  /** Long enough to cover clock spread between edge and auth, short enough that a captured
    * assertion is worth little -- it is minted per call, not cached, so nothing needs it to
    * outlive the request it was made for.
    */
  val Ttl: Duration = 2.minutes

  enum Error:
    case Malformed
    case Unsigned
    case Expired
    case WrongAudience
    case TokenMismatch

    /** Named an edge the verifier holds no key for. Raised by whoever resolves [[edgeIdOf]]
      * against a registry, not by anything here -- [[verify]] is given the keys already. */
    case UnknownEdge

    /** Named an edge that is real and signed correctly, but is not the one central has
      * assigned to serve this token's tenant. Raised by the caller after resolving the
      * registry, for the same reason [[UnknownEdge]] is: an edge's identity says nothing
      * about which tenants it may vouch for without checking that separately. */
    case WrongTenant

    /** This exact assertion has already been accepted once. Raised by the caller, which is
      * the only one holding a replay store -- [[verify]]'s own checks are otherwise pure, the
      * same split `versola.oauth.dpop.DpopService` makes around `Dpop.verify`. */
    case Replayed

  private case class AssertionHeader(
      @jsonField("edge_id") edgeId: Option[String],
  ) derives JsonCodec

  /** `exp` is required here rather than left to `JWT.deserialize`, which treats a token
    * carrying no expiry at all as unexpired. Everything [[issue]] mints has one, so demanding it
    * costs nothing and makes the lifetime a property of the format rather than a convention the
    * signer is trusted to have followed.
    *
    * `jti`/`iat` are read out rather than left implicit, so [[verify]] can hand them back to a
    * caller that keeps a replay store -- the same reason `Dpop.Proof` carries its own.
    */
  private case class AssertionClaims(
      aud: List[String],
      ath: String,
      exp: Long,
      jti: String,
      iat: Long,
  ) derives JsonCodec

  /** What a checked assertion hands back: enough for the caller to record it as spent.
    * `verify` itself keeps no state -- recording is the caller's job, same split
    * `versola.oauth.dpop.DpopService` makes around `Dpop.verify`.
    */
  case class Verified(jti: String, issuedAt: Instant)

  /** Mints an assertion for one specific access token. */
  def issue(
      edgeId: String,
      keyId: String,
      privateKey: PrivateKey,
      accessToken: String,
      ttl: Duration = Ttl,
  ): Task[String] =
    JWT.serialize(
      claims = JWT.Claims(
        issuer = edgeId,
        subject = edgeId,
        audience = List(Audience),
        custom = Json.Obj("ath" -> Json.Str(Dpop.ath(accessToken))),
      ),
      ttl = ttl,
      signature = JWT.Signature.Asymmetric(
        algorithm = JWT.Algorithm.RS256,
        keyId = keyId,
        privateKey = privateKey,
      ),
      headers = Map(EdgeIdHeader -> edgeId),
    )

  /** Reads the `edge_id` header parameter *without* verifying anything, so the caller can pick
    * the key set to verify against -- the same unverified-then-verified order
    * `central.authorizeInternal` uses. The value is untrusted until [[verify]] succeeds with
    * the keys it selected.
    */
  def edgeIdOf(assertion: String): IO[Error, String] =
    JWT.parseHeader[AssertionHeader](assertion)
      .mapError(_ => Error.Malformed)
      .flatMap(header => ZIO.fromOption(header.edgeId).orElseFail(Error.Malformed))

  /** Accepts an assertion this edge signed, for this access token, that has not expired.
    *
    * `keys` must be the registered keys of the edge [[edgeIdOf]] named; passing any other
    * edge's keys fails the signature check, which is what keeps one edge's assertion from
    * speaking for another.
    *
    * Says nothing about whether this edge may vouch for *this tenant's* tokens, or whether
    * this exact assertion has been seen before -- both are the caller's job, the same way
    * `Dpop.verify` leaves replay protection to `DpopService`. [[Verified]] hands back what
    * that check needs.
    */
  def verify(
      assertion: String,
      keys: JWT.PublicKeys,
      accessToken: String,
  ): IO[Error, Verified] =
    for
      claims <- JWT.deserialize[AssertionClaims](assertion, keys, JWT.Type.JWT)
        .mapError:
          case JWT.Error.InvalidSignature => Error.Unsigned
          case JWT.Error.Expired(_) => Error.Expired
          case _ => Error.Malformed
      _ <- ZIO.fail(Error.WrongAudience).unless(claims.aud.contains(Audience))
      _ <- ZIO.fail(Error.TokenMismatch).unless(
        MessageDigest.isEqual(
          claims.ath.getBytes(StandardCharsets.UTF_8),
          Dpop.ath(accessToken).getBytes(StandardCharsets.UTF_8),
        ),
      )
    yield Verified(claims.jti, Instant.ofEpochSecond(claims.iat))
