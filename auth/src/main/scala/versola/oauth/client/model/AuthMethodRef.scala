package versola.oauth.client.model

import zio.Chunk
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.prelude.Equal
import zio.schema.*

import java.time.Instant

/** Authentication Method Reference value as defined in RFC 8176.
  * A single authentication event may produce multiple values (e.g. SMS OTP → `Set(otp, sms)`).
  */
enum AuthMethodRef derives JsonCodec, Schema, Equal:
  /** Password-based authentication. */
  case pwd
  /** One-time password (code). */
  case otp
  /** OTP delivered via SMS channel. Paired with [[otp]]. */
  case sms
  /** Proof-of-possession of a hardware-secured (device-bound) key. */
  case hwk
  /** Proof-of-possession of a software-secured (synced / multi-device) key. */
  case swk
  /** User presence was confirmed (WebAuthn UP flag). */
  case user
  /** Multiple factors of authentication were performed. */
  case mfa

object AuthMethodRef:
  /** Derive the corresponding [[PassedAuthFactor]] from a set of AMR values. */
  def toPassedAuthFactor(methods: Set[AuthMethodRef]): Option[PassedAuthFactor] =
    if methods.exists(m => m == hwk || m == swk) then Some(PassedAuthFactor.passkey)
    else if methods.contains(pwd) then Some(PassedAuthFactor.password)
    else if methods.contains(otp) then Some(PassedAuthFactor.otp)
    else None

  /** Flatten the per-challenge records into the set of RFC 8176 values for the
    * OIDC `amr` claim, adding [[mfa]] when more than one distinct factor was passed.
    */
  def amrClaim(amr: Map[PassedAuthFactor, PassedFactorRecord]): Set[AuthMethodRef] =
    val methods = amr.values.flatMap(_.methods).toSet
    if amr.sizeIs >= 2 then methods + mfa else methods

  /** Build the OIDC ID token claims (`amr`, `auth_time`) from the resolved set of
    * method references and the authentication time. Omits `amr` when empty.
    */
  def idTokenClaims(amr: Set[AuthMethodRef], authTime: Option[Instant], acr: Option[String] = None): Map[String, Json] = {
    val amrField      = Option.when(amr.nonEmpty) {
      "amr" -> Json.Arr(Chunk.fromIterable(amr.toList.sortBy(_.toString).map(m => Json.Str(m.toString))))
    }
    val authTimeField = authTime.map(t => "auth_time" -> Json.Num(t.getEpochSecond))
    val acrField      = acr.map(v => "acr" -> Json.Str(v))
    (amrField ++ authTimeField ++ acrField).toMap
  }

/** The record of a single authentication challenge that was passed, storing
  * the timestamp and the typed RFC 8176 method references for that challenge.
  */
case class PassedFactorRecord(at: Instant, methods: Set[AuthMethodRef]) derives JsonCodec:
  /** Convert back to the internal routing key. */
  def toPassedAuthFactor: Option[PassedAuthFactor] = AuthMethodRef.toPassedAuthFactor(methods)
