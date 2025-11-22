package versola.util

object Base64:
  private val urlEncoder = java.util.Base64.getUrlEncoder.withoutPadding()
  
  def urlEncode(bytes: Array[Byte]) = urlEncoder.encodeToString(bytes)
  
  def urlDecode(base64: String) = java.util.Base64.getUrlDecoder.decode(base64)

