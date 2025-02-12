package zio.telemetry.example.http

import io.circe.Encoder
import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityEncoder, HttpRoutes }
import sttp.model.Uri
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentracing.OpenTracing
import zio.{ UIO, ZManaged }

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object StatusesService {

  def statuses(backendUri: Uri, service: ZManaged[Clock, Throwable, Clock with OpenTracing]): HttpRoutes[AppTask] = {
    val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
    import dsl._

    implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

    HttpRoutes.of[AppTask] {
      case GET -> Root / "statuses" =>
        service.use { env =>
          val zio = for {
            _       <- env.telemetry.root("/statuses")
            _       <- OpenTracing.tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)
            _       <- OpenTracing.tag(Tags.HTTP_METHOD.getKey, GET.name)
            _       <- OpenTracing.setBaggageItem("proxy-baggage-item-key", "proxy-baggage-item-value")
            buffer  <- UIO.succeed(new TextMapAdapter(mutable.Map.empty[String, String].asJava))
            _       <- OpenTracing.inject(HttpHeadersFormat, buffer)
            headers <- extractHeaders(buffer)
            up      = Status.up("proxy")
            res <- Client
                    .status(backendUri.path("status"), headers)
                    .map(_.body)
                    .flatMap {
                      case Right(s) => Ok(Statuses(List(s, up)))
                      case _        => Ok(Statuses(List(Status.down("backend"), up)))
                    }
          } yield res

          zio.provideSomeManaged(service)
        }
    }
  }

  private def extractHeaders(adapter: TextMapAdapter): UIO[Map[String, String]] = {
    val m = mutable.Map.empty[String, String]
    UIO(adapter.forEach { entry =>
      m.put(entry.getKey, entry.getValue)
      ()
    }).as(m.toMap)
  }

}
