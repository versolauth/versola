package versola.central.users

import versola.central.CentralConfig
import versola.central.configuration.topology.CellId
import versola.util.{ByteArrayNewType, Email, IdentifierDirectory, Phone, Secret}
import zio.ZLayer
import zio.json.JsonCodec

given JsonCodec[Email] = JsonCodec.string.transform(Email(_), identity)
given JsonCodec[Phone] = JsonCodec.string.transform(Phone(_), identity)

/** The kinds of identifier a user is found by. Each is unique across the cells of a partition. */
enum IdentifierKind(val code: String):
  case Phone extends IdentifierKind(IdentifierDirectory.Phone)
  case Email extends IdentifierKind(IdentifierDirectory.Email)
  case Login extends IdentifierKind(IdentifierDirectory.Login)

object IdentifierKind:
  def of(code: String): Option[IdentifierKind] = values.find(_.code == code)

/** The keyed BLAKE3 of an identifier, which is all the directory keeps of it (versolauth/versola#1041). */
type IdentifierMac = IdentifierMac.Type

object IdentifierMac extends ByteArrayNewType

/** An identifier as the directory holds it. */
final case class DirectoryIdentifier(kind: IdentifierKind, mac: IdentifierMac)

/** A user and the cell that holds it; `None` is the one `auth` of an installation with no cell registered. */
final case class UserDirectoryRecord(id: UserId, cellId: Option[CellId])

/** The key identifiers are MACed under ([[IdentifierDirectory.Key]]), for central's own identifier types. */
final class DirectoryKey private (key: IdentifierDirectory.Key):

  def phone(value: Phone): DirectoryIdentifier = of(IdentifierKind.Phone, value)

  def email(value: Email): DirectoryIdentifier = of(IdentifierKind.Email, value)

  def login(value: Login): DirectoryIdentifier = of(IdentifierKind.Login, value)

  private def of(kind: IdentifierKind, value: String): DirectoryIdentifier =
    DirectoryIdentifier(kind, IdentifierMac(key.mac(kind.code, value)))

object DirectoryKey:
  val live: ZLayer[CentralConfig, Nothing, DirectoryKey] =
    ZLayer.fromFunction((config: CentralConfig) => derive(config.clientSecretsSecret))

  def derive(secret: Secret): DirectoryKey = DirectoryKey(IdentifierDirectory.deriveKey(secret))
