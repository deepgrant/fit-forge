package ffmforge.lambda

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

import ffmforge.FFMForgeConfig
import ffmforge.fit.CodecDemoReport
import ffmforge.fit.GarminFitCodec
import ffmforge.http.AwsS3Support
import ffmforge.http.CodecDemoRequest
import ffmforge.http.DescribeRequest
import ffmforge.http.DownloadUrlResponse
import ffmforge.http.JsonProtocol
import ffmforge.http.MergeRequest
import ffmforge.http.MergeResponse
import ffmforge.http.TestFixtures
import ffmforge.http.UploadResponse
import ffmforge.http.UploadUrlRequest
import ffmforge.http.UploadUrlsResponse
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.Outcome
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json.enrichAny
import spray.json.enrichString

final class FFMForgeLambdaSpec extends AnyFunSuite with Matchers {

  import JsonProtocol._

  private given ExecutionContext = ExecutionContext.global

  private val base       = Instant.parse("2026-06-15T08:00:00Z")
  private val clock      = new AtomicReference[Instant](base)
  private val codec      = new GarminFitCodec()
  private lazy val store = AwsS3Support.newStore(() => clock.get)
  private lazy val api = new FFMForgeLambdaApi(
    store,
    codec,
    FFMForgeConfig(8080, 2.hours, 15.minutes, "unused", AwsS3Support.bucket.getOrElse("missing-test-bucket")),
  )

  override def withFixture(test: NoArgTest): Outcome = {
    assume(AwsS3Support.available, "AWS credentials/test bucket not available")
    super.withFixture(test)
  }

  test("unknown route returns NotFound") {
    val res = api.handle(event("GET", "/ffmforge/v1/unknown"))
    res.parseJson.asJsObject.fields("statusCode").convertTo[Int] shouldBe StatusCodes.NotFound.intValue
  }

  test("presigned upload, describe, merge and presigned download use real S3") {
    val uploadA = uploadViaPresignedUrl("a.fit", TestFixtures.twoSegments(codec)._1)
    val uploadB = uploadViaPresignedUrl("b.fit", TestFixtures.twoSegments(codec)._2)

    val describe = responseBody(
      api.handle(
        event("POST", "/ffmforge/v1/fit/describe", DescribeRequest(Vector(uploadA, uploadB)).toJson.compactPrint)
      )
    ).convertTo[UploadResponse]
    describe.files.size shouldBe 2
    describe.files.head.summary.totalDistanceM should not be empty

    val dry = responseBody(
      api.handle(
        event(
          "POST",
          "/ffmforge/v1/fit/merge",
          MergeRequest(Vector(uploadA, uploadB), "preserve", "OnePerSegment", dryRun = true).toJson.compactPrint,
        )
      )
    ).convertTo[MergeResponse]
    dry.id shouldBe None
    dry.report.gaps should not be empty

    val merged = responseBody(
      api.handle(
        event(
          "POST",
          "/ffmforge/v1/fit/merge",
          MergeRequest(Vector(uploadA, uploadB), "preserve", "OnePerSegment", dryRun = false).toJson.compactPrint,
        )
      )
    ).convertTo[MergeResponse]
    merged.id should not be None

    val download = responseBody(api.handle(event("GET", s"/ffmforge/v1/fit/${merged.id.get}/download")))
      .convertTo[DownloadUrlResponse]
    download.url should startWith("https://")
  }

  test("codec demo route round-trips an uploaded FIT file") {
    val id = uploadViaPresignedUrl("codec-demo.fit", TestFixtures.sampleBytes)
    val report = responseBody(
      api.handle(event("POST", "/ffmforge/v1/fit/codec-demo", CodecDemoRequest(id).toJson.compactPrint))
    ).convertTo[CodecDemoReport]

    report.id shouldBe id
    report.originalBytes shouldBe TestFixtures.sampleBytes.length
    report.reencodedBytes should be > 0
    report.original.records should be > 0
    report.redecoded.records shouldBe report.original.records
    report.summary.totalDistanceM should not be empty
    report.checks should not be empty
    report.passed shouldBe true
  }

  test("expired object returns Gone") {
    val id = uploadViaPresignedUrl("expired.fit", TestFixtures.sampleBytes)
    clock.set(base.plusSeconds(3.hours.toSeconds))
    api
      .handle(event("GET", s"/ffmforge/v1/fit/$id/download"))
      .parseJson
      .asJsObject
      .fields("statusCode")
      .convertTo[Int] shouldBe StatusCodes.Gone.intValue
    clock.set(base)
  }

  private def uploadViaPresignedUrl(name: String, bytes: Array[Byte]): String = {
    val upload = responseBody(
      api.handle(event("POST", "/ffmforge/v1/uploads", UploadUrlRequest(Vector(name)).toJson.compactPrint))
    )
      .convertTo[UploadUrlsResponse]
      .files
      .head
    val res = HttpClient
      .newHttpClient()
      .send(
        HttpRequest.newBuilder(URI.create(upload.url)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
        HttpResponse.BodyHandlers.discarding(),
      )
    res.statusCode() should (be(StatusCodes.OK.intValue).or(be(StatusCodes.Created.intValue)))
    upload.id
  }

  private def event(method: String, path: String, body: String = ""): String =
    s"""{"rawPath":"$path","requestContext":{"http":{"method":"$method"}},"body":${body.toJson.compactPrint},"isBase64Encoded":false,"requestId":"${UUID
        .randomUUID()}"}"""

  private def responseBody(response: String): spray.json.JsValue = {
    val obj = response.parseJson.asJsObject
    obj.fields("statusCode").convertTo[Int] shouldBe StatusCodes.OK.intValue
    obj.fields("body").convertTo[String].parseJson
  }
}
