package versola.util

import zio.json.ast.Json

/** JSON merge patch, RFC 7396 §2, on the JSON AST: the patch does not depend on the fields of whatever the result is decoded into.
  *
  * An object merges into the target recursively, a member that is `null` removes the target's member of that name, and anything
  * else (a string, a number, an array, a boolean) replaces what is there. A patch that is not an object replaces the target as a
  * whole; a target that is not an object is taken as an empty one when the patch is an object. The patch cannot set a member to
  * `null`: that is what it spells removal as (RFC 7396 §1).
  */
object JsonMergePatch:

  def apply(target: Json, patch: Json): Json = patch match
    case Json.Obj(patchFields) =>
      val targetFields = target match
        case Json.Obj(fields) => fields
        case _ => Json.Obj().fields
      val merged = patchFields.foldLeft(targetFields): (fields, entry) =>
        val (name, value) = entry
        val without = fields.filterNot(_._1 == name)
        value match
          case Json.Null => without
          case _ =>
            val current = fields.collectFirst { case (`name`, existing) => existing }.getOrElse(Json.Null)
            // Keeps the member where it was, so a merged document reads like the one it came from.
            if fields.exists(_._1 == name) then fields.map((n, v) => if n == name then n -> apply(current, value) else n -> v)
            else without :+ (name -> apply(Json.Null, value))
      Json.Obj(merged)
    case other => other
