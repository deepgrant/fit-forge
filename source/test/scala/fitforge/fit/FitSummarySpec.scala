package fitforge.fit

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FitSummarySpec extends AnyFunSuite with Matchers {

  private val t0 = Instant.parse("2026-06-15T08:00:00Z")

  test("ride summary reports sport and power (averaged from records when the session omits them)") {
    val recs = (0 until 10).map { i =>
      Record(timestamp = t0.plus(i.toLong, ChronoUnit.SECONDS), power = Some(100 + i * 10))
    }.toVector
    val file = FitFile.of(
      FileId(),
      recs,
      sessions = Vector(Session(t0, t0.plus(9, ChronoUnit.SECONDS), sport = Some("CYCLING"))),
    )

    val r = FitSummary.ride(file)
    r.sport shouldBe Some("CYCLING")
    r.maxPowerW shouldBe Some(190.0)
    r.avgPowerW.get shouldBe 145.0 +- 0.001
  }

  test("ride summary reports avg/max temperature from record temperature fields") {
    val fileId = FitViews.toMessage(FileId(timeCreated = Some(t0)))
    val recs = (0 until 5).map { i =>
      FitMessage(FitProfile.Mesg.Record)
        .setInstant(FitProfile.Rec.Timestamp, t0.plus(i.toLong, ChronoUnit.SECONDS))
        .setNumeric(FitProfile.Rec.Temperature, 20.0 + i)
    }.toVector
    val r = FitSummary.ride(FitFile(fileId +: recs))

    r.maxTempC shouldBe Some(24.0)
    r.avgTempC.get shouldBe 22.0 +- 0.001
  }

  test("temperature is absent when no record records it") {
    val recs = Vector(Record(timestamp = t0, power = Some(150)))
    val r    = FitSummary.ride(FitFile.of(FileId(), recs))
    r.avgTempC shouldBe None
    r.maxTempC shouldBe None
  }

  test("primary device is the local head unit; ANT+ sensor kind is decoded") {
    val fileId = FitViews.toMessage(FileId(timeCreated = Some(t0)))
    val headUnit = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 0)
      .setNumeric(FitProfile.Dev.Manufacturer, 1) // Garmin
      .setNumeric(FitProfile.Dev.SourceType, 5)   // local
    val hrStrap = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 4)
      .setNumeric(FitProfile.Dev.Manufacturer, 123) // Polar
      .setNumeric(FitProfile.Dev.SourceType, 1)     // antplus
      .setNumeric(FitProfile.Dev.DeviceType, 120)   // heart_rate
    val file = FitFile(Vector(fileId, headUnit, hrStrap))

    FitSummary.devices(file) should have size 2
    FitSummary.primaryDevice(file).map(_.index) shouldBe Some(0)
    FitSummary.primaryDevice(file).map(_.manufacturer) shouldBe Some("Garmin")
    FitSummary.devices(file).find(_.index == 4).flatMap(_.kind) shouldBe Some("heart_rate")
  }
}
