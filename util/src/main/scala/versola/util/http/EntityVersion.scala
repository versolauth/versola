package versola.util.http

/** The version of a stored thing as an HTTP validator: the `ETag` it is answered with and the `If-Match` a change is made
  * under (RFC 9110, section 13.1.1), so that two admins who read the same version cannot both write.
  *
  * The version is a number, sent as a strong entity tag in quotes (`"3"`); a bare number is accepted as well, for a client that
  * does not quote. A list of tags, a weak tag and `*` are not: a change is made on one version, not on "any".
  */
object EntityVersion:

  /** The `ETag` header value of `version`. */
  def etag(version: Long): String = s"\"$version\""

  /** The version an `If-Match` header names. */
  def parseIfMatch(value: String): Either[String, Long] =
    val tag = value.trim
    val bare = if tag.length >= 2 && tag.startsWith("\"") && tag.endsWith("\"") then tag.substring(1, tag.length - 1) else tag
    bare.toLongOption.filter(_ >= 0).toRight("If-Match is one version of the settings, as the ETag of the last read gave it")

  /** The version a change is made on, from the `If-Match` header and the `version` field of the body: either, and when both are
    * given they must agree. `None` when the request names none.
    *
    * `If-Match` is the preferred way (it is what the `ETag` of the read is for); the field is for a client that cannot set
    * headers.
    */
  def expected(ifMatch: Option[String], body: Option[Long]): Either[String, Option[Long]] =
    for
      header <- ifMatch.fold[Either[String, Option[Long]]](Right(None))(parseIfMatch(_).map(Some(_)))
      _ <- Either.cond(header.isEmpty || body.isEmpty || header == body, (), "If-Match and version name different versions")
    yield header.orElse(body)
