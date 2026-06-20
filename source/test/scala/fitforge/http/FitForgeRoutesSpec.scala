package fitforge.http

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.DurationInt

import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.Multipart
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.ByteString
import org.scalatest.Outcome
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import fitforge.FitForgeConfig
import fitforge.fit.GarminFitCodec

final class FitForgeRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with SprayJsonSupport {

  import JsonProtocol._

  implicit val routeTimeout: RouteTestTimeout = RouteTestTimeout(30.seconds)

  private val base  = Instant.parse("2026-06-15T08:00:00Z")
  private val clock = new AtomicReference[Instant](base)
  private val codec = new GarminFitCodec()
  private val config =
    FitForgeConfig(8080, 2.hours, "build/static-test", AwsS3Support.bucket, AwsS3Support.region)
  private lazy val store  = AwsS3Support.newStore(() => clock.get)(using system)
  private lazy val routes = new FitForgeRoutes(store, codec, config)(using system).routes

  override def withFixture(test: NoArgTest): Outcome = {
    assume(AwsS3Support.available, "AWS credentials not available (default profile)")
    super.withFixture(test)
  }

  private def upload(bytes: Array[Byte]): Multipart.FormData =
    Multipart.FormData(
      Multipart.FormData.BodyPart.Strict(
        "file",
        HttpEntity(ContentTypes.`application/octet-stream`, ByteString.fromArray(bytes)),
        Map("filename" -> "ride.fit"),
      )
    )

  private def uploadSampleId(): String =
    Post("/fitforge/v1/fit/upload", upload(TestFixtures.sampleBytes)) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[UploadResponse].files.head.id
    }

  "the fit-forge API" should {

    "report health" in {
      Get("/health") ~> routes ~> check { status shouldBe StatusCodes.OK }
    }

    "upload, summarise, track and download a real file" in {
      val resp = Post("/fitforge/v1/fit/upload", upload(TestFixtures.sampleBytes)) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[UploadResponse]
      }
      val file = resp.files.head
      file.summary.totalDistanceM.get shouldBe 32210.0 +- 50.0
      file.devices.exists(_.manufacturer == "Polar") shouldBe true

      Get(s"/fitforge/v1/fit/${file.id}/summary") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SummaryResponse].summary.totalDistanceM.get shouldBe 32210.0 +- 50.0
      }
      Get(s"/fitforge/v1/fit/${file.id}/track") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("FeatureCollection")
      }
      Get(s"/fitforge/v1/fit/${file.id}/download") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        codec.decode(responseAs[ByteString].toArray).records.size shouldBe 5637
      }
    }

    "merge two segments (dry run reports a gap, real run stores a file)" in {
      val (a, b) = TestFixtures.twoSegments(codec)
      val idA = Post("/fitforge/v1/fit/upload", upload(a)) ~> routes ~> check {
        responseAs[UploadResponse].files.head.id
      }
      val idB = Post("/fitforge/v1/fit/upload", upload(b)) ~> routes ~> check {
        responseAs[UploadResponse].files.head.id
      }

      val dry =
        Post("/fitforge/v1/fit/merge", MergeRequest(Vector(idA, idB), "preserve", "OnePerSegment", dryRun = true)) ~>
          routes ~> check {
            status shouldBe StatusCodes.OK
            responseAs[MergeResponse]
          }
      dry.id shouldBe None
      dry.report.gaps should not be empty
      dry.report.movingSeconds should be < dry.report.elapsedSeconds

      val real =
        Post("/fitforge/v1/fit/merge", MergeRequest(Vector(idA, idB), "preserve", "OnePerSegment", dryRun = false)) ~>
          routes ~> check {
            status shouldBe StatusCodes.OK
            responseAs[MergeResponse]
          }
      real.id should not be None
      Get(s"/fitforge/v1/fit/${real.id.get}/download") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        codec.decode(responseAs[ByteString].toArray).records.size should be > 4000
      }
    }

    "reject a bad upload with 422" in {
      Post("/fitforge/v1/fit/upload", upload("not a fit file".getBytes("UTF-8"))) ~> routes ~> check {
        status shouldBe StatusCodes.UnprocessableContent
      }
    }

    "return 404 for an unknown id" in {
      val id = s"${base.toEpochMilli + 999999}_${UUID.randomUUID()}"
      Get(s"/fitforge/v1/fit/$id/summary") ~> routes ~> check { status shouldBe StatusCodes.NotFound }
    }

    "return 410 for an expired id" in {
      val id = uploadSampleId()
      clock.set(base.plusSeconds(3.hours.toSeconds))
      Get(s"/fitforge/v1/fit/$id/summary") ~> routes ~> check { status shouldBe StatusCodes.Gone }
      clock.set(base)
    }

    "return 409 when segments overlap" in {
      val id = uploadSampleId()
      Post("/fitforge/v1/fit/merge", MergeRequest(Vector(id, id), "preserve", "OnePerSegment", dryRun = true)) ~>
        routes ~> check { status shouldBe StatusCodes.Conflict }
    }

    "reject gapHandling=close with 422" in {
      val id = uploadSampleId()
      Post("/fitforge/v1/fit/merge", MergeRequest(Vector(id), "close", "OnePerSegment", dryRun = true)) ~>
        routes ~> check { status shouldBe StatusCodes.UnprocessableContent }
    }
  }
}
