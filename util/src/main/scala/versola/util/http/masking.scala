package versola.util.http

import zio.IO
import zio.http.{Body, Client, Response, ZClient, ZClientAspect}

def mask(masking: HttpObservabilityConfig.Client): ZClientAspect[Nothing, Any, Nothing, Body, Nothing, Any, Nothing, Response] =
  new:
    override def apply[ReqEnv, Env >: Nothing <: Any, In >: Nothing <: Body, Err >: Nothing <: Any, Out >: Nothing <: Response](client: ZClient[Env, ReqEnv, In, Err, Out]): ZClient[Env, ReqEnv, In, Err, Out] =
      client.contramapZIO(in => Observability.clientMasking.set(masking).as(in))
