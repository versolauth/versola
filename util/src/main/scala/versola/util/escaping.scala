package versola.util

/** Escapes text interpolated into HTML character data or a quoted attribute value. */
def escapeHtml(value: String): String =
  value.flatMap:
    case '&'  => "&amp;"
    case '<'  => "&lt;"
    case '>'  => "&gt;"
    case '"'  => "&quot;"
    case '\'' => "&#x27;"
    case c    => c.toString

/** Escapes JSON inlined into an HTML `<script>` block.
  *
  * The HTML parser closes the block on the literal `</script`, and opens a comment on `<!--`,
  * before the JavaScript parser ever sees the text - so JSON encoding alone does not keep a
  * string value from ending the element. `<`, `>` and `&` can only occur inside JSON string
  * literals, where `\uXXXX` is a valid escape that decodes back to the original character.
  */
def escapeJsonForScript(json: String): String =
  json.flatMap:
    case '<' => "\\u003c"
    case '>' => "\\u003e"
    case '&' => "\\u0026"
    case c   => c.toString

/** Escapes CSS inlined into an HTML `<style>` block.
  *
  * As with `<script>`, the HTML parser ends the block on the literal `</style` regardless of CSS
  * syntax. `\3c ` is the CSS escape for `<` and is honoured wherever `<` can legitimately appear
  * in a stylesheet - inside strings and identifiers; elsewhere both forms are equally invalid.
  */
def escapeCssForStyle(css: String): String =
  css.replace("<", "\\3c ")
