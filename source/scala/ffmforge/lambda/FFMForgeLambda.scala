package ffmforge.lambda

import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

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
    val result = api.handle(event)
    output.write(result.getBytes(StandardCharsets.UTF_8))
  }
}

final class FFMForgeLambdaApi(store: FitStore, codec: FitCodec, config: FFMForgeConfig)(using ExecutionContext) {

  import JsonProtocol._

  private val SummaryPath  = "^/ffmforge/v1/fit/([^/]+)/summary$".r
  private val TrackPath    = "^/ffmforge/v1/fit/([^/]+)/track$".r
  private val DownloadPath = "^/ffmforge/v1/fit/([^/]+)/download$".r

  def handle(eventJson: String): String =
    Try(eventJson.parseJson.asJsObject.fields).flatMap { fields =>
      if (fields.get("source").contains(JsString("ffmforge.cleanup")))
        Try(response(StatusCodes.OK, JsObject("deleted" -> JsNumber(await(store.sweepExpired())))))
      else
        Try(route(parseEvent(fields)))
    } match {
      case Success(value) => value
      case Failure(e)     => response(StatusCodes.InternalServerError, ApiError(e.getMessage).toJson)
    }

  private def route(event: LambdaEvent): String = (event.method, event.path) match {
    case ("OPTIONS", path) if path.startsWith("/ffmforge/v1/") => response(StatusCodes.OK, JsObject.empty)

    case ("POST", "/ffmforge/v1/uploads") =>
      val req = event.bodyJson.convertTo[UploadUrlRequest]
      val urls = await(Future.traverse(req.files) { name =>
        store
          .createUpload(config.sessionTtl, config.presignTtl)
          .map(u => UploadUrlResult(u.id, name, u.url, u.expiresAt))
      })
      response(StatusCodes.OK, UploadUrlsResponse(urls).toJson)

    case ("POST", "/ffmforge/v1/fit/describe") =>
      val req = event.bodyJson.convertTo[DescribeRequest]
      describe(req.ids) match {
        case Right(value)        => response(StatusCodes.OK, value.toJson)
        case Left((status, err)) => response(status, err.toJson)
      }

    case ("POST", "/ffmforge/v1/fit/codec-demo") =>
      val req = event.bodyJson.convertTo[CodecDemoRequest]
      codecDemo(req.id)

    case ("POST", "/ffmforge/v1/fit/editor/open") =>
      val req = event.bodyJson.convertTo[EditorFileRequest]
      responseWithFile(req.id)(file => FitEditor.open(req.id, file).toJson)

    case ("POST", "/ffmforge/v1/fit/editor/rows") =>
      val req = event.bodyJson.convertTo[EditorRowsRequest]
      responseWithFile(req.id)(file =>
        FitEditor.rows(file, req.messageType, req.offset, req.limit, FitEditor.diagnose(file)).toJson
      )

    case ("POST", "/ffmforge/v1/fit/editor/repair-preview") =>
      val req = event.bodyJson.convertTo[EditorRepairRequest]
      responseWithFile(req.id)(file => FitEditor.preview(file, req.operations).toJson)

    case ("POST", "/ffmforge/v1/fit/editor/export") =>
      val req = event.bodyJson.convertTo[EditorRepairRequest]
      responseWithFile(req.id) { file =>
        val (repaired, preview) = FitEditor.repair(file, req.operations)
        val id                  = await(store.put(codec.encode(repaired), config.sessionTtl))
        ExportRepairResponse(id, preview).toJson
      }

    case ("POST", "/ffmforge/v1/fit/merge") =>
      responseMerge(event.bodyJson.convertTo[MergeRequest])

    case ("GET", SummaryPath(id)) =>
      responseWithFile(id)(file =>
        SummaryResponse(FitSummary.ride(file), FitSummary.devices(file), FitLayout.of(file)).toJson
      )

    case ("GET", TrackPath(id)) =>
      responseWithFile(id)(JsonProtocol.trackGeoJson)

    case ("GET", DownloadPath(id)) =>
      responseDownload(id, downloadFormat(event))

    case _ => response(StatusCodes.NotFound, ApiError(s"No route for ${event.method} ${event.path}").toJson)
  }

  private def describe(ids: Vector[String]): Either[(StatusCode, ApiError), UploadResponse] = {
    val files = ids.map { id =>
      readFile(id).map(file =>
        UploadFileResult(id, file.fileId, FitSummary.ride(file), FitSummary.devices(file), FitLayout.of(file))
      )
    }
    files.collectFirst { case Left(err) => err } match {
      case Some(err) => Left(err)
      case None      => Right(UploadResponse(files.collect { case Right(file) => file }))
    }
  }

  private def codecDemo(id: String): String =
    readBytes(id) match {
      case Right(bytes) =>
        Try(FitCodecReport.fromBytes(id, bytes, codec)) match {
          case Success(report) => response(StatusCodes.OK, report.toJson)
          case Failure(_) => response(StatusCodes.UnprocessableContent, ApiError("stored file is not valid FIT").toJson)
        }
      case Left((status, err)) => response(status, err.toJson)
    }

  private def responseMerge(req: MergeRequest): String =
    if (req.gapHandling != "preserve")
      response(StatusCodes.UnprocessableContent, ApiError("gapHandling 'close' is not implemented yet").toJson)
    else {
      val lap   = if (req.lapStrategy == "KeepOriginal") LapStrategy.KeepOriginal else LapStrategy.OnePerSegment
      val files = req.ids.map(readFile)
      files.collectFirst { case Left(err) => err } match {
        case Some((status, err)) => response(status, err.toJson)
        case None =>
          FitMerge.mergeWithReport(files.collect { case Right(file) => file }, MergeOptions(lap)) match {
            case Left(msg) => response(StatusCodes.Conflict, ApiError(msg).toJson)
            case Right(MergeOutcome(merged, report)) =>
              if (req.dryRun) response(StatusCodes.OK, MergeResponse(None, report).toJson)
              else {
                val id = await(store.put(codec.encode(merged), config.sessionTtl))
                response(StatusCodes.OK, MergeResponse(Some(id), report).toJson)
              }
          }
      }
    }

  private def responseDownload(id: String, format: Either[String, DownloadFormat]): String =
    format match {
      case Left(message) => response(StatusCodes.UnprocessableContent, ApiError(message).toJson)
      case Right(DownloadFormat.Fit) =>
        await(store.createDownload(id, DownloadFormat.Fit, config.presignTtl)) match {
          case Right(d) =>
            response(StatusCodes.OK, DownloadUrlResponse(d.id, d.url, d.expiresAt, d.format, d.filename).toJson)
          case Left(err) => responseStoreError(err)
        }
      case Right(DownloadFormat.Gpx) =>
        readFile(id) match {
          case Right(file) =>
            val gpxBytes = GpxXmlWriter.writeBytes(FitToGpx.convert(file))
            await(store.putDerived(id, DownloadFormat.Gpx, gpxBytes)) match {
              case Right(()) =>
                await(store.createDownload(id, DownloadFormat.Gpx, config.presignTtl)) match {
                  case Right(d) =>
                    response(StatusCodes.OK, DownloadUrlResponse(d.id, d.url, d.expiresAt, d.format, d.filename).toJson)
                  case Left(err) => responseStoreError(err)
                }
              case Left(err) => responseStoreError(err)
            }
          case Left((status, err)) => response(status, err.toJson)
        }
    }

  private def downloadFormat(event: LambdaEvent): Either[String, DownloadFormat] =
    event.query.get("format") match {
      case None        => Right(DownloadFormat.Default)
      case Some(value) => DownloadFormat.fromWireName(value).toRight(s"Unsupported download format '$value'")
    }

  private def responseWithFile(id: String)(f: FitFile => JsValue): String =
    readFile(id) match {
      case Right(file)         => response(StatusCodes.OK, f(file))
      case Left((status, err)) => response(status, err.toJson)
    }

  private def readFile(id: String): Either[(StatusCode, ApiError), FitFile] =
    readBytes(id).flatMap { bytes =>
      Try(codec.decode(bytes)) match {
        case Success(file) => Right(file)
        case Failure(_)    => Left(StatusCodes.UnprocessableContent -> ApiError("stored file is not valid FIT"))
      }
    }

  private def readBytes(id: String): Either[(StatusCode, ApiError), Array[Byte]] =
    await(store.get(id)) match {
      case Right(bytes)              => Right(bytes)
      case Left(StoreError.NotFound) => Left(StatusCodes.NotFound -> ApiError("file not found"))
      case Left(StoreError.Expired)  => Left(StatusCodes.Gone -> ApiError("session expired — re-upload"))
    }

  private def responseStoreError(e: StoreError): String = e match {
    case StoreError.NotFound => response(StatusCodes.NotFound, ApiError("file not found").toJson)
    case StoreError.Expired  => response(StatusCodes.Gone, ApiError("session expired — re-upload").toJson)
  }

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

  private def await[A](f: Future[A]): A = Await.result(f, 60.seconds)
}

final case class LambdaEvent(method: String, path: String, query: Map[String, String], body: String) {
  def bodyJson: JsValue =
    if (body.trim.isEmpty) JsObject.empty else body.parseJson
}
