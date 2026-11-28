package versola.user.move

import versola.user.move.UserMoveRepositorySpec.{documentOne, documentTwo}
import zio.Chunk
import zio.json.ast.Json
import zio.test.*

/** The digest an export answers with and a completion checks (versolauth/versola#1051): the same for rows that did not change,
  * whatever order a storage returned them in, and another for a change of any travelling row. */
object UserMoveDocumentSpec extends ZIOSpecDefault:

  private val digest = UserMoveDocument.digest

  def spec = suite("UserMoveDocument.digest")(
    test("is the same for two exports of unchanged rows, and for the rows in another order") {
      val shuffled = documentOne.copy(
        passwords = documentOne.passwords.reverse,
        passkeys = documentOne.passkeys.reverse,
        roles = documentOne.roles.reverse,
        refreshTokens = documentOne.refreshTokens.reverse,
        user = documentOne.user.copy(claims = Json.Obj(documentOne.user.claims.fields.reverse)),
      )
      assertTrue(digest(documentOne) == digest(documentOne), digest(documentOne) == digest(shuffled))
    },
    test("differs when any travelling row changed") {
      val token = documentOne.refreshTokens.head
      val changes = List(
        documentOne.copy(user = documentOne.user.copy(email = Some("other@example.com"))),
        documentOne.copy(user = documentOne.user.copy(claims = Json.Obj())),
        documentOne.copy(passwords = documentOne.passwords.tail),
        documentOne.copy(passwords = documentOne.passwords.map(p => p.copy(salt = Chunk.fill(16)(9.toByte)))),
        documentOne.copy(passkeys = documentOne.passkeys.map(p => p.copy(signatureCounter = p.signatureCounter + 1))),
        documentOne.copy(roles = documentOne.roles.tail),
        documentOne.copy(consents = Nil),
        documentOne.copy(refreshTokens = documentOne.refreshTokens.tail),
        documentOne.copy(refreshTokens = token.copy(scope = List("openid")) :: documentOne.refreshTokens.tail),
        documentOne.copy(moveId = UserMoveRepositorySpec.moveTwo),
      )
      assertTrue(changes.forall(changed => digest(changed) != digest(documentOne)), changes.map(digest).distinct.size == changes.size)
    },
    test("is base64url of a SHA-256: 43 characters, none of + / =") {
      assertTrue(digest(documentOne).matches("[A-Za-z0-9_-]{43}"))
    },
  )
