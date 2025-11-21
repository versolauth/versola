package versola.oauth

import versola.oauth.model.*
import versola.util.CacheSource
import zio.IO

trait OAuthScopeRepository extends CacheSource[Map[ScopeToken, Scope]]:

  def getAll: IO[Throwable, Map[ScopeToken, Scope]]

  def createOrUpdate(scopes: Vector[ScopeRecord]): IO[Throwable, Unit]

  def delete(names: Vector[ScopeToken]): IO[Throwable, Unit]
