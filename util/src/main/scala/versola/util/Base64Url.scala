package versola.util

import java.nio.charset.StandardCharsets


type Base64Url = Base64Url.Type

object Base64Url extends StringNewType:
  type Of[A] = Type
  def encode(bytes: Array[Byte]): Base64Url = apply(Base64.urlEncode(bytes))
  def decodeStr(str: String): String =
    new String(Base64.urlDecode(str), StandardCharsets.UTF_8)