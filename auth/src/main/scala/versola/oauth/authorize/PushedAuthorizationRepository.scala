package versola.oauth.authorize

import versola.oauth.authorize.model.PushedAuthorizationRecord
import versola.oauth.model.RequestUriReference
import versola.util.MAC
import zio.{Duration, Task}

trait PushedAuthorizationRepository:

  def create(
      requestUri: MAC.Of[RequestUriReference],
      record: PushedAuthorizationRecord,
      ttl: Duration,
  ): Task[Unit]

  /** RFC 9126 §4: a `request_uri` is single-use, so the record is removed as it is read.
    * Expired records are never returned.
    */
  def consume(requestUri: MAC.Of[RequestUriReference]): Task[Option[PushedAuthorizationRecord]]
