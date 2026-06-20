package fitforge.http

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.Multipart
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.ContentDispositionTypes
import org.apache.pekko.http.scaladsl.model.headers.`Access-Control-Allow-Headers`
import org.apache.pekko.http.scaladsl.model.headers.`Access-Control-Allow-Methods`
import org.apache.pekko.http.scaladsl.model.headers.`Access-Control-Allow-Origin`
import org.apache.pekko.http.scaladsl.model.headers.`Content-Disposition`
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString

import fitforge.FitForgeConfig
import fitforge.fit.FitCodec
import fitforge.fit.FitFile
import fitforge.fit.FitLayout
import fitforge.fit.FitMerge
import fitforge.fit.FitSummary
import fitforge.fit.LapStrategy
import fitforge.fit.MergeOptions
import fitforge.fit.MergeOutcome
import fitforge.store.FitStore
import fitforge.store.StoreError

/** All HTTP routes under `/fitforge/v1/`, plus health and static serving. */
final class FitForgeRoutes(store: FitStore, codec: FitCodec, config: FitForgeConfig)(implicit system: ActorSystem)
    extends SprayJsonSupport {

  import JsonProtocol._

  private implicit val ec: ExecutionContext = system.dispatcher

  private val corsHeaders = List(
    `Access-Control-Allow-Origin`.*,
    `Access-Control-Allow-Methods`(HttpMethods.GET, HttpMethods.POST, HttpMethods.OPTIONS),
    `Access-Control-Allow-Headers`("Content-Type"),
  )

  val routes: Route = respondWithHeaders(corsHeaders) {
    concat(
      options { complete(StatusCodes.OK) },
      path("health") { get { complete(StatusCodes.OK) } },
      pathPrefix("fitforge" / "v1" / "fit") {
        concat(
          (path("upload") & post)(uploadRoute),
          (path("merge") & post)(mergeRoute),
          path(Segment / "summary")(id => get(summaryRoute(id))),
          path(Segment / "track")(id => get(trackRoute(id))),
          path(Segment / "download")(id => get(downloadRoute(id))),
        )
      },
      getFromDirectory(config.staticDir),
    )
  }

  // ── routes ────────────────────────────────────────────────────────────────

  private def uploadRoute: Route = entity(as[Multipart.FormData]) { formData =>
    val resultsF: Future[Vector[Either[String, UploadFileResult]]] =
      formData.parts
        .filter(_.filename.isDefined)
        .mapAsync(1) { part =>
          part.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).flatMap { bs =>
            Try(codec.decode(bs.toArray)) match {
              case Failure(e) => Future.successful(Left(e.getMessage))
              case Success(file) =>
                store
                  .put(bs.toArray, config.sessionTtl)
                  .map(id =>
                    Right(
                      UploadFileResult(
                        id,
                        file.fileId,
                        FitSummary.ride(file),
                        FitSummary.devices(file),
                        FitLayout.of(file),
                      )
                    )
                  )
            }
          }
        }
        .runWith(Sink.seq)
        .map(_.toVector)

    onComplete(resultsF) {
      case Success(results) =>
        results.collectFirst { case Left(msg) => msg } match {
          case Some(msg) => complete(StatusCodes.UnprocessableContent -> ApiError(s"Invalid FIT: $msg"))
          case None      => complete(UploadResponse(results.collect { case Right(r) => r }))
        }
      case Failure(ex) => complete(StatusCodes.InternalServerError -> ApiError(ex.getMessage))
    }
  }

  private def summaryRoute(id: String): Route = withFile(id) { file =>
    complete(SummaryResponse(FitSummary.ride(file), FitSummary.devices(file), FitLayout.of(file)))
  }

  private def trackRoute(id: String): Route = withFile(id)(file => complete(JsonProtocol.trackGeoJson(file)))

  private def downloadRoute(id: String): Route = onComplete(store.get(id)) {
    case Success(Right(bytes)) =>
      respondWithHeader(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" -> s"$id.fit"))) {
        complete(HttpEntity(ContentTypes.`application/octet-stream`, bytes))
      }
    case Success(Left(err)) => completeStoreError(err)
    case Failure(ex)        => complete(StatusCodes.InternalServerError -> ApiError(ex.getMessage))
  }

  private def mergeRoute: Route = entity(as[MergeRequest]) { req =>
    if (req.gapHandling != "preserve")
      complete(StatusCodes.UnprocessableContent -> ApiError("gapHandling 'close' is not implemented yet"))
    else {
      val lap = if (req.lapStrategy == "KeepOriginal") LapStrategy.KeepOriginal else LapStrategy.OnePerSegment
      onComplete(Future.traverse(req.ids)(store.get)) {
        case Success(results) =>
          results.collectFirst { case Left(e) => e } match {
            case Some(err) => completeStoreError(err)
            case None =>
              Try(results.collect { case Right(b) => codec.decode(b) }) match {
                case Failure(_) =>
                  complete(StatusCodes.UnprocessableContent -> ApiError("a stored file is not valid FIT"))
                case Success(files) =>
                  FitMerge.mergeWithReport(files, MergeOptions(lap)) match {
                    case Left(msg) => complete(StatusCodes.Conflict -> ApiError(msg))
                    case Right(MergeOutcome(merged, report)) =>
                      if (req.dryRun) complete(MergeResponse(None, report))
                      else
                        onSuccess(store.put(codec.encode(merged), config.sessionTtl)) { id =>
                          complete(MergeResponse(Some(id), report))
                        }
                  }
              }
          }
        case Failure(ex) => complete(StatusCodes.InternalServerError -> ApiError(ex.getMessage))
      }
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private def completeStoreError(e: StoreError): Route = e match {
    case StoreError.NotFound => complete(StatusCodes.NotFound -> ApiError("file not found"))
    case StoreError.Expired  => complete(StatusCodes.Gone -> ApiError("session expired — re-upload"))
  }

  /** Fetch + decode a stored file by id, mapping store/decode failures to the right status. */
  private def withFile(id: String)(f: FitFile => Route): Route =
    onComplete(store.get(id).map(_.map(b => Try(codec.decode(b))))) {
      case Success(Right(Success(file))) => f(file)
      case Success(Right(Failure(_))) =>
        complete(StatusCodes.UnprocessableContent -> ApiError("stored file is not valid FIT"))
      case Success(Left(err)) => completeStoreError(err)
      case Failure(ex)        => complete(StatusCodes.InternalServerError -> ApiError(ex.getMessage))
    }
}
