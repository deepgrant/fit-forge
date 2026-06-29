package ffmforge.gpx

import java.time.Instant

import scala.xml.XML

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ffmforge.fit.Event
import ffmforge.fit.FileId
import ffmforge.fit.FitFile
import ffmforge.fit.FitProfile
import ffmforge.fit.FitViews
import ffmforge.fit.GpsPoint
import ffmforge.fit.Record
import ffmforge.fit.TimerEvent

final class GpxXmlWriterSpec extends AnyFunSuite with Matchers {

  private val t0 = Instant.parse("2026-06-15T08:00:00Z")

  test("writes GPX 1.1 with track points and telemetry extensions") {
    val point = GpxTrackPoint(
      lat = 42.12345678,
      lon = -71.12345678,
      elevationM = Some(101.234),
      time = Some(t0),
      extensions = GpxPointExtensions(
        heartRateBpm = Some(145),
        cadenceRpm = Some(88),
        powerW = Some(250),
        speedMps = Some(9.8765),
        temperatureC = Some(23.4),
      ),
    )
    val doc = GpxDocument(
      metadata = GpxMetadata(name = Some("Test ride"), time = Some(t0)),
      tracks = Vector(GpxTrack(name = Some("Track"), segments = Vector(GpxTrackSegment(Vector(point))))),
    )

    val text = GpxXmlWriter.write(doc)
    val xml  = XML.loadString(text)

    xml.label shouldBe "gpx"
    (xml \ "@version").text shouldBe "1.1"
    text should include("xmlns:gpxtpx=")
    text should include("xmlns:ffmforge=")
    (xml \\ "trkpt").head.attribute("lat").map(_.text) shouldBe Some("42.1234568")
    (xml \\ "trkpt").head.attribute("lon").map(_.text) shouldBe Some("-71.1234568")
    (xml \\ "ele").text shouldBe "101.23"
    (xml \\ "time").map(_.text) should contain(t0.toString)
    (xml \\ "hr").text shouldBe "145"
    (xml \\ "cad").text shouldBe "88"
    (xml \\ "atemp").text shouldBe "23.4"
    (xml \\ "power").text shouldBe "250"
    (xml \\ "speed").text shouldBe "9.877"
  }

  test("omits empty point extensions") {
    val doc = GpxDocument(
      tracks = Vector(
        GpxTrack(
          segments = Vector(
            GpxTrackSegment(Vector(GpxTrackPoint(42.0, -71.0, time = Some(t0))))
          )
        )
      )
    )

    val xml = XML.loadString(GpxXmlWriter.write(doc))
    (xml \\ "extensions") shouldBe empty
  }

  test("converts FIT records to pause-aware GPX segments") {
    val recordA = record(t0, 42.0, -71.0, power = 200, temperature = 21.0)
    val recordB = record(t0.plusSeconds(1), 42.1, -71.1, power = 210, temperature = 22.0)
    val recordC = record(t0.plusSeconds(30), 42.2, -71.2, power = 220, temperature = 23.0)
    val events = Vector(
      FitViews.toMessage(Event(t0, TimerEvent.Start)),
      FitViews.toMessage(Event(t0.plusSeconds(1), TimerEvent.StopAll)),
      FitViews.toMessage(Event(t0.plusSeconds(30), TimerEvent.Start)),
      FitViews.toMessage(Event(t0.plusSeconds(31), TimerEvent.StopAll)),
    )
    val file =
      FitFile(Vector(FitViews.toMessage(FileId(timeCreated = Some(t0)))) ++ events ++ Vector(recordA, recordB, recordC))

    val gpx = FitToGpx.convert(file, trackName = Some("Paused ride"))

    gpx.tracks.head.name shouldBe Some("Paused ride")
    gpx.tracks.head.segments should have size 2
    gpx.tracks.head.segments.map(_.points.size) shouldBe Vector(2, 1)
    gpx.tracks.head.segments.head.points.head.extensions.powerW shouldBe Some(200)
    gpx.tracks.head.segments.head.points.head.extensions.temperatureC shouldBe Some(21.0)
  }

  private def record(timestamp: Instant, lat: Double, lon: Double, power: Int, temperature: Double) =
    FitViews
      .toMessage(
        Record(
          timestamp = timestamp,
          position = Some(GpsPoint(lat, lon)),
          speedMps = Some(8.0),
          altitudeM = Some(100.0),
          heartRate = Some(140),
          cadence = Some(85),
          power = Some(power),
        )
      )
      .setNumeric(FitProfile.Rec.Temperature, temperature)
}
