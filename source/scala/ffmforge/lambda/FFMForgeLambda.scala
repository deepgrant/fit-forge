package ffmforge.lambda

import java.io.InputStream
import java.io.OutputStream
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
import ffmforge.FFMForgeConfig
import ffmforge.fit.FitCodec
import ffmforge.fit.FitFile
import ffmforge.fit.FitLayout
import ffmforge.fit.FitMerge
import ffmforge.fit.FitSummary
import ffmforge.fit.GarminFitCodec
import ffmforge.fit.LapStrategy
import ffmforge.fit.MergeOptions
import ffmforge.fit.MergeOutcome
import ffmforge.http.ApiError
import ffmforge.http.DescribeRequest
import ffmforge.http.DownloadUrlResponse
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

    case ("POST", "/ffmforge/v1/fit/merge") =>
      responseMerge(event.bodyJson.convertTo[MergeRequest])

    case ("GET", SummaryPath(id)) =>
      responseWithFile(id)(file =>
        SummaryResponse(FitSummary.ride(file), FitSummary.devices(file), FitLayout.of(file)).toJson
      )

    case ("GET", TrackPath(id)) =>
      responseWithFile(id)(JsonProtocol.trackGeoJson)

    case ("GET", DownloadPath(id)) =>
      await(store.createDownload(id, config.presignTtl)) match {
        case Right(d)  => response(StatusCodes.OK, DownloadUrlResponse(d.id, d.url, d.expiresAt).toJson)
        case Left(err) => responseStoreError(err)
      }

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

  private def responseWithFile(id: String)(f: FitFile => JsValue): String =
    readFile(id) match {
      case Right(file)         => response(StatusCodes.OK, f(file))
      case Left((status, err)) => response(status, err.toJson)
    }

  private def readFile(id: String): Either[(StatusCode, ApiError), FitFile] =
    await(store.get(id)) match {
      case Right(bytes) =>
        Try(codec.decode(bytes)) match {
          case Success(file) => Right(file)
          case Failure(_)    => Left(StatusCodes.UnprocessableContent -> ApiError("stored file is not valid FIT"))
        }
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
    LambdaEvent(method, path, decodeBody(body, isBase64))
  }

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

final case class LambdaEvent(method: String, path: String, body: String) {
  def bodyJson: JsValue =
    if (body.trim.isEmpty) JsObject.empty else body.parseJson
}
