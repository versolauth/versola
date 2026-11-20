package versola.central.users

import org.apache.commons.codec.digest.Blake3
import versola.central.CentralConfig
import versola.central.configuration.topology.CellId
import versola.util.{ByteArrayNewType, Email, Phone, Secret}
import zio.ZLayer

import zio.json.JsonCodec

import java.nio.charset.StandardCharsets

given JsonCodec[Email] = JsonCodec.string.transform(Email(_), identity)
given JsonCodec[Phone] = JsonCodec.string.transform(Phone(_), identity)

/** The kinds of identifier a user is found by. Each is unique across the cells of a partition. */
enum IdentifierKind(val code: String):
  case Phone extends IdentifierKind("phone")
  case Email extends IdentifierKind("email")
  case Login extends IdentifierKind("login")

object IdentifierKind:
  def of(code: String): Option[IdentifierKind] = values.find(_.code == code)

/** The keyed BLAKE3 of an identifier, which is all the directory keeps of it (versolauth/versola#1041). */
type IdentifierMac = IdentifierMac.Type

object IdentifierMac extends ByteArrayNewType

/** An identifier as the directory holds it. */
final case class DirectoryIdentifier(kind: IdentifierKind, mac: IdentifierMac)

/** A user and the cell that holds it; `None` is the one `auth` of an installation with no cell registered. */
final case class UserDirectoryRecord(id: UserId, cellId: Option[CellId])

/** The key identifiers are MACed under.
  *
  * Derived from central's `client-secrets-secret` with BLAKE3's key derivation mode under a context of its own, so
  * the directory needs no secret of its own to provision and the two uses of the secret never share a key. What
  * follows from the derivation: replacing that secret means building the directory again from the cells, which hold
  * the identifiers themselves.
  */
final class DirectoryKey private (key: Array[Byte]):

  def phone(value: Phone): DirectoryIdentifier = of(IdentifierKind.Phone, value)

  def email(value: Email): DirectoryIdentifier = of(IdentifierKind.Email, value)

  def login(value: Login): DirectoryIdentifier = of(IdentifierKind.Login, value)

  /** The kind is in the column and in the MAC: a value moved to a row of another kind matches nothing. */
  private def of(kind: IdentifierKind, value: String): DirectoryIdentifier =
    val mac = Array.ofDim[Byte](DirectoryKey.Length)
    Blake3.initKeyedHash(key)
      .update(kind.code.getBytes(StandardCharsets.UTF_8))
      .update(Array[Byte](0))
      .update(value.getBytes(StandardCharsets.UTF_8))
      .doFinalize(mac)
    DirectoryIdentifier(kind, IdentifierMac(mac))

object DirectoryKey:
  val live: ZLayer[CentralConfig, Nothing, DirectoryKey] =
    ZLayer.fromFunction((config: CentralConfig) => derive(config.clientSecretsSecret))

  private val Length = 32

  /** Never to change: it is part of every MAC in the table. */
  private val Context = "versola central identifier directory v1".getBytes(StandardCharsets.UTF_8)

  def derive(secret: Secret): DirectoryKey =
    val key = Array.ofDim[Byte](Length)
    Blake3.initKeyDerivationFunction(Context).update(secret).doFinalize(key)
    DirectoryKey(key)
