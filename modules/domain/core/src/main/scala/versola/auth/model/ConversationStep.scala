package versola.auth.model

import zio.schema.{Schema, derived}

enum ConversationStep derives Schema:
  case Email
  case OAuth(provider: String)
  case Passkey
  case Completed

object ConversationStep:
  def email: ConversationStep = Email
  def oauth(provider: String): ConversationStep = OAuth(provider)
  def passkey: ConversationStep = Passkey
  def completed: ConversationStep = Completed
