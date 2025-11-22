package versola.security

import versola.util.{ByteArrayNewType, StringNewType}

import java.nio.charset.StandardCharsets


type Digest = Digest.Type

object Digest extends ByteArrayNewType

type MAC = MAC.Type

object MAC extends ByteArrayNewType

type Salt = Salt.Type

object Salt extends ByteArrayNewType

type Secret = Secret.Type

object Secret extends ByteArrayNewType:
  type Bytes16 = Bytes16.Type
  object Bytes16 extends ByteArrayNewType.FixedLength(Secret, length = 16)
  
  type Bytes32 = Bytes32.Type
  object Bytes32 extends ByteArrayNewType.FixedLength(Secret, length = 32)