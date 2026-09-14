package versola.loadgen.store

import com.augustnagro.magnum.DbCodec
import versola.loadgen.model.*
import versola.loadgen.protocol.{EdgeSession, RefreshToken, SsoSession}
import versola.util.postgres.BasicCodecs

import java.time.Instant

/** magnum codecs shared by the Postgres repositories: the `SMALLINT` enum columns of
  * V0001/V0002/V0004 via [[StoreCodes]], and the protocol newtypes the session credentials are
  * typed as. Mixed in rather than imported so `DbCodec.derived` for the row types finds them.
  */
private[store] trait StoreCodecs extends BasicCodecs:
  given DbCodec[Instant] = DbCodec.InstantCodec

  given DbCodec[ActivityClass] = StoreCodecs.smallInt(StoreCodes.activityClass)
  given DbCodec[Platform] = StoreCodecs.smallInt(StoreCodes.platform)
  given DbCodec[CredentialKind] = StoreCodecs.smallInt(StoreCodes.credential)
  given DbCodec[UserRole] = StoreCodecs.smallInt(StoreCodes.role)
  given DbCodec[VirtualUserState] = StoreCodecs.smallInt(StoreCodes.userState)
  given DbCodec[SessionKind] = StoreCodecs.smallInt(StoreCodes.sessionKind)
  given DbCodec[MeasurementKind] = StoreCodecs.smallInt(StoreCodes.measurementKind)

  given DbCodec[RefreshToken] = DbCodec.StringCodec.biMap(RefreshToken(_), _.value)
  given DbCodec[EdgeSession] = DbCodec.StringCodec.biMap(EdgeSession(_), _.value)
  given DbCodec[SsoSession] = DbCodec.StringCodec.biMap(SsoSession(_), _.value)

private[store] object StoreCodecs:
  private def smallInt[A](codes: StoreCodes.Codes[A]): DbCodec[A] =
    DbCodec.ShortCodec.biMap(codes.decode, codes.encode)
