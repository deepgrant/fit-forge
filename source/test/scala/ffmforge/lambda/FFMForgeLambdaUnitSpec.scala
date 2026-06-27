package ffmforge.lambda

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import ffmforge.DownloadFormat
import ffmforge.FFMForgeConfig
import ffmforge.fit.GarminFitCodec
import ffmforge.http.DownloadUrlResponse
import ffmforge.http.JsonProtocol
import ffmforge.http.TestFixtures
import ffmforge.store.FitStore
import ffmforge.store.PresignedDownload
import ffmforge.store.PresignedUpload
import ffmforge.store.StoreError
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import spray.json.enrichAny
import spray.json.enrichString

final class FFMForgeLambdaUnitSpec extends AnyFunSuite with Matchers with ScalaFutures {

  import JsonProtocol._

  private given ExecutionContext = ExecutionContext.global
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(25, Millis))

  test("GPX download reuses an existing derived object") {
    val id    = s"1782527814802_${UUID.randomUUID()}"
    val store = new InMemoryFitStore(id, TestFixtures.sampleBytes)
    val api = new FFMForgeLambdaApi(
      store,
      new GarminFitCodec(),
      FFMForgeConfig(8080, 2.hours, 15.minutes, "unused", "unused"),
    )

    val first = responseBody(api.handle(event("GET", s"/ffmforge/v1/fit/$id/download", Map("format" -> "gpx"))))
      .convertTo[DownloadUrlResponse]
    val second = responseBody(api.handle(event("GET", s"/ffmforge/v1/fit/$id/download", Map("format" -> "gpx"))))
      .convertTo[DownloadUrlResponse]

    first.format shouldBe DownloadFormat.Gpx
    first.filename should endWith(".gpx")
    second.format shouldBe DownloadFormat.Gpx
    store.derivedPutCount shouldBe 1
    store.hasObject(id, DownloadFormat.Gpx) shouldBe true
  }

  private def event(method: String, path: String, query: Map[String, String]): String = {
    val queryJson = if (query.isEmpty) "" else s""","queryStringParameters":${query.toJson.compactPrint}"""
    s"""{"rawPath":"$path","requestContext":{"http":{"method":"$method"}}$queryJson,"body":"","isBase64Encoded":false}"""
  }

  private def responseBody(response: Future[String]): spray.json.JsValue = {
    val obj = response.futureValue.parseJson.asJsObject
    obj.fields("statusCode").convertTo[Int] shouldBe StatusCodes.OK.intValue
    obj.fields("body").convertTo[String].parseJson
  }
}

private final class InMemoryFitStore(initialId: String, initialBytes: Array[Byte])(using ExecutionContext)
    extends FitStore {

  private val expiresAt = Instant.parse("2026-06-15T10:00:00Z")
  private val objects =
    mutable.Map[(String, DownloadFormat), Array[Byte]]((initialId, DownloadFormat.Fit) -> initialBytes)

  private val derivedPuts = AtomicInteger(0)

  def derivedPutCount: Int = derivedPuts.get()

  def hasObject(id: String, format: DownloadFormat): Boolean = objects.contains((id, format))

  def createUpload(ttl: FiniteDuration, presignTtl: FiniteDuration): Future[PresignedUpload] =
    Future.failed(new UnsupportedOperationException("unused"))

  def put(bytes: Array[Byte], ttl: FiniteDuration): Future[String] = Future {
    val id = s"${expiresAt.toEpochMilli}_${UUID.randomUUID()}"
    objects((id, DownloadFormat.Fit)) = bytes
    id
  }

  def putDerived(id: String, format: DownloadFormat, bytes: Array[Byte]): Future[Either[StoreError, Unit]] = Future {
    objects((id, format)) = bytes
    derivedPuts.incrementAndGet()
    Right(())
  }

  def get(id: String): Future[Either[StoreError, Array[Byte]]] =
    Future.successful(objects.get((id, DownloadFormat.Fit)).toRight(StoreError.NotFound))

  def createDownload(
      id: String,
      format: DownloadFormat,
      presignTtl: FiniteDuration,
  ): Future[Either[StoreError, PresignedDownload]] =
    Future.successful(
      if (objects.contains((id, format)))
        Right(
          PresignedDownload(
            id,
            s"https://example.test/$id.${format.extension}",
            expiresAt,
            format,
            s"$id.${format.extension}",
          )
        )
      else Left(StoreError.NotFound)
    )

  def delete(id: String): Future[Unit] = Future.successful {
    DownloadFormat.values.foreach(format => objects.remove((id, format)))
  }

  def sweepExpired(): Future[Int] = Future.successful(0)
}
