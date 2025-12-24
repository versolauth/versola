package versola.util


type Digest = Digest.Type

object Digest extends ByteArrayNewType

/** Message Authentication Code */
type MAC = MAC.Type

object MAC extends ByteArrayNewType:
  type Of[A] = MAC

type Salt = Salt.Type

object Salt extends ByteArrayNewType

type Secret = Secret.Type

object Secret extends ByteArrayNewType:
  type Bytes16 = Bytes16.Type
  object Bytes16 extends ByteArrayNewType.FixedLength(Secret, length = 16)
  
  type Bytes32 = Bytes32.Type
  object Bytes32 extends ByteArrayNewType.FixedLength(Secret, length = 32)