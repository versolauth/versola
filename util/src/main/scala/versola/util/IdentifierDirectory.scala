package versola.util

import org.apache.commons.codec.digest.Blake3

import java.nio.charset.StandardCharsets

/** How an identifier of a user (a phone, an email, a login) is written in the directory of a partition
  * (versolauth/versola#1041): as its keyed BLAKE3, so that a copy of the directory is not a list of the phone numbers
  * of a country. Here, and not in central, because whatever writes the directory has to compute the same MAC -- central
  * for a claim, the load test's seeder for the users it creates.
  */
object IdentifierDirectory:
  val Phone = "phone"
  val Email = "email"
  val Login = "login"

  val MacLength = 32

  /** Never to change: it is part of every MAC in the table. */
  private val Context = "versola central identifier directory v1".getBytes(StandardCharsets.UTF_8)

  /** The key identifiers are MACed under, derived from central's `client-secrets-secret` with BLAKE3's key derivation
    * mode under a context of its own: the directory needs no secret of its own to provision, and the two uses of the
    * secret never share a key. What follows from the derivation: replacing that secret means building the directory
    * again from the cells, which hold the identifiers themselves.
    */
  final class Key private[IdentifierDirectory] (key: Array[Byte]):

    /** @param kind [[Phone]], [[Email]] or [[Login]]. It is in the MAC as well as beside it, so a value moved to a row
      *   of another kind matches nothing.
      * @param value the identifier as `auth` stores it; a phone is canonical E.164.
      */
    def mac(kind: String, value: String): Array[Byte] =
      val mac = Array.ofDim[Byte](MacLength)
      Blake3.initKeyedHash(key)
        .update(kind.getBytes(StandardCharsets.UTF_8))
        .update(Array[Byte](0))
        .update(value.getBytes(StandardCharsets.UTF_8))
        .doFinalize(mac)
      mac

  def deriveKey(clientSecretsSecret: Array[Byte]): Key =
    val key = Array.ofDim[Byte](MacLength)
    Blake3.initKeyDerivationFunction(Context).update(clientSecretsSecret).doFinalize(key)
    Key(key)
