package versola.util

import zio.json.ast.Json

object JsonJava:
  def toJava(json: Json): AnyRef =
    json match
      case Json.Str(s)  => s
      case Json.Num(n)  => n
      case Json.Bool(b) => java.lang.Boolean.valueOf(b)
      case Json.Null    => null
      case Json.Arr(elements) =>
        val out = java.util.ArrayList[AnyRef](elements.size)
        elements.foreach(e => out.add(toJava(e)))
        out
      case Json.Obj(fields) =>
        val out = java.util.LinkedHashMap[String, AnyRef](fields.size)
        fields.foreach((k, v) => out.put(k, toJava(v)))
        out
