package ffmforge.lambda

import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.FutureConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import ffmforge.DownloadFormat
import ffmforge.FFMForgeConfig
import ffmforge.fit.ExportRepairResponse
import ffmforge.fit.FitCodec
import ffmforge.fit.FitCodecReport
import ffmforge.fit.FitEditor
import ffmforge.fit.FitFile
import ffmforge.fit.FitLayout
import ffmforge.fit.FitMerge
import ffmforge.fit.FitSummary
import ffmforge.fit.GarminFitCodec
import ffmforge.fit.LapStrategy
import ffmforge.fit.MergeOptions
import ffmforge.fit.MergeOutcome
import ffmforge.gpx.FitToGpx
import ffmforge.gpx.GpxXmlWriter
import ffmforge.http.ApiError
import ffmforge.http.CodecDemoRequest
import ffmforge.http.DescribeRequest
import ffmforge.http.DownloadUrlResponse
import ffmforge.http.EditorFileRequest
import ffmforge.http.EditorRepairRequest
import ffmforge.http.EditorRowsRequest
import ffmforge.http.JsonProtocol
import ffmforge.http.MergeRequest
import ffmforge.http.MergeResponse
import ffmforge.http.SummaryResponse
import ffmforge.http.UploadFileResult
import ffmforge.http.UploadResponse
import ffmforge.http.UploadUrlRequest
import ffmforge.http.UploadUrlResult
import ffmforge.http.UploadUrlsResponse
import ffmforge.store.FitStore
import ffmforge.store.S3FitStore
import ffmforge.store.StoreError
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.model.StatusCodes
import spray.json.JsBoolean
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.JsonParser
import spray.json.JsonReader
import spray.json.enrichAny
import spray.json.enrichString

/** AWS Lambda API Gateway proxy handler for the versioned FFMForge API. */
final class FFMForgeLambda extends RequestStreamHandler {

  private given ExecutionContext = ExecutionContext.global

  private val config = FFMForgeConfig.fromEnv()
  private val codec  = new GarminFitCodec()
  private val store  = S3FitStore.fromDefaultChain(config.s3Bucket, () => Instant.now())
  private val api    = new FFMForgeLambdaApi(store, codec, config)

  def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
    val event  = String(input.readAllBytes(), StandardCharsets.UTF_8)
    val result = completeWithin(api.handle(event), context)
    output.write(result.getBytes(StandardCharsets.UTF_8))
  }

  private def completeWithin(response: Future[String], context: Context): String = {
    import JsonProtocol._

    val timeoutMs = math.max(1L, context.getRemainingTimeInMillis.toLong - 250L)
    try response.asJava.toCompletableFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
    catch {
      case _: TimeoutException =>
        FFMForgeLambdaApi.response(StatusCodes.GatewayTimeout, ApiError("request timed out").toJson)
      case e: ExecutionException =>
        FFMForgeLambdaApi.response(
          StatusCodes.InternalServerError,
          ApiError(Option(e.getCause).map(_.getMessage).getOrElse(e.getMessage)).toJson,
        )
      case NonFatal(e) =>
        FFMForgeLambdaApi.response(StatusCodes.InternalServerError, ApiError(e.getMessage).toJson)
    }
  }
}

final class FFMForgeLambdaApi(store: FitStore, codec: FitCodec, config: FFMForgeConfig)(using ExecutionContext) {

  import JsonProtocol._

  private val SummaryPath  = "^/ffmforge/v1/fit/([^/]+)/summary$".r
  private val TrackPath    = "^/ffmforge/v1/fit/([^/]+)/track$".r
  private val DownloadPath = "^/ffmforge/v1/fit/([^/]+)/download$".r

  def handle(eventJson: String): Future[String] =
    Try(eventJson.parseJson.asJsObject.fields) match {
      case Failure(e) => Future.successful(responseBadRequest("invalid lambda event JSON", e))
      case Success(fields) =>
        val routed =
          if (fields.get("source").contains(JsString("ffmforge.cleanup")))
            store.sweepExpired().map(deleted => response(StatusCodes.OK, JsObject("deleted" -> JsNumber(deleted))))
          else Future.fromTry(Try(parseEvent(fields))).flatMap(event => Future.fromTry(Try(route(event))).flatten)
        routed.recover { case NonFatal(e) =>
          response(StatusCodes.InternalServerError, ApiError(e.getMessage).toJson)
        }
    }

  private def route(event: LambdaEvent): Future[String] = (event.method, event.path) match {
    case ("OPTIONS", path) if path.startsWith("/ffmforge/v1/") =>
      Future.successful(response(StatusCodes.OK, JsObject.empty))

    case ("POST", "/ffmforge/v1/uploads") =>
      request[UploadUrlRequest](event) { req =>
        Future
          .traverse(req.files) { name =>
            store
              .createUpload(config.sessionTtl, config.presignTtl)
              .map(u => UploadUrlResult(u.id, name, u.url, u.expiresAt))
          }
          .map(urls => response(StatusCodes.OK, UploadUrlsResponse(urls).toJson))
      }

    case ("POST", "/ffmforge/v1/fit/describe") =>
      request[DescribeRequest](event) { req =>
        describe(req.ids).map {
          case Right(value)        => response(StatusCodes.OK, value.toJson)
          case Left((status, err)) => response(status, err.toJson)
        }
      }

    case ("POST", "/ffmforge/v1/fit/codec-demo") =>
      request[CodecDemoRequest](event)(req => codecDemo(req.id))

    case ("POST", "/ffmforge/v1/fit/editor/open") =>
      request[EditorFileRequest](event)(req => responseWithFile(req.id)(file => FitEditor.open(req.id, file).toJson))

    case ("POST", "/ffmforge/v1/fit/editor/rows") =>
      request[EditorRowsRequest](event) { req =>
        responseWithFile(req.id)(file =>
          FitEditor.rows(file, req.messageType, req.offset, req.limit, FitEditor.diagnose(file)).toJson
        )
      }

    case ("POST", "/ffmforge/v1/fit/editor/repair-preview") =>
      request[EditorRepairRequest](event)(req =>
        responseWithFile(req.id)(file => FitEditor.preview(file, req.operations).toJson)
      )

    case ("POST", "/ffmforge/v1/fit/editor/export") =>
      request[EditorRepairRequest](event) { req =>
        responseWithFileF(req.id) { file =>
          val (repaired, preview) = FitEditor.repair(file, req.operations)
          store
            .put(codec.encode(repaired), config.sessionTtl)
            .map(id => response(StatusCodes.OK, ExportRepairResponse(id, preview).toJson))
        }
      }

    case ("POST", "/ffmforge/v1/fit/merge") =>
      request[MergeRequest](event)(responseMerge)

    case ("GET", SummaryPath(id)) =>
      responseWithFile(id)(file =>
        SummaryResponse(FitSummary.ride(file), FitSummary.devices(file), FitLayout.of(file)).toJson
      )

    case ("GET", TrackPath(id)) =>
      responseWithFile(id)(JsonProtocol.trackGeoJson)

    case ("GET", DownloadPath(id)) =>
      responseDownload(id, downloadFormat(event))

    case _ =>
      Future.successful(response(StatusCodes.NotFound, ApiError(s"No route for ${event.method} ${event.path}").toJson))
  }

  private def request[A: JsonReader](event: LambdaEvent)(f: A => Future[String]): Future[String] =
    Try {
      val json = if (event.body.trim.isEmpty) JsObject.empty else JsonParser(event.body)
      json.convertTo[A]
    } match {
      case Success(value) => f(value)
      case Failure(e)     => Future.successful(responseBadRequest("invalid request JSON", e))
    }

  private def describe(ids: Vector[String]): Future[Either[(StatusCode, ApiError), UploadResponse]] =
    Future
      .traverse(ids) { id =>
        readFile(id).map {
          case Right(file) =>
            Right(
              UploadFileResult(
                id = id,
                fileId = file.fileId,
                summary = FitSummary.ride(file),
                devices = FitSummary.devices(file),
                layout = FitLayout.of(file),
              )
            )
          case Left(error) =>
            Left(error)
        }
      }
      .map { files =>
        files.collectFirst { case Left(err) => err } match {
          case Some(err) => Left(err)
          case None      => Right(UploadResponse(files.collect { case Right(file) => file }))
        }
      }

  private def codecDemo(id: String): Future[String] =
    readBytes(id).map {
      case Right(bytes) =>
        Try(FitCodecReport.fromBytes(id, bytes, codec)) match {
          case Success(report) => response(StatusCodes.OK, report.toJson)
          case Failure(_) => response(StatusCodes.UnprocessableContent, ApiError("stored file is not valid FIT").toJson)
        }
      case Left((status, err)) => response(status, err.toJson)
    }

  private def responseMerge(req: MergeRequest): Future[String] =
    if (req.gapHandling != "preserve")
      Future.successful(
        response(StatusCodes.UnprocessableContent, ApiError("gapHandling 'close' is not implemented yet").toJson)
      )
    else {
      val lap = if (req.lapStrategy == "KeepOriginal") LapStrategy.KeepOriginal else LapStrategy.OnePerSegment
      Future.traverse(req.ids)(readFile).flatMap { files =>
        files.collectFirst { case Left(err) => err } match {
          case Some((status, err)) => Future.successful(response(status, err.toJson))
          case None =>
            FitMerge.mergeWithReport(files.collect { case Right(file) => file }, MergeOptions(lap)) match {
              case Left(msg) => Future.successful(response(StatusCodes.Conflict, ApiError(msg).toJson))
              case Right(MergeOutcome(merged, report)) =>
                if (req.dryRun) Future.successful(response(StatusCodes.OK, MergeResponse(None, report).toJson))
                else
                  store
                    .put(codec.encode(merged), config.sessionTtl)
                    .map(id => response(StatusCodes.OK, MergeResponse(Some(id), report).toJson))
            }
        }
      }
    }

  private def responseDownload(id: String, format: Either[String, DownloadFormat]): Future[String] =
    format match {
      case Left(message) => Future.successful(response(StatusCodes.UnprocessableContent, ApiError(message).toJson))
      case Right(DownloadFormat.Fit) =>
        store.createDownload(id, DownloadFormat.Fit, config.presignTtl).map {
          case Right(d) =>
            response(StatusCodes.OK, DownloadUrlResponse(d.id, d.url, d.expiresAt, d.format, d.filename).toJson)
          case Left(err) => responseStoreError(err)
        }
      case Right(DownloadFormat.Gpx) =>
        store.createDownload(id, DownloadFormat.Gpx, config.presignTtl).flatMap {
          case Right(d) =>
            Future.successful(
              response(StatusCodes.OK, DownloadUrlResponse(d.id, d.url, d.expiresAt, d.format, d.filename).toJson)
            )
          case Left(StoreError.Expired) => Future.successful(responseStoreError(StoreError.Expired))
          case Left(StoreError.NotFound) =>
            readFile(id).flatMap {
              case Right(file) =>
                val gpxBytes = GpxXmlWriter.writeBytes(FitToGpx.convert(file))
                store.putDerived(id, DownloadFormat.Gpx, gpxBytes).flatMap {
                  case Right(()) =>
                    store.createDownload(id, DownloadFormat.Gpx, config.presignTtl).map {
                      case Right(d) =>
                        response(
                          StatusCodes.OK,
                          DownloadUrlResponse(d.id, d.url, d.expiresAt, d.format, d.filename).toJson,
                        )
                      case Left(err) => responseStoreError(err)
                    }
                  case Left(err) => Future.successful(responseStoreError(err))
                }
              case Left((status, err)) => Future.successful(response(status, err.toJson))
            }
        }
    }

  private def downloadFormat(event: LambdaEvent): Either[String, DownloadFormat] =
    event.query.get("format") match {
      case None        => Right(DownloadFormat.Default)
      case Some(value) => DownloadFormat.fromWireName(value).toRight(s"Unsupported download format '$value'")
    }

  private def responseWithFile(id: String)(f: FitFile => JsValue): Future[String] =
    responseWithFileF(id)(file => Future.successful(response(StatusCodes.OK, f(file))))

  private def responseWithFileF(id: String)(f: FitFile => Future[String]): Future[String] =
    readFile(id).flatMap {
      case Right(file)         => f(file)
      case Left((status, err)) => Future.successful(response(status, err.toJson))
    }

  private def readFile(id: String): Future[Either[(StatusCode, ApiError), FitFile]] =
    readBytes(id).map(_.flatMap { bytes =>
      Try(codec.decode(bytes)) match {
        case Success(file) => Right(file)
        case Failure(_)    => Left(StatusCodes.UnprocessableContent -> ApiError("stored file is not valid FIT"))
      }
    })

  private def readBytes(id: String): Future[Either[(StatusCode, ApiError), Array[Byte]]] =
    store.get(id).map {
      case Right(bytes)              => Right(bytes)
      case Left(StoreError.NotFound) => Left(StatusCodes.NotFound -> ApiError("file not found"))
      case Left(StoreError.Expired)  => Left(StatusCodes.Gone -> ApiError("session expired — re-upload"))
    }

  private def responseStoreError(e: StoreError): String = e match {
    case StoreError.NotFound => response(StatusCodes.NotFound, ApiError("file not found").toJson)
    case StoreError.Expired  => response(StatusCodes.Gone, ApiError("session expired — re-upload").toJson)
  }

  private def responseBadRequest(prefix: String, e: Throwable): String =
    response(StatusCodes.BadRequest, ApiError(s"$prefix: ${e.getMessage}").toJson)

  private def parseEvent(fields: Map[String, JsValue]): LambdaEvent = {
    val path = fields
      .get("rawPath")
      .orElse(fields.get("path"))
      .collect { case JsString(s) => s }
      .getOrElse("/")
    val method = fields
      .get("requestContext")
      .flatMap(_.asJsObject.fields.get("http"))
      .flatMap(_.asJsObject.fields.get("method"))
      .orElse(fields.get("httpMethod"))
      .collect { case JsString(s) => s.toUpperCase }
      .getOrElse("GET")
    val isBase64 = fields.get("isBase64Encoded").collect { case JsBoolean(b) => b }.getOrElse(false)
    val body     = fields.get("body").collect { case JsString(s) => s }.getOrElse("")
    LambdaEvent(method, path, queryParams(fields), decodeBody(body, isBase64))
  }

  private def queryParams(fields: Map[String, JsValue]): Map[String, String] = {
    val fromObject = fields
      .get("queryStringParameters")
      .collect { case obj: JsObject =>
        obj.fields.collect { case (key, JsString(value)) => key -> value }
      }
      .getOrElse(Map.empty)
    val fromRaw = fields
      .get("rawQueryString")
      .collect { case JsString(value) => parseRawQuery(value) }
      .getOrElse(Map.empty)
    fromRaw ++ fromObject
  }

  private def parseRawQuery(value: String): Map[String, String] =
    value
      .split("&")
      .toVector
      .filter(_.nonEmpty)
      .flatMap { pair =>
        val parts = pair.split("=", 2)
        parts.headOption.map(key => decodeQueryPart(key) -> parts.lift(1).map(decodeQueryPart).getOrElse(""))
      }
      .toMap

  private def decodeQueryPart(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def decodeBody(body: String, isBase64: Boolean): String =
    if (isBase64) String(Base64.getDecoder.decode(body), StandardCharsets.UTF_8) else body

  private def response(status: StatusCode, body: JsValue): String =
    FFMForgeLambdaApi.response(status, body)
}

object FFMForgeLambdaApi {

  def response(status: StatusCode, body: JsValue): String =
    JsObject(
      "statusCode" -> JsNumber(status.intValue),
      "headers" -> JsObject(
        "content-type"                 -> JsString("application/json"),
        "access-control-allow-origin"  -> JsString("*"),
        "access-control-allow-methods" -> JsString("GET,POST,OPTIONS"),
        "access-control-allow-headers" -> JsString("content-type"),
      ),
      "isBase64Encoded" -> JsBoolean(false),
      "body"            -> JsString(body.compactPrint),
    ).compactPrint
}

final case class LambdaEvent(method: String, path: String, query: Map[String, String], body: String) {}
