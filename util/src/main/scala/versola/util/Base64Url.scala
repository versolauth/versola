package versola.util


type Base64Url = Base64Url.Type

object Base64Url extends StringNewType:
  type Of[A] = Type
  def encode(bytes: Array[Byte]): Base64Url = apply(Base64.urlEncode(bytes))
