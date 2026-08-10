package versola.oauth.session.model

import versola.util.MAC

case class SessionInfo(
    id: MAC.Of[SessionId],
    record: SessionRecord,
)
