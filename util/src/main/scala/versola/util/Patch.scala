package versola.util

import zio.json.ast.Json
import zio.json.internal.{RetractReader, Write}
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder, JsonError}
import zio.schema.Schema

/** Represents the intent for a nullable, patchable field in a JSON request body.
 *
 * JSON semantics:
 *  - key absent           → `None`        (no update — field not in payload)
 *  - key present, null    → `Some(Deleted)` (clear the value)
 *  - key present, value   → `Some(Modified(v))` (update to new value)
 *
 * Use `Option[Patch[A]]` for patchable fields in case classes that derive JsonCodec.
 * The custom `JsonDecoder[Option[Patch[A]]]` makes sure that a JSON `null` value is
 * decoded as `Some(Deleted)`, while an absent key stays `None`.
 */
enum Patch[+A]:
  case Deleted
  case Modified(value: A)

object Patch:

  given [A: JsonEncoder] => JsonEncoder[Patch[A]] =
    new JsonEncoder[Patch[A]]:
      def unsafeEncode(patch: Patch[A], indent: Option[Int], out: Write): Unit =
        patch match
          case Deleted     => out.write("null")
          case Modified(v) => JsonEncoder[A].unsafeEncode(v, indent, out)
      // Never skip — Deleted must emit null, not be omitted
      override def isNothing(patch: Patch[A]): Boolean = false

  given [A: JsonDecoder as dec] => JsonDecoder[Patch[A]] =
    JsonDecoder[Json].mapOrFail:
      case Json.Null => Right(Patch.Deleted)
      case json      => dec.fromJsonAST(json).map(Patch.Modified(_))

  given [A: {JsonEncoder, JsonDecoder}] => JsonCodec[Patch[A]] =
    JsonCodec(JsonEncoder[Patch[A]], JsonDecoder[Patch[A]])

  /** Custom decoder for `Option[Patch[A]]` fields in derived case classes.
   *
   * ZIO JSON calls `unsafeDecodeMissing` for absent keys and `unsafeDecode` for
   * present ones.  Both must be handled:
   *   - key absent → None          (via unsafeDecodeMissing)
   *   - null       → Some(Deleted) (via unsafeDecode)
   *   - value      → Some(Modified(v)) (via unsafeDecode)
   */
  given [A: JsonDecoder] => JsonDecoder[Option[Patch[A]]] =
    new JsonDecoder[Option[Patch[A]]]:
      override def unsafeDecode(trace: List[JsonError], in: RetractReader): Option[Patch[A]] =
        Some(JsonDecoder[Patch[A]].unsafeDecode(trace, in))

      override def unsafeDecodeMissing(trace: List[JsonError]): Option[Patch[A]] =
        None

  given [A: Schema] => Schema[Patch[A]] =
    Schema.option[A].transform(
      {
        case None    => Patch.Deleted
        case Some(v) => Patch.Modified(v)
      },
      {
        case Patch.Deleted     => None
        case Patch.Modified(v) => Some(v)
      }
    )

  extension [A](opt: Option[Patch[A]])
    /** Splits an optional patch into `(mustUpdate, newValue)` suitable for SQL
     *  `SET col = CASE WHEN $mustUpdate THEN $newValue ELSE col END` patterns:
     *   - None              → (false, None)        no change
     *   - Some(Deleted)     → (true,  None)        set column to NULL
     *   - Some(Modified(v)) → (true,  Some(v))     set column to v
     */
    def toUpdate: (Boolean, Option[A]) = opt match
      case None                    => (false, None)
      case Some(Patch.Deleted)     => (true, None)
      case Some(Patch.Modified(v)) => (true, Some(v))
